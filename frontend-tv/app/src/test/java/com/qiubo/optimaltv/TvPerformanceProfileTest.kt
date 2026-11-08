package com.qiubo.optimaltv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPerformanceProfileTest {
    @Test
    fun constrainedPosterUsesItsDisplayPixelSize() {
        val size = TvImageRequestSpec.posterSize(displayWidthPx = 1920, decodeScale = 1f)

        assertEquals(232, size.widthPx)
        assertEquals(352, size.heightPx)
    }

    @Test
    fun regularTvKeepsHighDensityPosterDecode() {
        val size = TvImageRequestSpec.posterSize(displayWidthPx = 1920, decodeScale = 2f)

        assertEquals(464, size.widthPx)
        assertEquals(704, size.heightPx)
    }

    @Test
    fun heroDecodesOnlyTheVisibleBand() {
        val size = TvImageRequestSpec.heroSize(displayWidthPx = 1920)

        assertEquals(1920, size.widthPx)
        assertEquals(500, size.heightPx)
    }

    @Test
    fun constrainedTierUsesLightweightImageWarmupPolicy() {
        assertEquals(
            8,
            TvPerformancePolicy.coverPreloadLimit(TvPerformanceTier.CONSTRAINED),
        )
        assertEquals(
            2,
            TvPerformancePolicy.coverPreloadConcurrency(TvPerformanceTier.CONSTRAINED),
        )
        assertEquals(
            1f,
            TvPerformancePolicy.posterDecodeScale(TvPerformanceTier.CONSTRAINED),
            0f,
        )
    }

    @Test
    fun balancedTierKeepsFullImageWarmupPolicy() {
        assertEquals(
            64,
            TvPerformancePolicy.coverPreloadLimit(TvPerformanceTier.BALANCED),
        )
        assertEquals(
            8,
            TvPerformancePolicy.coverPreloadConcurrency(TvPerformanceTier.BALANCED),
        )
        assertEquals(
            2f,
            TvPerformancePolicy.posterDecodeScale(TvPerformanceTier.BALANCED),
            0f,
        )
    }

    @Test
    fun visibleHistoryPrecedesRemainingHeroes() {
        val ordered =
            orderedTvImagePreloads(
                heroUrls =
                    listOf(
                        "https://example.com/hero-current.jpg",
                        "https://example.com/hero-next.jpg",
                    ),
                cardUrls =
                    listOf(
                        "https://example.com/history-first.jpg",
                        "https://example.com/history-second.jpg",
                    ),
                limit = 4,
            )

        assertEquals(
            listOf(
                "https://example.com/hero-current.jpg",
                "https://example.com/history-first.jpg",
                "https://example.com/history-second.jpg",
                "https://example.com/hero-next.jpg",
            ),
            ordered.map { it.url },
        )
        assertEquals(listOf(true, false, false, true), ordered.map { it.hero })
    }

    @Test
    fun duplicateCardUrlKeepsFirstPositionAndPromotesHero() {
        val duplicate = "https://example.com/shared.jpg"
        val ordered =
            orderedTvImagePreloads(
                heroUrls = listOf("https://example.com/current.jpg", duplicate),
                cardUrls = listOf(duplicate, "https://example.com/card.jpg"),
                limit = 8,
            )

        assertEquals(
            listOf(
                "https://example.com/current.jpg",
                duplicate,
                "https://example.com/card.jpg",
            ),
            ordered.map { it.url },
        )
        assertEquals(listOf(true, true, false), ordered.map { it.hero })
    }

    @Test
    fun preloadPlanDropsInvalidUrlsAndHonorsLimit() {
        val ordered =
            orderedTvImagePreloads(
                heroUrls =
                    listOf(
                        "not-a-url",
                        "https://example.com/current.jpg",
                    ),
                cardUrls =
                    listOf(
                        "https://example.com/history.jpg",
                        "https://example.com/later.jpg",
                    ),
                limit = 2,
            )

        assertEquals(
            listOf(
                "https://example.com/current.jpg",
                "https://example.com/history.jpg",
            ),
            ordered.map { it.url },
        )
    }

    @Test
    fun smallHeapClassUsesConstrainedMotion() {
        assertEquals(TvPerformanceTier.CONSTRAINED, TvPerformancePolicy.tier(160, 4, false))
        assertEquals(false, TvPerformancePolicy.focusScaleMotionEnabled(TvPerformanceTier.CONSTRAINED))
        assertEquals(false, TvPerformancePolicy.navigationMotionEnabled(TvPerformanceTier.CONSTRAINED))
    }

    @Test
    fun lowRamFlagUsesConstrainedMotion() {
        assertEquals(TvPerformanceTier.CONSTRAINED, TvPerformancePolicy.tier(256, 8, true))
    }

    @Test
    fun twoCoreDeviceUsesConstrainedMotion() {
        assertEquals(TvPerformanceTier.CONSTRAINED, TvPerformancePolicy.tier(256, 2, false))
    }

    @Test
    fun regularTvKeepsFullMotion() {
        assertEquals(TvPerformanceTier.BALANCED, TvPerformancePolicy.tier(256, 4, false))
        assertEquals(true, TvPerformancePolicy.focusScaleMotionEnabled(TvPerformanceTier.BALANCED))
        assertEquals(true, TvPerformancePolicy.navigationMotionEnabled(TvPerformanceTier.BALANCED))
    }

    @Test
    fun suppressesComposeSemanticsForOwnVoiceGatewayOnConstrainedDevice() {
        assertTrue(
            TvPerformancePolicy.suppressFocusSemantics(
                constrained = true,
                enabledServices =
                    "com.dongpiandi.voicehelper/com.dongpiandi.voicehelper" +
                        ".VoiceGatewayService",
            ),
        )
    }

    @Test
    fun keepsComposeSemanticsForScreenReaderOrBalancedDevice() {
        assertFalse(
            TvPerformancePolicy.suppressFocusSemantics(
                constrained = true,
                enabledServices =
                    "com.dongpiandi.voicehelper/com.dongpiandi.voicehelper" +
                        ".VoiceGatewayService:" +
                        "com.google.android.marvin.talkback/.TalkBackService",
            ),
        )
        assertFalse(
            TvPerformancePolicy.suppressFocusSemantics(
                constrained = false,
                enabledServices =
                    "com.dongpiandi.voicehelper/com.dongpiandi.voicehelper" +
                        ".VoiceGatewayService",
            ),
        )
    }
}
