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
import kotlin.test.assertTrue

class DongpianCatalogRepositoryTest {
    @Test
    fun mapsHomeCardsAndIgnoresUnknownFields() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "object":"home.feed",
                              "future_root_field":true,
                              "sections":[{
                                "id":"continue",
                                "title":"继续观看",
                                "cards":[{
                                  "id":"variant-1",
                                  "title":"嫁金钗",
                                  "content_kind":"series",
                                  "genres":["古装"],
                                  "future_card_field":{"x":1}
                                }]
                              }]
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                    )
                }
            val repository = DongpianCatalogRepositoryImpl(apiClient(engine))

            val home = repository.loadHome()

            assertEquals(1, home.sections.size)
            assertEquals(
                "嫁金钗",
                home.sections
                    .single()
                    .cards
                    .single()
                    .title,
            )
            assertEquals(
                "variant-1",
                home.sections
                    .single()
                    .cards
                    .single()
                    .variantId,
            )
        }

    @Test
    fun searchUsesTheVerifiedCatalogQueryAndPagination() =
        runTest {
            var url = ""
            val engine =
                MockEngine { request ->
                    url = request.url.toString()
                    respond(
                        content =
                            """
                            {
                              "object":"catalog.browse",
                              "cards":[{"id":"v1","title":"星际穿越"}],
                              "pagination":{"has_more":true,"next_cursor":"page-2"}
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                    )
                }
            val repository = DongpianCatalogRepositoryImpl(apiClient(engine))

            val page = repository.search("星际", page = 2, limit = 20)

            assertTrue("q=%E6%98%9F%E9%99%85" in url)
            assertTrue("page=2" in url)
            assertEquals("page-2", page.nextCursor)
            assertTrue(page.hasMore)
        }

    @Test
    fun detailUsesTheVerifiedPathWithoutTheDetailSuffix() =
        runTest {
            var path = ""
            val engine =
                MockEngine { request ->
                    path = request.url.encodedPath
                    respond(
                        content =
                            """
                            {
                              "object":"catalog.detail",
                              "id":"variant-1",
                              "title":"嫁金钗",
                              "episodes":[{"id":"episode-1","number":1,"token":"token-1"}]
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                    )
                }
            val repository = DongpianCatalogRepositoryImpl(apiClient(engine))

            val detail = repository.loadDetail("variant-1")

            assertEquals("/v1/catalog/variant-1", path)
            assertEquals("token-1", detail.episodes.single().token)
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
