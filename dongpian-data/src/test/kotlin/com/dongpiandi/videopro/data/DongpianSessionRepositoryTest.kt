package com.dongpiandi.videopro.data

import com.dongpiandi.videopro.data.transport.DongpianApiClient
import com.dongpiandi.videopro.data.transport.DongpianApiClientOptions
import com.dongpiandi.videopro.data.transport.DongpianSigningKeyProvider
import dev.jellystack.core.security.SecretValue
import dev.jellystack.core.security.SecureStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DongpianSessionRepositoryTest {
    @Test
    fun loginStoresOnlyTheVerifiedSessionAndCanRestoreIt() =
        runTest {
            var meStatus = HttpStatusCode.OK
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/v1/users/password/login" ->
                            respond(
                                content = "{}",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.SetCookie, "session=abc; Path=/; HttpOnly"),
                            )
                        "/v1/users/me" -> {
                            assertEquals("session=abc", request.headers[HttpHeaders.Cookie])
                            if (meStatus == HttpStatusCode.OK) {
                                respond(
                                    content = """{"id":"user-123","display_name":"测试用户"}""",
                                    status = meStatus,
                                )
                            } else {
                                respond(content = "{}", status = meStatus)
                            }
                        }
                        else -> error("unexpected path")
                    }
                }
            val store = InMemorySecureStore()
            val api = apiClient(engine)
            val repository = DongpianSessionRepositoryImpl(api, store)

            val state = repository.login("account", "secret-pass")

            assertEquals("测试用户", state.displayName)
            assertEquals("session=abc", repository.currentCookie())
            assertEquals(3, store.snapshot().size)
            assertFalse(store.snapshot().values.any { "secret-pass" in it })

            val restored = DongpianSessionRepositoryImpl(api, store)
            assertEquals(state, restored.restore())
            assertEquals("session=abc", restored.currentCookie())
        }

    @Test
    fun rejectedRestoreClearsStoredSession() =
        runTest {
            var meStatus = HttpStatusCode.OK
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/v1/users/password/login" ->
                            respond(
                                content = "{}",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.SetCookie, "session=abc"),
                            )
                        "/v1/users/me" ->
                            if (meStatus == HttpStatusCode.OK) {
                                respond(
                                    content = """{"id":"user-123","display_name":"测试用户"}""",
                                    status = meStatus,
                                )
                            } else {
                                respond(content = "{}", status = meStatus)
                            }
                        else -> error("unexpected path")
                    }
                }
            val store = InMemorySecureStore()
            val api = apiClient(engine)
            DongpianSessionRepositoryImpl(api, store).login("account", "secret-pass")
            meStatus = HttpStatusCode.Unauthorized

            val restored = DongpianSessionRepositoryImpl(api, store)

            assertNull(restored.restore())
            assertNull(restored.currentCookie())
            assertTrue(store.snapshot().isEmpty())
        }

    private fun apiClient(engine: MockEngine): DongpianApiClient =
        DongpianApiClient(
            client = HttpClient(engine),
            environment = DongpianEnvironment(),
            signingKeyProvider =
                object : DongpianSigningKeyProvider {
                    override suspend fun signingKey(forceRefresh: Boolean): String = "fixed-key"

                    override fun invalidate() = Unit
                },
            options =
                DongpianApiClientOptions(
                    clockMillis = { 1_700_000_000_000L },
                    nonceProvider = { "00112233445566778899aabbccddeeff" },
                ),
        )

    private class InMemorySecureStore : SecureStore {
        private val values = mutableMapOf<String, SecretValue>()

        override suspend fun write(
            key: String,
            value: SecretValue,
        ) {
            values[key] = value
        }

        override suspend fun read(key: String): SecretValue? = values[key]

        override suspend fun remove(key: String) {
            values.remove(key)
        }

        fun snapshot(): Map<String, String> = values.mapValues { it.value.reveal() }
    }
}
