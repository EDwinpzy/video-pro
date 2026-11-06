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
        assertTrue(VoiceSearchBus.submit("流浪地球"))
        assertTrue(VoiceSearchBus.hasPendingQuery())
        assertEquals("流浪地球", VoiceSearchBus.takeNavigationQuery())
        assertNull(VoiceSearchBus.takeNavigationQuery())
        assertTrue(VoiceSearchBus.hasPendingQuery())
        assertEquals("流浪地球", VoiceSearchBus.takeQuery())
        assertFalse(VoiceSearchBus.hasPendingQuery())
        assertNull(VoiceSearchBus.takeQuery())
    }

    @Test
    fun rejectsBlankQueries() {
        assertFalse(VoiceSearchBus.submit("  "))
        assertFalse(VoiceSearchBus.hasPendingQuery())
    }
}
