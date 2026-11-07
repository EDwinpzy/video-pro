package com.qiubo.optimaltv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoProWidescreenTimelineTest {

    @Test
    fun `motion finishes in one point five seconds`() {
        assertEquals(1_500L, VideoProWidescreenTimeline.wallMotionDurationMillis)
        assertEquals(3_552L, VideoProWidescreenTimeline.previewLoopDurationMillis)
    }

    @Test
    fun `opening reaches half screen at the midpoint`() {
        val frame = VideoProWidescreenTimeline.frameAt(1f)

        assertEquals(360f, frame.openingHeight, 0.01f)
        assertEquals(0.5f, frame.coverage, 0.0001f)
    }

    @Test
    fun `initial hold is fully black before the curtain expands`() {
        val start = VideoProWidescreenTimeline.frameAt(0f)
        val openingStart = VideoProWidescreenTimeline.frameAt(0.1f)

        assertEquals(0f, start.openingHeight, 0f)
        assertEquals(0f, openingStart.openingHeight, 0f)
        assertEquals(540f, start.iconX, 0f)
        assertEquals(1100f, start.videoX, 0f)
        assertEquals(1200f, start.proX, 0f)
    }

    @Test
    fun `brand movement finishes before the screen reaches full height`() {
        val frame = VideoProWidescreenTimeline.frameAt(1.7f)

        assertEquals(355f, frame.iconX, 0.01f)
        assertEquals(595f, frame.videoX, 0.01f)
        assertEquals(595f, frame.proX, 0.01f)
        assertEquals(true, frame.openingHeight < 720f)
    }

    @Test
    fun `full frame reaches all edges with the original brand geometry`() {
        val frame = VideoProWidescreenTimeline.frameAt(1.9f)

        assertEquals(720f, frame.openingHeight, 0f)
        assertEquals(355f, frame.iconX, 0.01f)
        assertEquals(595f, frame.videoX, 0.01f)
        assertEquals(595f, frame.proX, 0.01f)
    }

    @Test
    fun `frame remains stable after the opener completes`() {
        assertEquals(
            VideoProWidescreenTimeline.frameAt(1.9f),
            VideoProWidescreenTimeline.frameAt(4.5f),
        )
    }

    @Test
    fun `non widescreen viewport fills the curtain without distorting the brand`() {
        val viewport = VideoProWidescreenViewport.forSize(width = 1024f, height = 768f)

        assertEquals(1024f / 1280f, viewport.brandScale, 0.0001f)
        assertEquals(96f, viewport.brandOffsetY, 0.0001f)
        assertEquals(0f, viewport.brandOffsetX, 0.0001f)
        assertEquals(768f / 720f, viewport.curtainScaleY, 0.0001f)
    }
}
