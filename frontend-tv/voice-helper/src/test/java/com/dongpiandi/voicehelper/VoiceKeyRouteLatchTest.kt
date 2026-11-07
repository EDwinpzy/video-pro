package com.dongpiandi.voicehelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceKeyRouteLatchTest {
    @Test
    fun firstRouteOwnsTheWholeKeySequence() {
        val latch = VoiceKeyRouteLatch()

        assertEquals(VoiceRoute.DICTATION, latch.claim(VoiceRoute.DICTATION))
        assertEquals(VoiceRoute.DICTATION, latch.claim(VoiceRoute.ASSISTANT))
    }

    @Test
    fun clearReleasesTheNextSequence() {
        val latch = VoiceKeyRouteLatch()
        latch.claim(VoiceRoute.ASSISTANT)

        latch.clear()

        assertNull(latch.owner)
        assertEquals(VoiceRoute.DICTATION, latch.claim(VoiceRoute.DICTATION))
    }
}
