package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.data.source.DanmakuItem
import org.junit.Assert.assertEquals
import org.junit.Test

class DanmakuLanePolicyTest {
    @Test
    fun sameLaneReusesOnlyAfterThePreviousTailClears() {
        val items =
            listOf(
                item(timeMs = 0L, content = "wide"),
                item(timeMs = 3_199L, content = "edge"),
                item(timeMs = 3_200L, content = "safe"),
            )

        val placements =
            DanmakuLanePolicy.place(
                items = items,
                laneCount = 1,
                viewportWidthPx = 1_920f,
                travelSpeedPxPerMs = 0.2f,
                gapPx = 40f,
            ) { candidate ->
                if (candidate.timeMs == 0L) 600f else 100f
            }

        assertEquals(listOf(0L, 3_200L), placements.map { it.item.timeMs })
        assertEquals(listOf(0, 0), placements.map { it.lane })
        assertEquals(12_600L, placements.first().endMs)
    }

    @Test
    fun denseBurstUsesAvailableLanesThenDropsExcess() {
        val items = (0 until 7).map { index -> item(timeMs = 0L, content = "dense-$index") }

        val placements =
            DanmakuLanePolicy.place(
                items = items,
                laneCount = 3,
                viewportWidthPx = 1_920f,
                travelSpeedPxPerMs = 0.25f,
                gapPx = 40f,
            ) { 400f }

        assertEquals(3, placements.size)
        assertEquals(listOf(0, 1, 2), placements.map { it.lane })
    }

    @Test
    fun activeWindowUsesInclusiveStartAndExclusiveEndAndRebuildsOnSeekBack() {
        val schedule =
            listOf(
                placement(timeMs = 100L, endMs = 200L, lane = 0, content = "first"),
                placement(timeMs = 150L, endMs = 250L, lane = 1, content = "second"),
            )
        val window = DanmakuActiveWindow(schedule)

        assertEquals(emptyList<Int>(), window.activeAt(99L).toList())
        assertEquals(listOf(0), window.activeAt(100L).toList())
        assertEquals(listOf(0, 1), window.activeAt(150L).toList())
        assertEquals(listOf(1), window.activeAt(200L).toList())
        assertEquals(emptyList<Int>(), window.activeAt(250L).toList())
        assertEquals(listOf(0), window.activeAt(100L).toList())
    }

    @Test
    fun activeWindowMatchesFilterOracleAcrossDensePlayback() {
        val schedule =
            (0 until 600).map { index ->
                val startMs = index * 8L
                placement(
                    timeMs = startMs,
                    endMs = startMs + 1_200L,
                    lane = index % 6,
                    content = "dense-$index",
                )
            }
        val window = DanmakuActiveWindow(schedule)

        for (clockMs in 0L..5_000L step 17L) {
            val expected =
                schedule.indices.filter { index ->
                    val placement = schedule[index]
                    clockMs >= placement.item.timeMs && clockMs < placement.endMs
                }
            assertEquals("clockMs=$clockMs", expected, window.activeAt(clockMs).toList())
        }
    }

    private fun placement(
        timeMs: Long,
        endMs: Long,
        lane: Int,
        content: String,
    ) = DanmakuPlacement(
        item = item(timeMs = timeMs, content = content),
        lane = lane,
        endMs = endMs,
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
