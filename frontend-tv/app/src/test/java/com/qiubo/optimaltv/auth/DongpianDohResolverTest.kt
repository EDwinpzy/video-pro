package com.qiubo.optimaltv.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DongpianDohResolverTest {
    @Test
    fun extractsOnlyValidIpv4AnswersForRequestedHost() {
        val body =
            """
            {
              "Status": 0,
              "Answer": [
                {"name": "ai.dongpian20.com.", "type": 5, "data": "cft.wetvcdn.com."},
                {"name": "cft.wetvcdn.com.", "type": 1, "data": "3.175.211.202"},
                {"name": "cft.wetvcdn.com.", "type": 1, "data": "3.175.211.202"},
                {"name": "cft.wetvcdn.com.", "type": 1, "data": "999.1.1.1"},
                {"name": "other.example.", "type": 1, "data": "1.1.1.1"},
                {"name": "cft.wetvcdn.com.", "type": 28, "data": "2001:db8::1"}
              ]
            }
            """.trimIndent()

        assertEquals(listOf("3.175.211.202"), parseDohIpv4("ai.dongpian20.com", body))
    }

    @Test
    fun returnsNoAddressesForFailedOrMalformedResponse() {
        assertTrue(parseDohIpv4("ai.dongpian20.com", """{"Status":2}""").isEmpty())
        assertTrue(parseDohIpv4("ai.dongpian20.com", "<html>").isEmpty())
    }
}
