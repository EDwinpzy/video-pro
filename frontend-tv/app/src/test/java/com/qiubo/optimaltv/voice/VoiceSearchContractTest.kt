package com.qiubo.optimaltv.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun acceptsBothActivityAndInAppBroadcastActions() {
        val activity =
            VoiceSearchContract.requestFromValues(
                action = VoiceSearchContract.ACTION_OPEN_SEARCH,
                rawQuery = "三国演义",
                openFirstResult = true,
            )
        val broadcast =
            VoiceSearchContract.requestFromValues(
                action = VoiceSearchContract.ACTION_OPEN_SEARCH_IN_APP,
                rawQuery = "凡人修仙传",
                openFirstResult = true,
            )

        assertEquals("三国演义", activity?.query)
        assertTrue(activity?.openFirstResult == true)
        assertEquals("凡人修仙传", broadcast?.query)
        assertTrue(broadcast?.openFirstResult == true)
    }

    @Test
    fun rejectsUnrelatedActions() {
        assertNull(
            VoiceSearchContract.requestFromValues(
                action = "com.example.UNRELATED",
                rawQuery = "三国演义",
                openFirstResult = true,
            ),
        )
    }
}
