package com.qiubo.optimaltv.data.source

import android.content.Context
import com.qiubo.optimaltv.auth.DongpianAuthException
import com.qiubo.optimaltv.data.model.OfficialPlaybackBinding
import com.qiubo.optimaltv.data.model.OfficialPlaybackLine
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Executes the production resolver; only its signed HTTP boundary is replaced. */
@RunWith(RobolectricTestRunner::class)
class DongpianPlaybackRefreshTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun expiredTicketRefreshesTheOriginalSelectionAndRetriesOnlyWithItsNewTicket() = runBlocking {
        val calls = mutableListOf<Request>()
        val binding = binding()
        val source = source(calls) {
            when (calls.size) {
                1 -> throw expired()
                2 -> refreshed(line(ticket = "new-ticket"))
                3 -> resolved()
                else -> error("Unexpected extra HTTP request")
            }
        }

        val result = source.resolveLine(binding)

        assertEquals(listOf("POST", "GET", "POST"), calls.map { it.method })
        assertEquals(DongpianPlaybackProtocol.RESOLVE_LINE_PATH, calls[0].path)
        assertEquals("old-ticket", JSONObject(calls[0].body!!).getString("ticket"))
        assertEquals(1, JSONObject(calls[0].body!!).length())
        assertEquals(DongpianPlaybackProtocol.refreshResolvePath(binding), calls[1].path)
        assertEquals(null, calls[1].body)
        assertEquals("new-ticket", JSONObject(calls[2].body!!).getString("ticket"))
        assertEquals(binding.line.playbackSourceId, result.playbackSourceId)
        assertEquals("https://media.example.invalid/parsed.m3u8", result.url)
        assertFalse(DongpianPlaybackProtocol.needsResolution(result))
        assertEquals("new-ticket", result.resolveTicket)

        // A fresh result is cached: selecting the same unresolved row must not spend a ticket again.
        assertEquals(result, source.resolveLine(binding))
        assertEquals(3, calls.size)
    }

    @Test
    fun aSecondExpiredResponseStopsWithoutASecondRefresh() = runBlocking {
        val calls = mutableListOf<Request>()
        val source = source(calls) {
            if (calls.size == 2) refreshed(line(ticket = "new-ticket")) else throw expired()
        }

        val failure = failure { source.resolveLine(binding()) }

        assertEquals("playback_resolve_ticket_refresh_exhausted", failure.wireCode)
        assertEquals(listOf("POST", "GET", "POST"), calls.map { it.method })
    }

    @Test
    fun unchangedOrMissingTicketsStopBeforeTheRetry() = runBlocking {
        listOf("old-ticket", "").forEach { refreshedTicket ->
            val calls = mutableListOf<Request>()
            val source = source(calls) {
                if (calls.size == 1) throw expired()
                refreshed(line(ticket = refreshedTicket))
            }

            val failure = failure { source.resolveLine(binding()) }

            assertEquals("playback_resolve_ticket_refresh_exhausted", failure.wireCode)
            assertEquals(listOf("POST", "GET"), calls.map { it.method })
        }
    }

    @Test
    fun anotherPlaybackSourceCannotReplaceTheExpiredSelection() = runBlocking {
        val calls = mutableListOf<Request>()
        val source = source(calls) {
            if (calls.size == 1) throw expired()
            refreshed(line(ticket = "new-ticket").put("playback_source_id", "another-source"))
        }

        val failure = failure { source.resolveLine(binding()) }

        assertEquals("playback_resolve_ticket_refresh_exhausted", failure.wireCode)
        assertEquals(2, calls.size)
    }

    @Test
    fun compactIdAliasIsNormalizedBeforeRefreshingTheSamePlaybackSource() = runBlocking {
        val calls = mutableListOf<Request>()
        val original = line().apply { remove("playback_source_id") }
        val compactBinding = binding().copy(
            line = requireNotNull(DongpianPlaybackProtocol.parseLine(original, 0)),
        )
        val source = source(calls) {
            when (calls.size) {
                1 -> throw expired()
                2 -> refreshed(line(ticket = "new-ticket").apply { remove("playback_source_id") })
                3 -> resolved()
                else -> error("Unexpected request")
            }
        }

        val result = source.resolveLine(compactBinding)

        assertEquals("record-one", result.playbackSourceId)
        assertTrue(calls[1].path.contains("line=record-one"))
        assertEquals(3, calls.size)
    }

    @Test
    fun missingIdentityCannotFallBackToAnInternalIdOrProviderMatch() = runBlocking {
        val calls = mutableListOf<Request>()
        val original = binding().let { it.copy(line = it.line.copy(playbackSourceId = "")) }
        val source = source(calls) {
            if (calls.size == 1) throw expired()
            refreshed(line(ticket = "new-ticket"))
        }

        val rejected = failure { source.resolveLine(original) }

        assertEquals("playback_resolve_ticket_refresh_exhausted", rejected.wireCode)
        assertEquals(listOf("POST", "GET"), calls.map { it.method })
    }

    @Test
    fun nonExpiryFailuresArePropagatedWithoutRefreshingTheTicket() = runBlocking {
        listOf(
            DongpianAuthException("Unavailable", statusCode = 503, wireCode = "playback_resolve_ticket_expired"),
            DongpianAuthException("Unavailable", statusCode = 404, wireCode = "playback_line_unavailable"),
            DongpianAuthException("Not allowed", statusCode = 403, wireCode = "playback_not_allowed"),
            DongpianAuthException("Other ticket error", statusCode = 404, wireCode = "playback_line_ticket_expired"),
            DongpianAuthException(
                "Nested code must not trigger a refresh",
                statusCode = 404,
                wireCode = "playback_resolve_ticket_expired",
                topLevelWireCode = "",
            ),
        ).forEach { rejected ->
            val calls = mutableListOf<Request>()
            val source = source(calls) { throw rejected }

            assertSame(rejected, failure { source.resolveLine(binding()) })
            assertEquals(listOf("POST"), calls.map { it.method })
        }
    }

    @Test
    fun directAndFreshParseRowsBypassTheHttpResolver() = runBlocking {
        val calls = mutableListOf<Request>()
        val source = source(calls) { error("Playable row must not request a ticket") }
        val direct = binding().line.copy(
            resolveMode = "direct",
            resolveTicket = "",
            url = "https://media.example.invalid/direct.m3u8",
        )
        val parse = binding().line.copy(
            url = "https://media.example.invalid/fresh.m3u8",
            resolved = true,
            expiresAt = "2999-01-01T00:00:00Z",
        )

        assertEquals(direct, source.resolveLine(binding().copy(line = direct), forceRefresh = true))
        assertEquals(parse, source.resolveLine(binding().copy(line = parse)))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun imagePayloadIsRejectedAsIncompleteMedia() = runBlocking {
        val calls = mutableListOf<Request>()
        val source = source(calls) {
            resolved(
                url =
                    "https://p3-volc-community-sign.byteimg.com/" +
                        "poster.jpg~tplv-image.image?mime_type=video_mp4",
                mimeType = "image/jpeg",
            )
        }

        val rejected = failure { source.resolveLine(binding(), forceRefresh = true) }

        assertEquals("playback_line_resolve_incomplete", rejected.wireCode)
        assertEquals(1, calls.size)
    }

    private data class Request(val path: String, val method: String, val body: String?)

    private fun source(
        calls: MutableList<Request>,
        reply: (Request) -> JSONObject,
    ): DongpianOfficialVodSource = DongpianOfficialVodSource(context) { _, path, method, body ->
        val request = Request(path, method, body)
        calls += request
        reply(request)
    }

    private fun line(ticket: String = "old-ticket"): JSONObject =
        JSONObject()
            .put("id", "record-one")
            .put("playback_source_id", "source-one")
            .put("provider_id", "provider-one")
            .put("provider_name", "Provider One")
            .put("play_from", "web")
            .put("source_vod_id", "vod-one")
            .put("resolve_mode", "parse")
            .put("resolve_required", true)
            .put("resolved", false)
            .put("resolve_ticket", ticket)

    private fun binding(): OfficialPlaybackBinding = OfficialPlaybackBinding(
        episodeToken = "episode/token",
        sequenceIndex = 2,
        episodeCount = 12,
        contentKind = "series",
        line = requireNotNull(DongpianPlaybackProtocol.parseLine(line(), 0)),
    )

    private fun refreshed(line: JSONObject): JSONObject = JSONObject()
        .put("line_options", JSONArray().put(line))
        .put("current_episode", JSONObject().put("sequence_index", 2))
        .put("episode_count", 12)
        .put("content_kind", "series")

    private fun resolved(
        url: String = "https://media.example.invalid/parsed.m3u8",
        mimeType: String = "application/vnd.apple.mpegurl",
    ): JSONObject = JSONObject().put(
        "line",
        JSONObject()
            .put("url", url)
            .put("mime_type", mimeType)
            .put("resolved", true)
            .put("expires_at", "2999-01-01T00:00:00Z"),
    )

    private fun expired(): DongpianAuthException = DongpianAuthException(
        "Expired",
        statusCode = 404,
        wireCode = "playback_resolve_ticket_expired",
    )

    private suspend fun failure(block: suspend () -> OfficialPlaybackLine): DongpianAuthException {
        try {
            block()
        } catch (failure: DongpianAuthException) {
            return failure
        }
        throw AssertionError("Resolver unexpectedly accepted the rejected line")
    }
}
