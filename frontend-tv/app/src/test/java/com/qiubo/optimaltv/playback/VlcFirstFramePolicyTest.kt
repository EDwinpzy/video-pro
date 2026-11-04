package com.qiubo.optimaltv.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcFirstFramePolicyTest {
    @Test
    fun `vout alone is not a displayed first frame`() {
        assertFalse(VlcFirstFramePolicy.isDisplayed(voutEstablished = true, displayedPictures = 0))
        assertFalse(VlcFirstFramePolicy.isDisplayed(voutEstablished = false, displayedPictures = 1))
    }

    @Test
    fun `displayed picture after vout confirms the first frame`() {
        assertTrue(VlcFirstFramePolicy.isDisplayed(voutEstablished = true, displayedPictures = 1))
    }

    @Test
    fun `pause before the asynchronous playing callback settles a buffering session`() {
        assertEquals(
            EnginePlayState.READY,
            VlcPauseStatePolicy.stateAfterPause(EnginePlayState.BUFFERING),
        )
        assertEquals(
            EnginePlayState.ERROR,
            VlcPauseStatePolicy.stateAfterPause(EnginePlayState.ERROR),
        )
    }
}
