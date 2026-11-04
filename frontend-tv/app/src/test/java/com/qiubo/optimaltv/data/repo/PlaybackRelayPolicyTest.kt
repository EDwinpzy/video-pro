package com.qiubo.optimaltv.data.repo

import com.qiubo.optimaltv.data.model.LineInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRelayPolicyTest {
    @Test
    fun officialResolvedHttpsLineUsesRelay() {
        val line = LineInfo(
            vodId = "dongpian:item|official:0",
            label = "Official",
            url = "https://media.example.invalid/index.m3u8",
        )

        assertTrue(PlaybackRelayPolicy.shouldRelayDirectLine(line, preferRelay = true))
    }

    @Test
    fun nonOfficialDirectLineIsNotRewritten() {
        val line = LineInfo(
            vodId = "live:match",
            label = "Live",
            url = "https://media.example.invalid/live.m3u8",
        )

        assertFalse(PlaybackRelayPolicy.shouldRelayDirectLine(line, preferRelay = true))
    }

    @Test
    fun plaintextUrlIsNotSentThroughRelay() {
        val line = LineInfo(
            vodId = "dongpian:item|official:0",
            label = "Official",
            url = "http://media.example.invalid/index.m3u8",
        )

        assertFalse(PlaybackRelayPolicy.shouldRelayDirectLine(line, preferRelay = true))
    }

    @Test
    fun relayOptOutIsRespected() {
        val line = LineInfo(
            vodId = "dongpian:item|official:0",
            label = "Official",
            url = "https://media.example.invalid/index.m3u8",
        )

        assertFalse(PlaybackRelayPolicy.shouldRelayDirectLine(line, preferRelay = false))
    }
}
