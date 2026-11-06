package com.qiubo.optimaltv.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceSearchContractTest {
    @Test
    fun normalizesSearchQuery() {
        assertEquals("流浪地球 2", VoiceSearchContract.normalizeQuery("  流浪地球\n2  "))
    }

    @Test
    fun rejectsBlankQuery() {
        assertNull(VoiceSearchContract.normalizeQuery(""))
        assertNull(VoiceSearchContract.normalizeQuery(" \n\t "))
        assertNull(VoiceSearchContract.normalizeQuery(null))
    }

    @Test
    fun capsQueryLength() {
        val query = VoiceSearchContract.normalizeQuery("影".repeat(120))
        assertEquals(VoiceSearchContract.MAX_QUERY_LENGTH, query?.length)
    }
}
