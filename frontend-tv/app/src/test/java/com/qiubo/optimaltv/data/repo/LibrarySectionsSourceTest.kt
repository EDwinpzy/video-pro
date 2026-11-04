package com.qiubo.optimaltv.data.repo

import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibrarySectionsSourceTest {
    @Test
    fun channelCardsReachLibraryWithoutWaitingForDouban() {
        val paths = mutableListOf<String>()
        val client = client { url ->
            paths += url.encodedPath
            check(url.encodedPath == "/hhkan/channel-sections/1")
            """{"sections":[{"title":"最近热门","items":[{"id":123,"title":"Example film","cover":"https://vres.example.com/poster.jpg"}]}]}"""
        }

        val sections = loadLegacyLibrarySections("https://example.com", client, "movie", 1, false)

        val item = sections.first { it.name == "最近热门" }.items.single()
        assertEquals(listOf("/hhkan/channel-sections/1"), paths)
        assertEquals("hhkan:123", item.id)
        assertEquals("hhkan:123", item.detailRef)
        assertTrue(item.posterUrl.startsWith("https://example.com/hhkan/proxy?u="))
    }

    @Test
    fun emptyChannelFallsBackToPopulatedDoubanHome() {
        val requests = mutableListOf<Pair<String, String?>>()
        val client = client { url ->
            requests += url.encodedPath to url.queryParameter("category")
            if (url.encodedPath.startsWith("/hhkan/")) """{"sections":[]}"""
            else """{"sections":[{"title":"最近热门","items":[{"id":"456","title":"Fallback film"}]}]}"""
        }

        val sections = loadLegacyLibrarySections("https://example.com", client, "movie", 1, false)

        assertEquals(listOf("/hhkan/channel-sections/1", "/vod/home"), requests.map { it.first })
        assertEquals(listOf(null, "movie"), requests.map { it.second })
        assertEquals("douban:456", sections.single().items.single().id)
    }

    @Test
    fun channelTimeoutDoesNotDiscardWorkingFallback() {
        val client = client { url ->
            if (url.encodedPath.startsWith("/hhkan/")) throw SocketTimeoutException("cold channel")
            """{"sections":[{"title":"最近热门","items":[{"id":"789","title":"Recovered film"}]}]}"""
        }

        val sections = loadLegacyLibrarySections("https://example.com", client, "movie", 1, true)

        assertEquals("douban:789", sections.single().items.single().id)
    }

    @Test(expected = CancellationException::class)
    fun channelCancellationIsNotRetriedAsFallback() {
        val client = client { url ->
            if (url.encodedPath.startsWith("/hhkan/")) throw CancellationException("cancelled")
            """{"sections":[{"title":"最近热门","items":[{"id":"999","title":"Must not load"}]}]}"""
        }

        loadLegacyLibrarySections("https://example.com", client, "movie", 1, false)
    }

    private fun client(body: (HttpUrl) -> String): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body(chain.request().url).toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
}
