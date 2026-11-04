package com.qiubo.optimaltv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemotePosterCandidateTest {
    @Test
    fun fallbackChainAdvancesInCandidateOrderAndStopsAtLastAttempt() {
        val visited = mutableListOf(0)
        var current = 0
        repeat(2) {
            current = nextPosterCandidateIndex(current, candidateCount = 3)
            visited += current
        }
        current = nextPosterCandidateIndex(current, candidateCount = 3)

        assertEquals(listOf(0, 1, 2), visited)
        assertEquals(2, current)
    }

    @Test
    fun emptyCandidateChainNeverAdvancesPastFirstAttempt() {
        assertEquals(0, nextPosterCandidateIndex(currentIndex = 0, candidateCount = 0))
        assertEquals(0, nextPosterCandidateIndex(currentIndex = 7, candidateCount = 0))
    }

    @Test
    fun posterLogLabelKeepsOnlyHostAndFile() {
        val label =
            posterLogLabel(
                "https://user:password@img.example/private/poster.webp?token=secret",
            )

        assertEquals("img.example/poster.webp", label)
        assertFalse(label.contains("password"))
        assertFalse(label.contains("token"))
        assertFalse(label.contains("secret"))
    }
}
