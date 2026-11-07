package com.dongpiandi.videopro.data

import com.dongpiandi.videopro.data.transport.WebDongpianSigningKeyProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DongpianSigningKeyProviderTest {
    private val key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun discoversAndCachesTheRuntimeSigningKey() =
        runTest {
            var requests = 0
            val engine =
                MockEngine { request ->
                    requests += 1
                    val body =
                        when (request.url.encodedPath) {
                            "/" -> """<script src="/assets/main-abc.js"></script>"""
                            "/assets/main-abc.js" -> """import("./movie-card-runtime-XyZ.js");"""
                            "/assets/movie-card-runtime-XyZ.js" -> """const Jo="$key";"""
                            else -> error("unexpected path")
                        }
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                    )
                }
            val provider =
                WebDongpianSigningKeyProvider(
                    client = HttpClient(engine),
                    environment = DongpianEnvironment(),
                    clockMillis = { 1_000L },
                )

            assertEquals(key, provider.signingKey())
            assertEquals(key, provider.signingKey())
            assertEquals(3, requests)

            provider.invalidate()
            assertEquals(key, provider.signingKey())
            assertEquals(6, requests)
        }
}
