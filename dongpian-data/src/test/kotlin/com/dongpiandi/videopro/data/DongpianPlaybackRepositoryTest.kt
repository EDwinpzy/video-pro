package com.dongpiandi.videopro.data

import com.dongpiandi.videopro.data.transport.DongpianApiClient
import com.dongpiandi.videopro.data.transport.DongpianApiClientOptions
import com.dongpiandi.videopro.data.transport.DongpianSigningKeyProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DongpianPlaybackRepositoryTest {
    @Test
    fun returnsOnlyResolvedHttpsLinesWithoutCredentialsOrDuplicates() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "object":"playback.resolve",
                              "line_options":[
                                {"id":"safe","label":"线路 1","url":"https://cdn.example.com/video.m3u8","resolved":true,"resolve_required":false},
                                {"id":"duplicate","label":"重复","url":"https://cdn.example.com/video.m3u8","resolved":true,"resolve_required":false},
                                {"id":"http","url":"http://cdn.example.com/video.m3u8","resolved":true,"resolve_required":false},
                                {"id":"pending","url":"https://cdn.example.com/pending.m3u8","resolved":true,"resolve_required":true},
                                {"id":"parse","url":"https://cdn.example.com/parse.m3u8","resolved":true,"resolve_required":false,"resolve_mode":"parse"},
                                {"id":"credentials","url":"https://user:pass@cdn.example.com/video.m3u8","resolved":true,"resolve_required":false}
                              ]
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                    )
                }
            val repository = DongpianPlaybackRepositoryImpl(apiClient(engine))

            val lines = repository.resolve("episode_token_1")

            assertEquals(1, lines.size)
            assertEquals("safe", lines.single().id)
            assertEquals("https://cdn.example.com/video.m3u8", lines.single().url)
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
}
