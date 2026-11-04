package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.data.prefs.DanmakuDisplayArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuPresentationPolicyTest {
    @Test
    fun halfScreenUsesAtMostHalfTheLaneBudget() {
        val full =
            DanmakuPresentationPolicy.laneCount(
                displayArea = DanmakuDisplayArea.FULL,
                viewportHeightPx = 720,
                topInsetPx = 20f,
                laneHeightPx = 40f,
            )
        val half =
            DanmakuPresentationPolicy.laneCount(
                displayArea = DanmakuDisplayArea.HALF,
                viewportHeightPx = 720,
                topInsetPx = 20f,
                laneHeightPx = 40f,
            )

        assertEquals(6, full)
        assertEquals(3, half)
    }

    @Test
    fun largerTextReducesTracksBeforeTheyCanOverlap() {
        val standard =
            DanmakuPresentationPolicy.laneCount(
                displayArea = DanmakuDisplayArea.FULL,
                viewportHeightPx = 250,
                topInsetPx = 20f,
                laneHeightPx = 34f,
            )
        val large =
            DanmakuPresentationPolicy.laneCount(
                displayArea = DanmakuDisplayArea.FULL,
                viewportHeightPx = 250,
                topInsetPx = 20f,
                laneHeightPx = 40.8f,
            )

        assertEquals(6, standard)
        assertEquals(5, large)
    }

    @Test
    fun quarterScreenStaysInsideItsSmallerLaneBudget() {
        val quarter =
            DanmakuPresentationPolicy.laneCount(
                displayArea = DanmakuDisplayArea.QUARTER,
                viewportHeightPx = 720,
                topInsetPx = 20f,
                laneHeightPx = 40f,
            )
        val half =
            DanmakuPresentationPolicy.laneCount(
                displayArea = DanmakuDisplayArea.HALF,
                viewportHeightPx = 720,
                topInsetPx = 20f,
                laneHeightPx = 40f,
            )

        assertEquals(2, quarter)
        assertTrue(quarter < half)
    }

    @Test
    fun textWidthEstimateAccountsForEveryGlyph() {
        val short = DanmakuPresentationPolicy.estimatedTextWidthPx("短", 24f)
        val long = DanmakuPresentationPolicy.estimatedTextWidthPx("短弹幕内容", 24f)

        assertEquals(24f, short)
        assertTrue(long > short)
    }
}
