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
    @Test fun deniedLineRefreshesThenTriesDifferentProviderAndNeverCyclesBack() {
        val recovery = VodHttpRecovery()
        val lines = listOf(line("a", "one"), line("b", "one"), line("c", "two"), line("d", "three"))
        assertEquals(VodHttpRecovery.Action.Refresh, recovery.recover(403, lines, 0))
        assertEquals(VodHttpRecovery.Action.Fallback(2), recovery.recover(403, lines, 0))
        assertEquals(VodHttpRecovery.Action.Refresh, recovery.recover(403, lines, 2))
        assertEquals(VodHttpRecovery.Action.Fallback(1), recovery.recover(403, lines, 2))
        assertEquals(VodHttpRecovery.Action.Refresh, recovery.recover(403, lines, 1))
        assertEquals(VodHttpRecovery.Action.Stop, recovery.recover(403, lines, 1))
    }

    @Test fun refreshBudgetUsesStableLineIdentityAcrossReorder() {
        val recovery = VodHttpRecovery()
        val a = line("a", "one")
        val b = line("b", "two")
        assertEquals(VodHttpRecovery.Action.Refresh, recovery.recover(401, listOf(a, b), 0))
        assertEquals(VodHttpRecovery.Action.Fallback(0), recovery.recover(401, listOf(b, a), 1))
        recovery.reset()
        assertEquals(VodHttpRecovery.Action.Refresh, recovery.recover(401, listOf(b, a), 1))
    }

    @Test fun transientHttpErrorsHaveExtraRefreshButStillEndAndLegacySourcesStaySelected() {
        val recovery = VodHttpRecovery()
        val lines = listOf(LineInfo("legacy", "legacy", "https://example.invalid/video.m3u8"))
        assertNull(recovery.recover(200, lines, 0))
        assertEquals(VodHttpRecovery.Action.Refresh, recovery.recover(503, lines, 0))
        assertEquals(VodHttpRecovery.Action.Refresh, recovery.recover(503, lines, 0))
        assertEquals(VodHttpRecovery.Action.Stop, recovery.recover(503, lines, 0))
    }

    @Test fun candidatesAlreadyFailedByOtherRecoveryPathsAreExcluded() {
        val recovery = VodHttpRecovery()
        val lines = listOf(line("a", "one"), line("b", "two"), line("c", "three"))
        recovery.recover(403, lines, 0, setOf(0, 1))
        assertEquals(VodHttpRecovery.Action.Fallback(2), recovery.recover(403, lines, 0, setOf(0, 1)))
    }

    private fun line(id: String, provider: String): LineInfo {
        val parsed = DongpianPlaybackProtocol.parseLine(JSONObject()
            .put("playback_source_id", id).put("provider_id", provider)
            .put("resolve_mode", "parse").put("resolve_ticket", "fictional-ticket"), 0)!!
        return LineInfo("dongpian:fictional", id, "", official =
            OfficialPlaybackBinding("fictional-episode", 0, 1, "movie", parsed))
    }
}
