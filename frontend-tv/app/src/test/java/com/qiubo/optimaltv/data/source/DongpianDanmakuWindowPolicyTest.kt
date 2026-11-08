package com.qiubo.optimaltv.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DongpianDanmakuWindowPolicyTest {
    private val segments =
        listOf(
            segment(0L, 60_000L),
            segment(60_000L, 120_000L),
            segment(480_000L, 540_000L),
            segment(540_000L, 600_000L),
        )

    @Test
    fun detectsTheDenseWindowAtFiveHundredSeconds() {
        assertEquals(2, DanmakuWindowPolicy.segmentIndex(segments, 500_000L))
    }

    @Test
    fun usesAnExclusiveEndBoundary() {
        assertEquals(2, DanmakuWindowPolicy.segmentIndex(segments, 539_999L))
        assertEquals(3, DanmakuWindowPolicy.segmentIndex(segments, 540_000L))
        assertEquals(null, DanmakuWindowPolicy.segmentIndex(segments, 600_000L))
    }

    @Test
    fun rejectsAWindowAfterPlaybackMovesPastIt() {
        val window =
            DanmakuWindow(
                items = emptyList(),
                available = true,
                fromMs = 480_000L,
                toMs = 540_000L,
            )

        assertTrue(DanmakuWindowPolicy.covers(window, 500_000L))
        assertFalse(DanmakuWindowPolicy.covers(window, 540_001L))
    }

    @Test
    fun keepsRecentCommentsWhenTheNextSegmentStarts() {
        val merged =
            DanmakuWindowPolicy.mergeWindowItems(
                previous =
                    listOf(
                        item(269_999L, "too-old"),
                        item(290_000L, "still-on-screen"),
                        item(299_000L, "boundary"),
                        item(400_000L, "future-window"),
                    ),
                incoming =
                    listOf(
                        item(300_000L, "new-segment"),
                        item(300_000L, "new-segment"),
                    ),
                incomingFromMs = 300_000L,
            )

        assertEquals(
            listOf("still-on-screen", "boundary", "new-segment"),
            merged.map(DanmakuItem::content),
        )
    }

    @Test
    fun keepsThePreviousWindowWhenTheNextLoadIsEmpty() {
        val previous = listOf(item(299_000L, "boundary"))

        assertEquals(
            previous,
            DanmakuWindowPolicy.mergeWindowItems(
                previous = previous,
                incoming = emptyList(),
                incomingFromMs = 300_000L,
            ),
        )
    }

    private fun segment(fromMs: Long, toMs: Long) =
        DanmakuSegmentSpec(
            fromMs = fromMs,
            toMs = toMs,
            count = 1,
            sha256 = "0".repeat(64),
            url = "bundle.json.gz",
        )

    private fun item(
        timeMs: Long,
        content: String,
    ) = DanmakuItem(
        timeMs = timeMs,
        sourceMode = 1,
        color = 0xFFFFFF,
        fontSize = 24,
        content = content,
        sourceId = content,
    )
}
