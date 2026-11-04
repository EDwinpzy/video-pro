package com.qiubo.optimaltv.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class VlcRequestOptionsPolicyTest {
    @Test
    fun `request options preserve supported auth fields and prefer explicit user agent`() {
        val options = VlcRequestOptionsPolicy.requestOptions(
            headers = mapOf(
                "user-agent" to "HeaderAgent/1.0",
                "referer" to "https://player.example.invalid/",
                "COOKIE" to "session=fixture",
            ),
            userAgent = "ProjectorQA/1.0",
        )

        assertEquals(
            listOf(
                ":http-user-agent=ProjectorQA/1.0",
                ":http-referrer=https://player.example.invalid/",
                ":http-cookies=session=fixture",
            ),
            options,
        )
    }

    @Test
    fun `request options fall back to user agent header and omit blank values`() {
        val options = VlcRequestOptionsPolicy.requestOptions(
            headers = mapOf(
                "User-Agent" to "HeaderAgent/1.0",
                "Referer" to " ",
                "Cookie" to "",
            ),
            userAgent = "",
        )

        assertEquals(listOf(":http-user-agent=HeaderAgent/1.0"), options)
    }
}
