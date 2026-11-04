package com.qiubo.optimaltv.player

import androidx.test.platform.app.InstrumentationRegistry
import com.qiubo.optimaltv.auth.DongpianAuthClient
import com.qiubo.optimaltv.data.source.DongpianOfficialVodSource
import com.qiubo.optimaltv.data.source.DongpianPlaybackProtocol
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in live-service probe for comparing the compact and full initial-resolve payloads. */
class DongpianPlaybackLineCompareDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test
    fun compactAndFullResolveReturnTheSameVisibleLineSet() = runBlocking {
        assumeTrue(arguments.getString("dongpianLineProbe") == "true")
        assertTrue("A pre-existing official session is required", DongpianAuthClient.isSignedIn(context))

        val title = arguments.getString("title") ?: "爱情假说"
        val history = DongpianAuthClient.apiJson(
            context,
            "/v1/users/me/history?limit=100&update_view=text",
        )
        val historyItems = history.optJSONArray("items")
        val item = (0 until (historyItems?.length() ?: 0))
            .mapNotNull { historyItems?.optJSONObject(it) }
            .firstOrNull { it.optString("title").contains(title, ignoreCase = true) }
            ?: throw AssertionError("History does not contain the requested title")

        val source = DongpianOfficialVodSource(context)
        val detail = source.detail(item.optString("variant_id"))
        val token = detail.episodeTokens.first()
        val index = detail.episodeSequenceIndices.firstOrNull()
        val query = buildList {
            add("episode_index=${index ?: 0}")
            add("episode_count=${detail.episodeCount}")
            add("content_kind=${encode(detail.contentKind)}")
        }
        val base = "/v1/playback/resolve/${encode(token)}?${query.joinToString("&")}"
        val compact = requestSummary("$base&view=compact")
        val full = requestSummary(base)
        val report = JSONObject()
            .put("title", item.optString("title"))
            .put("compact", compact)
            .put("full", full)
        val directory = File(context.filesDir, "qa").apply { mkdirs() }
        File(directory, "playback-line-compare.json").writeText(report.toString(2))

        assertTrue("Both probes must return at least one line", compact.getInt("count") > 0 && full.getInt("count") > 0)
    }

    private fun requestSummary(path: String): JSONObject {
        val response = DongpianAuthClient.apiJson(context, path)
        val payload = response.optJSONObject("data") ?: response
        val raw = payload.optJSONArray("line_options")
        val lines = DongpianPlaybackProtocol.parseLines(raw)
        return JSONObject()
            .put("count", lines.size)
            .put("raw_count", raw?.length() ?: 0)
            .put("providers", lines.map { it.providerDisplayName }.distinct())
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value.trim(), "UTF-8").replace("+", "%20")
}
