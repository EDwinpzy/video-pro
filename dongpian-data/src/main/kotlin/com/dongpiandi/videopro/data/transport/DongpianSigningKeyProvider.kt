package com.dongpiandi.videopro.data.transport

import com.dongpiandi.videopro.data.DongpianEnvironment
import com.dongpiandi.videopro.data.DongpianException
import com.dongpiandi.videopro.data.DongpianFailureKind
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

interface DongpianSigningKeyProvider {
    suspend fun signingKey(forceRefresh: Boolean = false): String

    fun invalidate()
}

class WebDongpianSigningKeyProvider(
    private val client: HttpClient,
    private val environment: DongpianEnvironment,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = SIGNING_KEY_TTL_MILLIS,
    private val maxAssetBytes: Int = MAX_ASSET_BYTES,
) : DongpianSigningKeyProvider {
    private val mutex = Mutex()
    private var cachedKey: String? = null
    private var expiresAtMillis: Long = 0L

    override suspend fun signingKey(forceRefresh: Boolean): String =
        mutex.withLock {
            val now = clockMillis()
            val cached = cachedKey
            if (!forceRefresh && cached != null && now < expiresAtMillis) return@withLock cached
            val discovered = discoverKey()
            cachedKey = discovered
            expiresAtMillis = now + ttlMillis
            discovered
        }

    override fun invalidate() {
        cachedKey = null
        expiresAtMillis = 0L
    }

    private suspend fun discoverKey(): String {
        val home = fetchAsset("/")
        val mainPath =
            DongpianAssetDiscovery.mainAsset(home)
                ?: failSigning()
        val main = fetchAsset(mainPath)
        val runtimeAsset = DongpianAssetDiscovery.runtimeAsset(main)
        val runtime = runtimeAsset?.let { fetchAsset("/assets/$it") }
        return DongpianAssetDiscovery.signingKey(main, runtime)
            ?: failSigning()
    }

    private suspend fun fetchAsset(path: String): String {
        try {
            val response = client.get(environment.url(path))
            if (!response.status.isSuccess()) failSigning(httpStatus = response.status.value)
            val body = response.bodyAsText()
            if (body.toByteArray(Charsets.UTF_8).size > maxAssetBytes) {
                failAssetTooLarge()
            }
            return body
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            throw DongpianException(
                kind = DongpianFailureKind.NETWORK,
                message = "Dongpian service configuration could not be loaded.",
                cause = failure,
            )
        }
    }

    private fun signingFailure(httpStatus: Int? = null) =
        DongpianException(
            kind = DongpianFailureKind.SIGNING_KEY_UNAVAILABLE,
            message = "Dongpian request signing is temporarily unavailable.",
            httpStatus = httpStatus,
        )

    private fun failSigning(httpStatus: Int? = null): Nothing = throw signingFailure(httpStatus)

    private fun failAssetTooLarge(): Nothing =
        throw DongpianException(
            kind = DongpianFailureKind.RESPONSE_TOO_LARGE,
            message = "Dongpian service configuration is too large.",
        )

    companion object {
        const val SIGNING_KEY_TTL_MILLIS: Long = 10 * 60 * 1_000L
        const val MAX_ASSET_BYTES: Int = 4 * 1024 * 1024
    }
}

internal object DongpianAssetDiscovery {
    private val mainPattern = Regex("""src=["'](/assets/main-[^"']+\.js)["']""")
    private val runtimePattern = Regex("""\./(movie-card-runtime-[\w-]+\.js)""")
    private val keyPattern = Regex("""Jo\s*=\s*["']([0-9a-f]{64})["']""")

    fun mainAsset(html: String): String? = mainPattern.find(html)?.groupValues?.getOrNull(1)

    fun runtimeAsset(mainScript: String): String? = runtimePattern.find(mainScript)?.groupValues?.getOrNull(1)

    fun signingKey(
        mainScript: String,
        runtimeScript: String?,
    ): String? =
        runtimeScript?.let { keyPattern.find(it)?.groupValues?.getOrNull(1) }
            ?: keyPattern.find(mainScript)?.groupValues?.getOrNull(1)
}
