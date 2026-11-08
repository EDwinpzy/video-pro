package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.data.model.LineInfo
import com.qiubo.optimaltv.data.model.OfficialPlaybackBinding
import com.qiubo.optimaltv.data.source.DongpianPlaybackProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VodHttpRecoveryTest {
    @Test fun deniedLineRefreshesOnceThenStopsWithoutChangingSource() {
        val recovery = VodHttpRecovery()
        val selected = line("a", "one")
        assertEquals(VodHttpRecovery.Action.Refresh, recovery.recover(403, selected))
        assertEquals(VodHttpRecovery.Action.Stop, recovery.recover(403, selected))
    }

    @Test fun refreshBudgetUsesStableLineIdentityAndResetsOnlyExplicitly() {
        val recovery = VodHttpRecovery()
        val a = line("a", "one")
        assertEquals(VodHttpRecovery.Action.Refresh, recovery.recover(401, a))
        assertEquals(VodHttpRecovery.Action.Stop, recovery.recover(401, a.copy(label = "renamed")))
        recovery.reset()
        assertEquals(VodHttpRecovery.Action.Refresh, recovery.recover(401, a))
    }

    @Test fun transientHttpErrorsHaveExtraRefreshButStillEndOnTheSelectedSource() {
        val recovery = VodHttpRecovery()
        val selected = LineInfo("legacy", "legacy", "https://example.invalid/video.m3u8")
        assertNull(recovery.recover(200, selected))
        assertEquals(VodHttpRecovery.Action.Refresh, recovery.recover(503, selected))
        assertEquals(VodHttpRecovery.Action.Refresh, recovery.recover(503, selected))
        assertEquals(VodHttpRecovery.Action.Stop, recovery.recover(503, selected))
    }

    @Test fun missingSelectedSourceStopsImmediately() {
        val recovery = VodHttpRecovery()
        assertEquals(VodHttpRecovery.Action.Stop, recovery.recover(403, null))
    }

    private fun line(id: String, provider: String): LineInfo {
        val parsed = DongpianPlaybackProtocol.parseLine(JSONObject()
            .put("playback_source_id", id).put("provider_id", provider)
            .put("resolve_mode", "parse").put("resolve_ticket", "fictional-ticket"), 0)!!
        return LineInfo("dongpian:fictional", id, "", official =
            OfficialPlaybackBinding("fictional-episode", 0, 1, "movie", parsed))
    }
}
