package com.qiubo.optimaltv.data.repo

import com.qiubo.optimaltv.data.model.LineInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRelayPolicyTest {
    @Test
    fun officialResolvedUrlUsesRelayWhenEnabled() {
        assertTrue(
            PlaybackRelayPolicy.shouldRelayOfficialUrl(
                "https://media.example.invalid/index.m3u8",
                preferRelay = true,
            ),
        )
    }

    @Test
    fun officialResolvedUrlHonoursRelayOptOut() {
        assertFalse(
            PlaybackRelayPolicy.shouldRelayOfficialUrl(
                "https://media.example.invalid/index.m3u8",
                preferRelay = false,
            ),
        )
    }

    @Test
    fun plaintextOfficialUrlIsNotSentThroughRelay() {
        assertFalse(
            PlaybackRelayPolicy.shouldRelayOfficialUrl(
                "http://media.example.invalid/index.m3u8",
                preferRelay = true,
            ),
        )
    }

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

    @Test
    fun officialLineWithoutPlayRefGetsAStableRelayFailureIdentity() {
        val firstEpisode = LineInfo(
            vodId = "dongpian:item|official:0",
            label = "Official",
            url = "https://media.example.invalid/episode-1.m3u8",
        )
        val secondEpisode = firstEpisode.copy(
            url = "https://media.example.invalid/episode-2.m3u8",
        )
        val identity =
            PlaybackRelayPolicy.relayFailureIdentity(firstEpisode)
                ?: error("official line must have a relay identity")

        assertEquals(identity, PlaybackRelayPolicy.relayFailureIdentity(secondEpisode))
        assertFalse(
            PlaybackRelayPolicy.shouldPreferRelay(firstEpisode, setOf(identity)),
        )
        assertTrue(PlaybackRelayPolicy.shouldPreferRelay(secondEpisode, emptySet()))
    }

    @Test
    fun playRefRelayFailureIdentityIgnoresEpisodeSuffix() {
        val firstEpisode = LineInfo(
            vodId = "douban:123",
            label = "Line A",
            url = "",
            playRef = "detail|line-a|episode-1",
        )
        val secondEpisode = firstEpisode.copy(playRef = "detail|line-a|episode-2")
        val otherLine = firstEpisode.copy(label = "Line B", playRef = "detail|line-b|episode-1")

        assertEquals(
            PlaybackRelayPolicy.relayFailureIdentity(firstEpisode),
            PlaybackRelayPolicy.relayFailureIdentity(secondEpisode),
        )
        assertFalse(
            PlaybackRelayPolicy.relayFailureIdentity(firstEpisode) ==
                PlaybackRelayPolicy.relayFailureIdentity(otherLine),
        )
    }
}
