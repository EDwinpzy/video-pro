package com.qiubo.optimaltv.player

import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.platform.app.InstrumentationRegistry
import com.qiubo.optimaltv.auth.DongpianAuthClient
import com.qiubo.optimaltv.auth.DongpianAuthException
import com.qiubo.optimaltv.data.model.OfficialPlaybackBinding
import com.qiubo.optimaltv.data.source.DongpianOfficialVodSource
import com.qiubo.optimaltv.data.source.DongpianPlaybackProtocol
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real-service smoke test. Captured credentials remain in device-encrypted storage. */
class DongpianTicketExpiryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val preferences by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "qa_official_ticket_expiry",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Test
    fun oldTicketReplayAndAutomaticRefreshUseTheLiveOfficialService() = runBlocking {
        assumeTrue(arguments.getString("dongpianLiveTicketExpiry") == "true")
        assertTrue("A pre-existing official session is required", DongpianAuthClient.isSignedIn(context))
        when (arguments.getString("ticketPhase", "capture")) {
            "capture" -> captureUsableTicket()
            "verify" -> verifyExpiredTicketRefresh()
            "inspect" -> inspectCapturedTicket()
            else -> throw AssertionError("Unknown ticket test phase")
        }
    }

    private suspend fun captureUsableTicket() {
        val source = DongpianOfficialVodSource(context)
        val history = DongpianAuthClient.apiJson(context, "/v1/users/me/history?limit=10&update_view=text")
        val items = history.optJSONArray("items")
        val variant = items?.optJSONObject(0)?.optString("variant_id").orEmpty()
        assertTrue("The account needs an existing playable history item", variant.isNotBlank())
        val detail = source.detail(variant)
        assertTrue("Selected title needs an episode", detail.episodeTokens.isNotEmpty())
        val playback = source.playback(
            detail.episodeTokens.first(),
            detail.episodeSequenceIndices.firstOrNull(),
            detail.episodeCount,
            detail.contentKind,
        )
        val binding = playback.lines.firstNotNullOfOrNull { candidate ->
            candidate.official?.takeIf {
                DongpianPlaybackProtocol.isParseLine(it.line) &&
                    DongpianPlaybackProtocol.ticketOf(it.line).isNotBlank()
            }
        } ?: throw AssertionError("No parse ticket was returned")
        val first = postTicket(DongpianPlaybackProtocol.ticketOf(binding.line))
        assertTrue("First ticket call must return a media URL", playable(first))
        val capture = JSONObject()
            .put("episode_token", binding.episodeToken)
            .put("sequence_index", binding.sequenceIndex)
            .put("episode_count", binding.episodeCount)
            .put("content_kind", binding.contentKind)
            .put("line", JSONObject(binding.line.rawJson))
            .put("captured_at", System.currentTimeMillis())
        assertTrue("Encrypted ticket capture was not persisted", preferences.edit().putString("binding", capture.toString()).commit())
        val expiry = ticketExpiry(DongpianPlaybackProtocol.ticketOf(binding.line))
        report(
            JSONObject()
                .put("phase", "capture")
                .put("first_post", "success")
                .put("has_playback_source_id", binding.line.playbackSourceId.isNotBlank())
                .put("has_internal_id", binding.line.id.isNotBlank())
                .put("line_count", playback.lines.size)
                .put("ticket_expiry_epoch_ms", expiry ?: JSONObject.NULL)
                .put("ticket_ttl_seconds", expiry?.let { (it - System.currentTimeMillis()) / 1000 } ?: JSONObject.NULL)
                .put("captured_at", System.currentTimeMillis()),
        )
    }

    private suspend fun verifyExpiredTicketRefresh() {
        val saved = preferences.getString("binding", null)
            ?: throw AssertionError("Run the capture phase before replaying the original ticket")
        val capture = JSONObject(saved)
        val binding = OfficialPlaybackBinding(
            episodeToken = capture.getString("episode_token"),
            sequenceIndex = capture.optInt("sequence_index", -1).takeIf { it >= 0 },
            episodeCount = capture.optInt("episode_count", 0).takeIf { it > 0 },
            contentKind = capture.getString("content_kind"),
            line = requireNotNull(DongpianPlaybackProtocol.parseLine(capture.getJSONObject("line"), 0)),
        )
        val ticket = DongpianPlaybackProtocol.ticketOf(binding.line)
        val ageSeconds = (System.currentTimeMillis() - capture.getLong("captured_at")) / 1000
        val replayFailure = try {
            val response = postTicket(ticket)
            assertTrue("Replayed ticket returned incomplete media", playable(response))
            null
        } catch (failure: DongpianAuthException) {
            failure
        }
        if (replayFailure == null) {
            report(JSONObject().put("phase", "verify").put("second_old_post", "still_usable").put("age_seconds", ageSeconds))
            assumeTrue("The original live ticket has not expired; retry the verify phase later", false)
            return
        }
        report(
            JSONObject()
                .put("phase", "verify")
                .put("second_old_post", "rejected")
                .put("http_status", replayFailure.statusCode)
                .put("wire_code", replayFailure.wireCode)
                .put("top_level_code", replayFailure.topLevelWireCode)
                .put("age_seconds", ageSeconds),
        )
        assertEquals("Old ticket failure must be HTTP 404", 404, replayFailure.statusCode)
        assertEquals("playback_resolve_ticket_expired", replayFailure.wireCode)
        assertEquals("playback_resolve_ticket_expired", replayFailure.topLevelWireCode)
        var oldPosts = 0
        var newPosts = 0
        var refreshGets = 0
        val source = DongpianOfficialVodSource(context) { requestContext, path, method, body ->
            if (method == "POST" && path == DongpianPlaybackProtocol.RESOLVE_LINE_PATH) {
                if (JSONObject(body!!).getString("ticket") == ticket) oldPosts++ else newPosts++
            }
            if (method == "GET" && path.startsWith("/v1/playback/resolve/")) refreshGets++
            DongpianAuthClient.apiJson(requestContext, path, method, body)
        }
        val result = source.resolveLine(binding, forceRefresh = true)
        assertTrue("Refreshed ticket must resolve to playable media", DongpianPlaybackProtocol.isPlayableMediaUrl(result.url))
        assertEquals("Refresh must keep the original stable line", binding.line.stableId, result.stableId)
        assertEquals(1, oldPosts)
        assertEquals(1, refreshGets)
        assertEquals(1, newPosts)
        preferences.edit().remove("binding").commit()
        report(
            JSONObject()
                .put("phase", "verify")
                .put("second_old_post", "http_404_ticket_expired")
                .put("age_seconds", ageSeconds)
                .put("resolver_old_posts", oldPosts)
                .put("resolver_refresh_gets", refreshGets)
                .put("resolver_new_posts", newPosts)
                .put("same_line", true)
                .put("result", "success"),
        )
    }

    private fun postTicket(ticket: String): JSONObject = DongpianAuthClient.apiJson(
        context,
        DongpianPlaybackProtocol.RESOLVE_LINE_PATH,
        method = "POST",
        body = JSONObject().put("ticket", ticket).toString(),
    )

    private fun playable(response: JSONObject): Boolean {
        val payload = response.optJSONObject("data") ?: response
        return DongpianPlaybackProtocol.isPlayableMediaUrl(payload.optJSONObject("line")?.optString("url").orEmpty())
    }

    private fun ticketExpiry(ticket: String): Long? {
        ticket.split('.', ':').forEach { part ->
            val expiry = try {
                val payload = String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
                JSONObject(payload).optLong("exp", 0L).takeIf { it > 0 }
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: org.json.JSONException) {
                null
            }
            if (expiry != null) return if (expiry < 10_000_000_000L) expiry * 1000 else expiry
        }
        return null
    }

    private fun inspectCapturedTicket() {
        val capture = JSONObject(preferences.getString("binding", null) ?: throw AssertionError("No captured ticket"))
        val line = requireNotNull(DongpianPlaybackProtocol.parseLine(capture.getJSONObject("line"), 0))
        val ticket = DongpianPlaybackProtocol.ticketOf(line)
        val expiry = ticketExpiry(ticket)
        report(
            JSONObject()
                .put("phase", "inspect")
                .put("age_seconds", (System.currentTimeMillis() - capture.getLong("captured_at")) / 1000)
                .put("ticket_characters", ticket.length)
                .put("ticket_segment_count", ticket.split('.').size)
                .put("ticket_expiry_epoch_ms", expiry ?: JSONObject.NULL)
                .put("canonical_source_id_present", line.playbackSourceId.isNotBlank()),
        )
    }

    private fun report(value: JSONObject) {
        val directory = File(context.filesDir, "qa").apply { mkdirs() }
        File(directory, "ticket-expiry-${value.getString("phase")}.json").writeText(value.toString(2))
        Log.i("OTV-TICKET-QA", value.toString())
    }
}
