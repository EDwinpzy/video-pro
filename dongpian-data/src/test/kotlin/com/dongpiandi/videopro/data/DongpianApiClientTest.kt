package com.dongpiandi.videopro.data

import com.dongpiandi.videopro.data.transport.DongpianApiClient
import com.dongpiandi.videopro.data.transport.DongpianApiClientOptions
import com.dongpiandi.videopro.data.transport.DongpianHttpMethod
import com.dongpiandi.videopro.data.transport.DongpianRequest
import com.dongpiandi.videopro.data.transport.DongpianSigningKeyProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DongpianApiClientTest {
    @Test
    fun signsEveryRequestAndOnlySendsTheProvidedCookie() =
        runTest {
            var seenCookie: String? = null
            var seenHeaders: Map<String, String?> = emptyMap()
            val engine =
                MockEngine { request ->
                    seenCookie = request.headers[HttpHeaders.Cookie]
                    seenHeaders =
                        mapOf(
                            "timestamp" to request.headers["x-ai-movie-timestamp"],
                            "nonce" to request.headers["x-ai-movie-nonce"],
                            "signature" to request.headers["x-ai-movie-signature"],
                            "client-name" to request.headers["x-ai-movie-client-name"],
                            "client-version" to request.headers["x-ai-movie-client-version"],
                            "build-version" to request.headers["x-ai-movie-build-version"],
                            "protocol-version" to request.headers["x-ai-movie-protocol-version"],
                        )
                    respond(
                        content = """{"ok":true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.SetCookie, "session=abc; Path=/; HttpOnly"),
                    )
                }
            val api =
                apiClient(
                    engine = engine,
                    keyProvider = FixedKeyProvider("fixed-key"),
                )

            val response =
                api.execute(
                    DongpianRequest(
                        method = DongpianHttpMethod.GET,
                        pathAndQuery = "/v1/users/me?x=1",
                        cookie = "session=abc",
                    ),
                )

            assertEquals(200, response.status)
            assertEquals("session=abc", seenCookie)
            assertTrue(seenHeaders.values.all { it != null })
            assertEquals(listOf("session=abc"), response.setCookies)
        }

    @Test
    fun refreshesTheSigningKeyOnceAfterInvalidSignature() =
        runTest {
            var calls = 0
            val keys = FixedKeyProvider("first-key")
            val engine =
                MockEngine {
                    calls += 1
                    if (calls == 1) {
                        respond(
                            content = """{"error":"invalid_request_signature"}""",
                            status = HttpStatusCode.Unauthorized,
                        )
                    } else {
                        respond(content = """{"ok":true}""", status = HttpStatusCode.OK)
                    }
                }
            val api = apiClient(engine = engine, keyProvider = keys)

            val response =
                api.execute(
                    DongpianRequest(
                        method = DongpianHttpMethod.GET,
                        pathAndQuery = "/v1/feed/home",
                    ),
                )

            assertEquals(200, response.status)
            assertEquals(2, calls)
            assertEquals(1, keys.invalidations)
            assertEquals(2, keys.signingCalls)
        }

    @Test
    fun doesNotReplayAPostAfterAnInvalidSignature() =
        runTest {
            var calls = 0
            val keys = FixedKeyProvider("first-key")
            val engine =
                MockEngine {
                    calls += 1
                    respond(
                        content = """{"error":"invalid_request_signature"}""",
                        status = HttpStatusCode.Unauthorized,
                    )
                }
            val api = apiClient(engine = engine, keyProvider = keys)

            val response =
                api.execute(
                    DongpianRequest(
                        method = DongpianHttpMethod.POST,
                        pathAndQuery = "/v1/users/password/login",
                        body = """{"account":"example","password":"secret"}""",
                    ),
                )

            assertEquals(401, response.status)
            assertEquals(1, calls)
            assertEquals(0, keys.invalidations)
        }

    @Test
    fun rejectsResponsesAboveTheConfiguredLimit() =
        runTest {
            val engine =
                MockEngine {
                    respond(content = "123456", status = HttpStatusCode.OK)
                }
            val api =
                apiClient(
                    engine = engine,
                    keyProvider = FixedKeyProvider("fixed-key"),
                    maxResponseBytes = 5,
                )

            assertFailsWith<DongpianException> {
                api.execute(DongpianRequest(DongpianHttpMethod.GET, "/v1/feed/home"))
            }
        }

    private fun apiClient(
        engine: MockEngine,
        keyProvider: DongpianSigningKeyProvider,
        maxResponseBytes: Int = DongpianApiClient.MAX_RESPONSE_BYTES,
    ): DongpianApiClient =
        DongpianApiClient(
            client = HttpClient(engine),
            environment = DongpianEnvironment(),
            signingKeyProvider = keyProvider,
            options =
                DongpianApiClientOptions(
                    clockMillis = { 1_700_000_000_000L },
                    nonceProvider = { "00112233445566778899aabbccddeeff" },
                    maxResponseBytes = maxResponseBytes,
                ),
        )

    private class FixedKeyProvider(
        private val key: String,
    ) : DongpianSigningKeyProvider {
        var signingCalls = 0
        var invalidations = 0

        override suspend fun signingKey(forceRefresh: Boolean): String {
            signingCalls += 1
            return key
        }

        override fun invalidate() {
            invalidations += 1
        }
    }
}
