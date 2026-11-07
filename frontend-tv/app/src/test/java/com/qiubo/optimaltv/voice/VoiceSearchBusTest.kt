package com.qiubo.optimaltv.voice

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceSearchBusTest {
    @Before
    fun setUp() {
        VoiceSearchBus.resetForTest()
    }

    @After
    fun tearDown() {
        VoiceSearchBus.resetForTest()
    }

    @Test
    fun querySurvivesNavigationUntilTheSearchScreenConsumesIt() {
        val request = VoiceSearchRequest("流浪地球", openFirstResult = true)
        assertTrue(VoiceSearchBus.submit(request))
        assertTrue(VoiceSearchBus.hasPendingQuery())
        assertEquals(request, VoiceSearchBus.takeNavigationRequest())
        assertNull(VoiceSearchBus.takeNavigationRequest())
        assertTrue(VoiceSearchBus.hasPendingQuery())
        assertEquals(request, VoiceSearchBus.takeRequest())
        assertFalse(VoiceSearchBus.hasPendingQuery())
        assertNull(VoiceSearchBus.takeRequest())
    }

    @Test
    fun rejectsBlankQueries() {
        assertFalse(VoiceSearchBus.submit(VoiceSearchRequest("  ", openFirstResult = true)))
        assertFalse(VoiceSearchBus.hasPendingQuery())
    }
}
