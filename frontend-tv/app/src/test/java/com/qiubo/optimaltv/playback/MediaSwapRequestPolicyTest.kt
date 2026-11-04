package com.qiubo.optimaltv.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MediaSwapRequestPolicyTest {
    @Test
    fun `live renewal preserves every playback request property`() {
        val request = MediaSwapRequestPolicy.request(
            url = "https://media.example.invalid/live.m3u8",
            autoplay = false,
            headers = mapOf(
                "Referer" to "https://player.example.invalid/",
                "Cookie" to "session=fixture",
            ),
            userAgent = "ProjectorQA/1.0",
            mimeType = "application/vnd.apple.mpegurl",
        )

        assertEquals("https://media.example.invalid/live.m3u8", request.url)
        assertEquals(0L, request.startMs)
        assertEquals("https://player.example.invalid/", request.headers["Referer"])
        assertEquals("session=fixture", request.headers["Cookie"])
        assertEquals("ProjectorQA/1.0", request.userAgent)
        assertEquals("application/vnd.apple.mpegurl", request.mimeType)
        assertFalse(request.autoplay)
    }
}
