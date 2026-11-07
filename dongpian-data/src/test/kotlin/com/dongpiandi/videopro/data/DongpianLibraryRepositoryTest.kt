package com.dongpiandi.videopro.data

import com.dongpiandi.videopro.data.model.DongpianHistoryUpsertPayload
import com.dongpiandi.videopro.data.model.DongpianSyncOperation
import com.dongpiandi.videopro.data.transport.DongpianApiClient
import com.dongpiandi.videopro.data.transport.DongpianApiClientOptions
import com.dongpiandi.videopro.data.transport.DongpianSigningKeyProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DongpianLibraryRepositoryTest {
    @Test
    fun readsHistoryWithTheVerifiedQueryContract() =
        runTest {
            var requestedUrl = ""
            val engine =
                MockEngine { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        content =
                            """
                            {
                              "object":"user.watch_history.list",
                              "items":[{
                                "variant_id":"variant-1",
                                "title":"嫁金钗",
                                "episode_label":"第1集",
                                "episode_number":1,
                                "position_seconds":689,
                                "duration_seconds":2700,
                                "watched_at":"2026-10-31T05:30:00Z"
                              }],
                              "next_cursor":"next",
                              "meta":{"total_count":1}
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                    )
                }
            val repository = repository(apiClient(engine), cookie = "session=abc")

            val page = repository.history(limit = 24, query = "星际")

            assertEquals(1, page.items.size)
            assertEquals(689, page.items.single().positionSeconds)
            assertEquals("next", page.nextCursor)
            assertTrue("update_view=text" in requestedUrl)
            assertTrue("q=%E6%98%9F%E9%99%85" in requestedUrl)
        }

    @Test
    fun syncRequiresEveryOperationToBeAccepted() =
        runTest {
            var accepted = true
            val engine =
                MockEngine { request ->
                    if (request.method != HttpMethod.Post) error("unexpected method")
                    val body =
                        if (accepted) {
                            """{"object":"user.library_sync","accepted_op_ids":["op-1"],"rejected_ops":[]}"""
                        } else {
                            """{"object":"user.library_sync","accepted_op_ids":[],"rejected_ops":[]}"""
                        }
                    respond(content = body, status = HttpStatusCode.OK)
                }
            val repository = repository(apiClient(engine), cookie = "session=abc")
            val operation =
                DongpianSyncOperation.HistoryUpsert(
                    opId = "op-1",
                    payload =
                        DongpianHistoryUpsertPayload(
                            variantId = "variant-1",
                            title = "嫁金钗",
                            episodeId = "episode-1",
                            episodeLabel = "第1集",
                            episodeNumber = 1,
                            episodeToken = "token-1",
                            positionSeconds = 689,
                            durationSeconds = 2700,
                            watchedAt = "2026-10-31T05:30:00Z",
                            posterUrl = null,
                            backdropUrl = null,
                            contentKind = "series",
                            area = null,
                            language = null,
                            genres = emptyList(),
                        ),
                )

            assertEquals(listOf("op-1"), repository.sync(listOf(operation)).acceptedOpIds)
            accepted = false
            assertFailsWith<DongpianException> { repository.sync(listOf(operation)) }
        }

    @Test
    fun compactStatusUsesVariantIdsAndParsesResume() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertTrue("ids=variant-1" in request.url.toString())
                    assertTrue("view=compact" in request.url.toString())
                    respond(
                        content =
                            """
                            {"object":"user.library.status","items":[{
                              "variant_id":"variant-1",
                              "favorite":true,
                              "resume":{"episode_id":"episode-1","episode_token":"token-1","position_seconds":739}
                            }]}
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                    )
                }
            val repository = repository(apiClient(engine), cookie = "session=abc")

            val status = repository.resumeStatus(listOf("variant-1")).single()

            assertTrue(status.favorite)
            assertEquals(739, status.resume?.positionSeconds)
            assertEquals("episode-1", status.resume?.episodeId)
        }

    private fun repository(
        apiClient: DongpianApiClient,
        cookie: String,
    ): DongpianLibraryRepositoryImpl =
        DongpianLibraryRepositoryImpl(
            apiClient = apiClient,
            sessionCookieAccess = FakeSessionCookieAccess(cookie),
        )

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

    private class FakeSessionCookieAccess(
        private val cookie: String,
    ) : DongpianSessionCookieAccess {
        override suspend fun currentCookie(): String = cookie
    }
}
