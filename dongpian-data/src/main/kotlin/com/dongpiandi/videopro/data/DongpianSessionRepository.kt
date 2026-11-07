package com.dongpiandi.videopro.data

import com.dongpiandi.videopro.data.dto.DongpianLoginRequestDto
import com.dongpiandi.videopro.data.dto.DongpianUserDto
import com.dongpiandi.videopro.data.transport.DongpianApiClient
import com.dongpiandi.videopro.data.transport.DongpianHttpMethod
import com.dongpiandi.videopro.data.transport.DongpianRequest
import com.dongpiandi.videopro.data.transport.DongpianRequestSigner
import dev.jellystack.core.security.SecureStore
import dev.jellystack.core.security.SecureStoreException
import dev.jellystack.core.security.secretValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

data class DongpianSessionState(
    val accountScope: String,
    val displayName: String,
)

private data class StoredDongpianSession(
    val cookie: String,
    val accountScope: String,
    val displayName: String,
)

interface DongpianSessionRepository {
    val state: StateFlow<DongpianSessionState?>

    suspend fun restore(): DongpianSessionState?

    suspend fun login(
        account: String,
        password: String,
    ): DongpianSessionState

    suspend fun logout()
}

internal interface DongpianSessionCookieAccess {
    suspend fun currentCookie(): String?
}

internal class DongpianSessionRepositoryImpl(
    private val apiClient: DongpianApiClient,
    private val secureStore: SecureStore,
) : DongpianSessionRepository,
    DongpianSessionCookieAccess {
    private val mutableState = MutableStateFlow<DongpianSessionState?>(null)
    override val state: StateFlow<DongpianSessionState?> = mutableState.asStateFlow()

    @Volatile
    private var sessionCookie: String? = null

    override suspend fun restore(): DongpianSessionState? {
        val stored = readStoredSession()
        val restored =
            if (stored == null) {
                null
            } else {
                try {
                    validateStoredSession(stored)
                } catch (failure: DongpianException) {
                    if (failure.kind == DongpianFailureKind.UNAUTHORIZED) null else throw failure
                }
            }
        return if (restored == null) {
            clearMemoryAndStore()
            null
        } else {
            restored.also { mutableState.value = it }
        }
    }

    override suspend fun login(
        account: String,
        password: String,
    ): DongpianSessionState {
        val normalizedAccount = account.trim()
        if (normalizedAccount.isEmpty() || password.isEmpty()) {
            throw DongpianException(
                kind = DongpianFailureKind.INVALID_INPUT,
                message = "Account and password are required.",
            )
        }
        val stored = authenticate(normalizedAccount, password)
        return persistSession(stored).also { mutableState.value = it }
    }

    private suspend fun authenticate(
        normalizedAccount: String,
        password: String,
    ): StoredDongpianSession {
        val loginResponse =
            apiClient.execute(
                DongpianRequest(
                    method = DongpianHttpMethod.POST,
                    pathAndQuery = "/v1/users/password/login",
                    body =
                        DongpianJson.value.encodeToString(
                            DongpianLoginRequestDto(
                                account = normalizedAccount,
                                password = password,
                            ),
                        ),
                ),
            )
        if (!loginResponse.isSuccess) {
            val kind =
                if (loginResponse.status in setOf(400, 401, 403)) {
                    DongpianFailureKind.UNAUTHORIZED
                } else {
                    DongpianFailureKind.HTTP
                }
            throw DongpianException(
                kind = kind,
                message =
                    if (kind == DongpianFailureKind.UNAUTHORIZED) {
                        "Account or password is incorrect."
                    } else {
                        "Dongpian login failed."
                    },
                httpStatus = loginResponse.status,
            )
        }
        val cookie =
            loginResponse.setCookies.joinToString("; ").takeIf(String::isNotBlank)
                ?: throw DongpianException(
                    kind = DongpianFailureKind.INVALID_RESPONSE,
                    message = "Dongpian login did not return a session.",
                )
        val meResponse =
            apiClient.execute(
                DongpianRequest(
                    method = DongpianHttpMethod.GET,
                    pathAndQuery = "/v1/users/me",
                    cookie = cookie,
                ),
            )
        meResponse.requireSuccess()
        val user = meResponse.decodeUser()
        val displayName = user.displayName.orEmpty().ifBlank { normalizedAccount }
        return StoredDongpianSession(
            cookie = cookie,
            accountScope = DongpianRequestSigner.sha256Hex(user.id),
            displayName = displayName,
        )
    }

    private suspend fun persistSession(stored: StoredDongpianSession): DongpianSessionState {
        try {
            secureStore.write(KEY_COOKIE, secretValue(stored.cookie))
            secureStore.write(KEY_ACCOUNT_SCOPE, secretValue(stored.accountScope))
            secureStore.write(KEY_DISPLAY_NAME, secretValue(stored.displayName))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SecureStoreException) {
            clearMemoryAndStore()
            throw DongpianException(
                kind = DongpianFailureKind.SESSION_STORAGE,
                message = "Dongpian session could not be stored securely.",
                cause = failure,
            )
        }
        sessionCookie = stored.cookie
        return DongpianSessionState(stored.accountScope, stored.displayName)
    }

    override suspend fun logout() {
        clearMemoryAndStore()
    }

    override suspend fun currentCookie(): String? = sessionCookie

    private suspend fun readStoredSession(): StoredDongpianSession? {
        val cookie = readSecret(KEY_COOKIE)
        val accountScope = readSecret(KEY_ACCOUNT_SCOPE)
        val displayName = readSecret(KEY_DISPLAY_NAME)
        return if (cookie.isNullOrBlank() || accountScope.isNullOrBlank() || displayName.isNullOrBlank()) {
            null
        } else {
            StoredDongpianSession(cookie, accountScope, displayName)
        }
    }

    private suspend fun validateStoredSession(stored: StoredDongpianSession): DongpianSessionState? {
        val response =
            apiClient.execute(
                DongpianRequest(
                    method = DongpianHttpMethod.GET,
                    pathAndQuery = "/v1/users/me",
                    cookie = stored.cookie,
                ),
            )
        if (response.status in setOf(401, 403)) return null
        response.requireSuccess()
        val user = response.decodeUser()
        val matches = DongpianRequestSigner.sha256Hex(user.id) == stored.accountScope
        return if (matches) {
            sessionCookie = stored.cookie
            DongpianSessionState(stored.accountScope, stored.displayName)
        } else {
            null
        }
    }

    private suspend fun readSecret(key: String): String? =
        try {
            secureStore.read(key)?.reveal()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SecureStoreException) {
            throw DongpianException(
                kind = DongpianFailureKind.SESSION_STORAGE,
                message = "Dongpian session could not be read securely.",
                cause = failure,
            )
        }

    private suspend fun clearMemoryAndStore() {
        sessionCookie = null
        mutableState.value = null
        var removalFailure: SecureStoreException? = null
        for (key in listOf(KEY_COOKIE, KEY_ACCOUNT_SCOPE, KEY_DISPLAY_NAME)) {
            try {
                secureStore.remove(key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SecureStoreException) {
                if (removalFailure == null) removalFailure = failure
            }
        }
        removalFailure?.let { failure ->
            throw DongpianException(
                kind = DongpianFailureKind.SESSION_STORAGE,
                message = "Dongpian session could not be cleared securely.",
                cause = failure,
            )
        }
    }

    private companion object {
        const val KEY_COOKIE = "session.cookie"
        const val KEY_ACCOUNT_SCOPE = "session.account_scope"
        const val KEY_DISPLAY_NAME = "session.display_name"
    }
}

private fun com.dongpiandi.videopro.data.transport.DongpianHttpResponse.decodeUser(): DongpianUserDto {
    val user =
        try {
            DongpianJson.value.decodeFromString<DongpianUserDto>(body)
        } catch (failure: SerializationException) {
            throw DongpianException(
                kind = DongpianFailureKind.INVALID_RESPONSE,
                message = "Dongpian returned an invalid user response.",
                cause = failure,
            )
        }
    if (user.id.isBlank()) {
        throw DongpianException(
            kind = DongpianFailureKind.INVALID_RESPONSE,
            message = "Dongpian user identity is missing.",
        )
    }
    return user.copy(displayName = (user.displayName ?: user.name ?: user.username).orEmpty())
}
