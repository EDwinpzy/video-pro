package com.qiubo.optimaltv.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.qiubo.optimaltv.BuildConfig
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class DongpianAuthSession(
    val userId: String,
    val displayName: String,
    val cookie: String,
)

data class DongpianRememberedLogin(
    val account: String,
    val password: String,
)

internal data class DongpianAuthSessionSnapshot(
    val revision: Long,
    val session: DongpianAuthSession,
)

class DongpianAuthException(
    message: String,
    val unauthorized: Boolean = false,
    val statusCode: Int = 0,
    val wireCode: String = "",
    val topLevelWireCode: String = wireCode,
) : Exception(message)

/**
 * Small login adapter for the migrated TV frontend.
 *
 * The legacy frontend still uses its own VOD transport. This adapter only owns the
 * Dongpiandi account gate, so migrating the existing screens does not require
 * rewriting their catalog, detail, search, or playback code.
 */
object DongpianAuthClient {
    internal const val BASE_URL = "https://ai.dongpian20.com"
    private const val PREFS_NAME = "video_pro_auth"
    private const val REMEMBERED_LOGIN_PREFS_NAME = "video_pro_remembered_login"
    private const val LEGACY_PREFS_NAME = "dongpian-session"
    private const val RUNTIME_PREFS_NAME = "video_pro_runtime"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_REMEMBERED_ACCOUNT = "account"
    private const val KEY_REMEMBERED_PASSWORD = "password"
    private const val KEY_SIGNING_KEY = "signing_key"
    private const val KEY_SIGNING_KEY_EXPIRES_AT = "signing_key_expires_at"
    private const val CLIENT_NAME = "movie-search-frontend"
    private const val CLIENT_VERSION = "1.0.0"
    private const val BUILD_VERSION =
        "dongpiandi-v2026.10.30.2-api-build-boundary-ee3385411179-web"
    private const val PROTOCOL_VERSION = "2026-07-05.library-v2.playback-v1"
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024
    private const val SIGNING_KEY_TTL_MS = 10 * 60 * 1000L
    private const val NETWORK_ERROR = "网络连接失败，请检查网络后重试。"
    private val accountPattern = Regex("^[a-z0-9][a-z0-9._@+-]{2,127}$", RegexOption.IGNORE_CASE)
    private val signingKeyPattern = Regex("""["']([0-9a-f]{64})["']""")
    private const val SIGNING_HEADER = "x-ai-movie-signature"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val http =
        OkHttpClient.Builder()
            .dns(DongpianDns)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    private val random = SecureRandom()
    private val sessionRevision = SessionRevisionGuard()
    private val securePreferencesCache = CachedPreferences<SharedPreferences>()
    private val rememberedLoginPreferencesCache = CachedPreferences<SharedPreferences>()
    @Volatile
    private var cachedSigningKey: String? = null
    @Volatile
    private var signingKeyExpiresAt: Long = 0L

    private val _sessionState = MutableStateFlow<DongpianAuthSession?>(null)
    val sessionState: StateFlow<DongpianAuthSession?> = _sessionState.asStateFlow()

    private val _sessionRevisionState = MutableStateFlow<Long?>(null)
    val sessionRevisionState: StateFlow<Long?> = _sessionRevisionState.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    suspend fun restore(context: Context): DongpianAuthSession? =
        withContext(Dispatchers.IO) {
            val (revision, stored) = sessionRevision.snapshot { readStoredSession(context) }
            stored ?: return@withContext null
            try {
                validate(context, stored).also {
                    currentCoroutineContext().ensureActive()
                    commitSessionResult(revision) {
                        storeSession(context, it)
                        _sessionState.value = it
                        _sessionRevisionState.value = revision
                        _notice.value = null
                    }
                }
            } catch (failure: DongpianAuthException) {
                if (failure.unauthorized) {
                    currentCoroutineContext().ensureActive()
                    commitSessionResult(revision) { invalidateSession(context) }
                    null
                } else {
                    throw failure
                }
            }
        }

    suspend fun login(
        context: Context,
        account: String,
        password: String,
        rememberCredentials: Boolean? = null,
    ): DongpianAuthSession =
        withContext(Dispatchers.IO) {
            val normalizedAccount = validateLoginCredentials(account, password)
            val revision = sessionRevision.beginTransition()
            try {
                val body =
                    JSONObject()
                        .put("account", normalizedAccount)
                        .put("password", password)
                        .toString()
                var signingKey = networkBound("signing_key") { signingKey(context) }
                var response =
                    networkBound("login_post") {
                        signedRequest(
                            key = signingKey,
                            method = "POST",
                            pathAndQuery = "/v1/users/password/login",
                            body = body,
                        )
                    }
                if (isSignatureRejected(response.code, response.body)) {
                    invalidateSigningKey(context)
                    signingKey = networkBound("signing_key_retry") { signingKey(context) }
                    response =
                        networkBound("login_post_retry") {
                            signedRequest(
                                key = signingKey,
                                method = "POST",
                                pathAndQuery = "/v1/users/password/login",
                                body = body,
                            )
                        }
                }
                if (response.code !in 200..299) {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.w(
                            "DongpianAuth",
                            "login failed status=${response.code}",
                        )
                    }
                    throw DongpianAuthException(
                        message = loginErrorMessage(response.code, response.body),
                        unauthorized = response.code in setOf(401, 403),
                    )
                }
                val loginCookie =
                    mergeCookieHeader("", response.setCookies).takeIf { it.isNotBlank() }
                        ?: throw DongpianAuthException("登录响应没有返回会话，请稍后重试。")
                if (BuildConfig.DEBUG) {
                    android.util.Log.i(
                        "DongpianAuth",
                        "login accepted cookies=${loginCookie.split(';').size} " +
                            "names=${cookieNames(loginCookie).joinToString()}",
                    )
                }
                val validated =
                    networkBound("me_get") { requestUser(context, loginCookie, signingKey) }
                val cookie = mergeCookieHeader(loginCookie, validated.setCookies)
                val displayName =
                    validated.user.displayName
                        .takeIf { it.isNotBlank() }
                        ?: normalizedAccount
                val session = DongpianAuthSession(validated.user.id, displayName, cookie)
                currentCoroutineContext().ensureActive()
                when (rememberCredentials) {
                    true -> rememberLogin(context, normalizedAccount, password)
                    false -> forgetRememberedLogin(context)
                    null -> Unit
                }
                commitSessionResult(revision, completesTransition = true) {
                    storeSession(context, session)
                    _sessionState.value = session
                    _sessionRevisionState.value = revision
                    _notice.value = null
                }
                session
            } finally {
                sessionRevision.finishTransition(revision)
            }
        }

    suspend fun logout(context: Context) {
        withContext(Dispatchers.IO) {
            sessionRevision.change {
                clearStoredSession(context)
                clearRememberedLogin(context)
                _sessionState.value = null
                _sessionRevisionState.value = null
                _notice.value = "已退出登录。"
            }
        }
    }

    suspend fun rememberedLogin(context: Context): DongpianRememberedLogin? =
        withContext(Dispatchers.IO) {
            val prefs =
                runCatching { rememberedLoginPreferences(context) }.getOrElse {
                    clearRememberedLogin(context)
                    return@withContext null
                }
            val account = prefs.getString(KEY_REMEMBERED_ACCOUNT, null).orEmpty().trim()
            val password = prefs.getString(KEY_REMEMBERED_PASSWORD, null).orEmpty()
            if (account.isEmpty() || password.isEmpty()) {
                null
            } else {
                DongpianRememberedLogin(account = account, password = password)
            }
        }

    suspend fun rememberLogin(
        context: Context,
        account: String,
        password: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val normalizedAccount =
                runCatching { validateLoginCredentials(account, password) }.getOrNull()
                    ?: return@withContext false
            runCatching {
                rememberedLoginPreferences(context)
                    .edit()
                    .putString(KEY_REMEMBERED_ACCOUNT, normalizedAccount)
                    .putString(KEY_REMEMBERED_PASSWORD, password)
                    .commit()
            }.getOrDefault(false)
        }

    suspend fun forgetRememberedLogin(context: Context) {
        withContext(Dispatchers.IO) { clearRememberedLogin(context) }
    }

    /**
     * Restores an already-validated, in-process session without asking the device owner
     * to enter account A's password again. Only debug builds can call this test seam;
     * callers must keep the captured session in memory.
     */
    internal suspend fun restoreCapturedSessionForTesting(
        context: Context,
        captured: DongpianAuthSession,
    ): DongpianAuthSession =
        withContext(Dispatchers.IO) {
            check(BuildConfig.DEBUG) { "Captured session restore is debug-only." }
            require(captured.cookie.isNotBlank()) { "Captured session must contain a cookie." }
            val restored = networkBound("captured_session_restore") { validate(context, captured) }
            currentCoroutineContext().ensureActive()
            val revision = sessionRevision.beginTransition()
            try {
                commitSessionResult(revision, completesTransition = true) {
                    storeSession(context, restored)
                    _sessionState.value = restored
                    _sessionRevisionState.value = revision
                    _notice.value = null
                }
                restored
            } finally {
                sessionRevision.finishTransition(revision)
            }
        }

    fun isSignedIn(context: Context): Boolean =
        runCatching { currentSession(context)?.cookie?.isNotBlank() == true }.getOrDefault(false)

    fun hasStoredSession(context: Context): Boolean =
        runCatching { readStoredSession(context)?.cookie?.isNotBlank() == true }
            .getOrDefault(false)

    fun currentSession(context: Context): DongpianAuthSession? =
        currentSessionSnapshot(context)?.session

    internal fun currentSessionSnapshot(context: Context): DongpianAuthSessionSnapshot? =
        sessionRevision.snapshot {
            _sessionState.value ?: runCatching { readStoredSession(context) }.getOrNull()
        }.let { (revision, session) ->
            session?.takeUnless { sessionRevision.isTransitioning(revision) }?.let {
                DongpianAuthSessionSnapshot(revision, it)
            }
        }

    fun apiJson(
        context: Context,
        pathAndQuery: String,
        method: String = "GET",
        body: String? = null,
    ): JSONObject = apiJsonInternal(context, pathAndQuery, method, body, expectedUserId = null)

    fun apiJsonForUser(
        context: Context,
        pathAndQuery: String,
        method: String = "GET",
        body: String? = null,
        expectedUserId: String,
    ): JSONObject =
        apiJsonInternal(context, pathAndQuery, method, body, expectedUserId)

    private fun apiJsonInternal(
        context: Context,
        pathAndQuery: String,
        method: String,
        body: String?,
        expectedUserId: String?,
    ): JSONObject {
        val (revision, stored) = sessionRevision.snapshot { readStoredSession(context) }
        if (sessionRevision.isTransitioning(revision)) {
            throw DongpianAuthException("账号正在切换，请稍后重试。", statusCode = 409)
        }
        if (stored == null || stored.cookie.isBlank()) {
            commitSessionResult(revision) { invalidateSession(context) }
            throw DongpianAuthException("登录已失效，请重新登录。", unauthorized = true)
        }
        if (
            expectedUserId != null &&
            (
                stored.userId != expectedUserId ||
                    _sessionState.value?.userId?.let { it != expectedUserId } == true
            )
        ) {
            throw DongpianAuthException(
                "账号已切换，已停止旧会话请求。",
                statusCode = 409,
            )
        }
        var response =
            networkBound("api_request") {
                signedRequest(
                    signingKey(context),
                    method,
                    pathAndQuery,
                    body = body,
                    cookie = stored.cookie,
                )
            }
        val setCookies = response.setCookies.toMutableList()
        if (response.code == 401 && "invalid_request_signature" in response.body) {
            invalidateSigningKey(context)
            response =
                networkBound("api_request_retry") {
                    signedRequest(
                        signingKey(context),
                        method,
                        pathAndQuery,
                        body = body,
                        cookie = mergeCookieHeader(stored.cookie, setCookies),
                    )
                }
            setCookies += response.setCookies
        }
        commitSessionResult(revision) {
            if (isSessionRejected(response.code, response.body)) {
                invalidateSession(context)
            }
        }
        if (isSessionRejected(response.code, response.body)) {
            throw DongpianAuthException(
                "登录已失效，请重新登录。",
                unauthorized = true,
                statusCode = response.code,
                wireCode = wireErrorCode(response.body),
                topLevelWireCode = topLevelWireErrorCode(response.body),
            )
        }
        if (response.code !in 200..299) {
            throw DongpianAuthException(
                "懂片帝服务暂时不可用（HTTP ${response.code}）。",
                statusCode = response.code,
                wireCode = wireErrorCode(response.body),
                topLevelWireCode = topLevelWireErrorCode(response.body),
            )
        }
        val refreshedCookie = mergeCookieHeader(stored.cookie, setCookies)
        if (refreshedCookie != stored.cookie) {
            val refreshed = stored.copy(cookie = refreshedCookie)
            commitSessionResult(revision) {
                storeSession(context, refreshed)
                if (_sessionState.value?.userId == refreshed.userId) {
                    _sessionState.value = refreshed
                }
            }
        }
        val json =
            runCatching { JSONObject(response.body) }
                .getOrElse { throw DongpianAuthException("懂片帝服务返回了异常数据。") }
        commitSessionResult(revision) { }
        return json
    }

    private fun signingKey(context: Context): String {
        val now = System.currentTimeMillis()
        cachedSigningKey?.takeIf { now < signingKeyExpiresAt }?.let { return it }
        val prefs = runCatching { runtimePreferences(context) }.getOrNull()
        val storedKey = prefs?.getString(KEY_SIGNING_KEY, null).orEmpty()
        val storedExpiry = prefs?.getLong(KEY_SIGNING_KEY_EXPIRES_AT, 0L) ?: 0L
        if (storedKey.isNotBlank() && now < storedExpiry) {
            cachedSigningKey = storedKey
            signingKeyExpiresAt = storedExpiry
            return storedKey
        }
        return discoverSigningKey().also { key ->
            rememberSigningKey(context, key, now + SIGNING_KEY_TTL_MS)
        }
    }

    private fun rememberSigningKey(
        context: Context,
        key: String,
        expiresAt: Long,
    ) {
        cachedSigningKey = key
        signingKeyExpiresAt = expiresAt
        runCatching {
            runtimePreferences(context)
                .edit()
                .putString(KEY_SIGNING_KEY, key)
                .putLong(KEY_SIGNING_KEY_EXPIRES_AT, expiresAt)
                .apply()
        }
    }

    private fun invalidateSigningKey(context: Context) {
        cachedSigningKey = null
        signingKeyExpiresAt = 0L
        runCatching {
            runtimePreferences(context)
                .edit()
                .remove(KEY_SIGNING_KEY)
                .remove(KEY_SIGNING_KEY_EXPIRES_AT)
                .apply()
        }
    }

    private fun validate(
        context: Context,
        stored: DongpianAuthSession,
    ): DongpianAuthSession {
        val key = networkBound("signing_key") { signingKey(context) }
        val validated =
            try {
                networkBound("me_get") { requestUser(context, stored.cookie, key) }
            } catch (failure: DongpianAuthException) {
                if (failure.unauthorized) throw failure
                throw DongpianAuthException(
                    failure.message ?: "无法验证登录状态，请检查网络后重试。",
                )
            }
        val refreshed = stored.copy(
            userId = validated.user.id,
            displayName =
                validated.user.displayName
                    .takeIf { it.isNotBlank() }
                    ?: stored.displayName,
            cookie = mergeCookieHeader(stored.cookie, validated.setCookies),
        )
        return refreshed
    }

    private fun requestUser(
        context: Context,
        cookie: String,
        signingKey: String,
    ): DongpianUserValidation {
        var response =
            signedRequest(
                key = signingKey,
                method = "GET",
                pathAndQuery = "/v1/users/me",
                cookie = cookie,
            )
        val setCookies = response.setCookies.toMutableList()
        if (response.code == 401 && "invalid_request_signature" in response.body) {
            invalidateSigningKey(context)
            response =
                signedRequest(
                    key = signingKey(context),
                    method = "GET",
                    pathAndQuery = "/v1/users/me",
                    cookie = mergeCookieHeader(cookie, setCookies),
                )
            setCookies += response.setCookies
        }
        if (isSessionRejected(response.code, response.body)) {
            if (BuildConfig.DEBUG) {
                android.util.Log.w(
                    "DongpianAuth",
                    "session rejected status=${response.code}",
                )
            }
            throw DongpianAuthException("登录已失效，请重新登录。", unauthorized = true)
        }
        if (response.code !in 200..299) {
            throw DongpianAuthException("账号服务暂时不可用，请稍后重试。")
        }
        val json =
            runCatching { JSONObject(response.body) }
                .getOrElse { throw DongpianAuthException("账号服务返回了异常数据。") }
        val id = json.optString("id").trim()
        if (id.isEmpty()) {
            throw DongpianAuthException("账号服务返回的身份信息不完整。")
        }
        return DongpianUser(
            id = id,
            displayName =
                json.optString("display_name")
                    .ifBlank { json.optString("name") }
                    .ifBlank { json.optString("username") },
        ).let { DongpianUserValidation(it, setCookies) }
    }

    private fun discoverSigningKey(): String {
        val home = getText(BASE_URL + "/", "signing_home")
        val mainPath =
            Regex("""src=["'](/assets/main-[^"']+\.js)["']""")
                .find(home)
                ?.groupValues
                ?.getOrNull(1)
                ?: throw DongpianAuthException("无法读取登录服务配置，请稍后重试。")
        val mainScript = getText(BASE_URL + mainPath, "signing_main")
        val runtimeName =
            Regex("""\./(movie-card-runtime-[\w-]+\.js)""")
                .find(mainScript)
                ?.groupValues
                ?.getOrNull(1)
        val runtimeScript = runtimeName?.let { getText("$BASE_URL/assets/$it", "signing_runtime") }
        return extractSigningKey(runtimeScript, mainScript)
            ?: throw DongpianAuthException("无法读取登录签名配置，请稍后重试。")
    }

    internal fun extractSigningKey(vararg scripts: String?): String? {
        val bundles = scripts.filterNotNull()
        bundles.forEach { script ->
            val headerIndex = script.indexOf(SIGNING_HEADER)
            if (headerIndex < 0) return@forEach
            signingKeyPattern.findAll(script)
                .minByOrNull { kotlin.math.abs(it.range.first - headerIndex) }
                ?.groupValues
                ?.getOrNull(1)
                ?.let { return it }
        }
        return bundles.firstNotNullOfOrNull { script ->
            signingKeyPattern.findAll(script)
                .singleOrNull()
                ?.groupValues
                ?.getOrNull(1)
        }
    }

    private fun getText(
        url: String,
        stage: String,
    ): String =
        networkBound(stage) {
            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code !in 200..299) {
                    throw DongpianAuthException("登录服务配置请求失败（HTTP ${response.code}）。")
                }
                if (body.toByteArray(StandardCharsets.UTF_8).size > MAX_BODY_BYTES) {
                    throw DongpianAuthException("登录服务配置异常。")
                }
                body
            }
        }

    private fun signedRequest(
        key: String,
        method: String,
        pathAndQuery: String,
        body: String? = null,
        cookie: String? = null,
    ): HttpResponse {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = newNonce()
        val signature = sign(key, method, pathAndQuery, timestamp, nonce)
        val requestBody =
            body?.toRequestBody(jsonMediaType)
                ?: if (method == "POST") "".toRequestBody(jsonMediaType) else null
        val request =
            Request.Builder()
                .url(BASE_URL + pathAndQuery)
                .header("Accept", "application/json")
                .header("x-ai-movie-timestamp", timestamp)
                .header("x-ai-movie-nonce", nonce)
                .header("x-ai-movie-signature", signature)
                .header("x-ai-movie-client-name", CLIENT_NAME)
                .header("x-ai-movie-client-version", CLIENT_VERSION)
                .header("x-ai-movie-build-version", BUILD_VERSION)
                .header("x-ai-movie-protocol-version", PROTOCOL_VERSION)
                .apply { cookie?.takeIf(String::isNotBlank)?.let { header("Cookie", it) } }
                .method(method, requestBody)
                .build()
        http.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (responseBody.toByteArray(StandardCharsets.UTF_8).size > MAX_BODY_BYTES) {
                throw DongpianAuthException("登录服务返回了异常数据。")
            }
            return HttpResponse(response.code, responseBody, response.headers("Set-Cookie"))
        }
    }

    private fun sign(
        key: String,
        method: String,
        pathAndQuery: String,
        timestamp: String,
        nonce: String,
    ): String {
        val payload = "$method\n$pathAndQuery\n$timestamp\n$nonce"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    private fun newNonce(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return bytes.toHex()
    }

    private fun ByteArray.toHex(): String =
        buildString(size * 2) {
            for (byte in this@toHex) {
                append(HEX[(byte.toInt() ushr 4) and 0x0f])
                append(HEX[byte.toInt() and 0x0f])
            }
        }

    private fun encryptedPreferences(
        context: Context,
        name: String,
    ): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            name,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private fun createPreferences(context: Context): SharedPreferences =
        encryptedPreferences(context, PREFS_NAME)

    private fun preferences(context: Context): SharedPreferences =
        securePreferencesCache.get {
            createPreferences(context.applicationContext)
        }

    private fun rememberedLoginPreferences(context: Context): SharedPreferences =
        rememberedLoginPreferencesCache.get {
            encryptedPreferences(context.applicationContext, REMEMBERED_LOGIN_PREFS_NAME)
        }

    private fun runtimePreferences(context: Context) =
        context.getSharedPreferences(RUNTIME_PREFS_NAME, Context.MODE_PRIVATE)

    private fun legacyPreferences(context: Context) =
        EncryptedSharedPreferences.create(
            context,
            LEGACY_PREFS_NAME,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private fun readStoredSession(context: Context): DongpianAuthSession? {
        val prefs = runCatching { preferences(context) }.getOrElse {
            clearStoredSession(context)
            return null
        }
        var cookie = prefs.getString(KEY_COOKIE, null).orEmpty()
        var name = prefs.getString(KEY_DISPLAY_NAME, null).orEmpty()
        var userId = prefs.getString(KEY_USER_ID, null).orEmpty()
        if (cookie.isBlank()) {
            runCatching {
                val legacy = legacyPreferences(context)
                cookie = legacy.getString("session.cookie", null).orEmpty()
                name = legacy.getString("session.display_name", null).orEmpty()
            }
        }
        return if (cookie.isBlank()) {
            null
        } else {
            DongpianAuthSession(
                userId = userId,
                displayName = name.ifBlank { "懂片帝用户" },
                cookie = cookie,
            )
        }
    }

    private fun storeSession(
        context: Context,
        session: DongpianAuthSession,
    ) {
        securePreferencesCache.update(
            create = { createPreferences(context.applicationContext) },
        ) { prefs ->
            prefs
                .edit()
                .putString(KEY_COOKIE, session.cookie)
                .putString(KEY_DISPLAY_NAME, session.displayName)
                .putString(KEY_USER_ID, session.userId)
                .commit()
        }
        // The migrated cookie now lives in video_pro_auth; never let the old store
        // resurrect a previous account after logout.
        runCatching { legacyPreferences(context).edit().clear().commit() }
        runCatching { context.deleteSharedPreferences(LEGACY_PREFS_NAME) }
    }

    private fun clearStoredSession(context: Context) {
        securePreferencesCache.invalidateAndDelete(
            clear = { it.edit().clear().commit() },
            delete = { context.deleteSharedPreferences(PREFS_NAME) },
        )
        runCatching { legacyPreferences(context).edit().clear().commit() }
        runCatching { context.deleteSharedPreferences(LEGACY_PREFS_NAME) }
    }

    private fun invalidateSession(context: Context) {
        sessionRevision.change {
            clearStoredSession(context)
            _sessionState.value = null
            _sessionRevisionState.value = null
            _notice.value = "登录已失效，请重新登录。"
        }
    }

    private fun clearRememberedLogin(context: Context) {
        rememberedLoginPreferencesCache.invalidateAndDelete(
            clear = { it.edit().clear().commit() },
            delete = { context.deleteSharedPreferences(REMEMBERED_LOGIN_PREFS_NAME) },
        )
    }

    private fun commitSessionResult(
        revision: Long,
        completesTransition: Boolean = false,
        block: () -> Unit,
    ) {
        val committed =
            if (completesTransition) {
                sessionRevision.commitTransition(revision, block)
            } else {
                sessionRevision.commit(revision, block)
            }
        if (!committed) {
            throw DongpianAuthException("账号已切换，已停止旧会话请求。", statusCode = 409)
        }
    }

    private fun <T> networkBound(
        stage: String,
        block: () -> T,
    ): T {
        val startedAt = System.currentTimeMillis()
        return try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: DongpianAuthException) {
            throw failure
        } catch (failure: IOException) {
            logNetworkFailure(stage, startedAt, failure)
            throw DongpianAuthException(NETWORK_ERROR)
        } catch (failure: Exception) {
            logNetworkFailure(stage, startedAt, failure)
            throw DongpianAuthException(NETWORK_ERROR)
        }
    }

    private fun logNetworkFailure(
        stage: String,
        startedAt: Long,
        failure: Throwable,
    ) {
        if (BuildConfig.DEBUG) {
            android.util.Log.w(
                "DongpianAuth",
                "network stage=$stage elapsedMs=${System.currentTimeMillis() - startedAt} " +
                    "failure=${failure.javaClass.simpleName}",
            )
        }
    }

    internal fun loginErrorMessage(
        code: Int,
        body: String,
    ): String {
        val normalized = body.lowercase()
        val serverMessage = serverErrorMessage(body)
        return when {
            isSignatureRejected(code, body) ->
                "登录服务配置异常，请稍后重试。"
            code == 429 ||
                "too many requests" in normalized ||
                "rate_limited" in normalized ->
                "操作太频繁，请稍后重试。"
            "account or password is invalid" in normalized ||
                "invalid credentials" in normalized ||
                "password is invalid" in normalized ||
                code in setOf(400, 401, 403) ->
                "账号或密码错误。"
            code >= 500 -> "登录服务暂时不可用，请稍后重试。"
            !serverMessage.isNullOrBlank() -> serverMessage
            else -> "登录服务暂时不可用，请稍后重试。"
        }
    }

    private fun serverErrorMessage(body: String): String? {
        val parsed = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val message =
            parsed.optString("message")
                .ifBlank { parsed.optString("error") }
                .trim()
        if (message.isBlank()) return null
        val normalized = message.lowercase()
        if (
            "<!doctype" in normalized ||
            "<html" in normalized ||
            "<body" in normalized ||
            "<head" in normalized
        ) {
            return null
        }
        return message.take(180)
    }

    internal fun isSessionRejected(
        code: Int,
        body: String,
    ): Boolean {
        if (isSignatureRejected(code, body)) return false
        if (code == 401) return true
        if (code != 403) return false
        val normalized = body.lowercase()
        return listOf(
            "session expired",
            "session_expired",
            "invalid session",
            "not authenticated",
            "unauthorized",
            "login required",
            "请先登录",
            "登录已失效",
            "会话已失效",
        ).any(normalized::contains)
    }

    internal fun isSignatureRejected(
        code: Int,
        body: String,
    ): Boolean {
        if (code !in setOf(401, 403)) return false
        val normalized = body.lowercase()
        return "invalid_request_signature" in normalized ||
            "invalid signature" in normalized ||
            "signature_expired" in normalized
    }

    internal fun validateLoginCredentials(
        account: String,
        password: String,
    ): String {
        val normalized = account.trim()
        if (normalized.isEmpty()) throw DongpianAuthException("请输入账号。")
        if (normalized.length < 3) throw DongpianAuthException("账号至少 3 位。")
        if (!accountPattern.matches(normalized)) {
            throw DongpianAuthException("账号只能使用字母、数字、点、下划线、@、加号或短横线。")
        }
        if (password.isEmpty()) throw DongpianAuthException("请输入密码。")
        if (password.length < 8) throw DongpianAuthException("密码至少 8 位。")
        if (password.length > 128) throw DongpianAuthException("密码最多 128 位。")
        return normalized
    }

    private fun cookieNames(header: String): List<String> =
        header.split(';').mapNotNull { part ->
            part.substringBefore('=', "").trim().takeIf(String::isNotBlank)
        }

    private data class DongpianUser(
        val id: String,
        val displayName: String,
    )

    private data class DongpianUserValidation(
        val user: DongpianUser,
        val setCookies: List<String>,
    )

    private data class HttpResponse(
        val code: Int,
        val body: String,
        val setCookies: List<String>,
    )

    private const val HEX = "0123456789abcdef"
}

/**
 * Applies Set-Cookie updates to a Cookie request header by cookie name.
 * Max-Age=0 removes an existing cookie; other attributes are intentionally ignored.
 */
internal fun mergeCookieHeader(
    current: String,
    setCookieHeaders: List<String>,
): String {
    val jar = linkedMapOf<String, String>()
    current.split(';').forEach { part ->
        val name = part.substringBefore('=', "").trim()
        if (name.isBlank() || '=' !in part) return@forEach
        jar[name] = part.substringAfter('=').trim()
    }
    setCookieHeaders.forEach { raw ->
        val segments = raw.split(';')
        val pair = segments.firstOrNull().orEmpty()
        val name = pair.substringBefore('=', "").trim()
        if (name.isBlank() || '=' !in pair) return@forEach
        val expired =
            segments.drop(1).any { attribute ->
                val normalized = attribute.trim().lowercase()
                normalized == "max-age=0" ||
                    (normalized.startsWith("max-age=") &&
                        normalized.substringAfter('=').toLongOrNull()?.let { it <= 0 } == true)
            }
        if (expired) {
            jar.remove(name)
        } else {
            jar[name] = pair.substringAfter('=').trim()
        }
    }
    return jar.entries.joinToString("; ") { (name, value) -> "$name=$value" }
}

/** Extracts the stable wire error code without retaining response bodies in logs or state. */
internal fun topLevelWireErrorCode(body: String): String {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return ""
    val code = (root.opt("code") as? String)?.trim().orEmpty()
    return code.takeIf { it.matches(Regex("^[a-z0-9_.:-]{1,120}$", RegexOption.IGNORE_CASE)) }.orEmpty()
}

internal fun wireErrorCode(body: String): String {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return ""
    fun objectAt(value: Any?): JSONObject? = value as? JSONObject
    fun scalarAt(value: Any?): String? =
        (value as? String)?.trim()?.takeIf(String::isNotBlank)

    val payload = objectAt(root.opt("payload"))
    val error = objectAt(root.opt("error"))
    val candidates = listOf(
        scalarAt(root.opt("code")),
        scalarAt(root.opt("error")),
        payload?.let { scalarAt(it.opt("code")) },
        payload?.let { scalarAt(it.opt("error")) },
        error?.let { scalarAt(it.opt("code")) },
        error?.let { scalarAt(it.opt("type")) },
    )
    return candidates.firstNotNullOfOrNull { candidate ->
        candidate
            ?.takeIf { it.matches(Regex("^[a-z0-9_.:-]{1,120}$", RegexOption.IGNORE_CASE)) }
    }.orEmpty()
}
