package com.qiubo.optimaltv.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterUrlsTest {
    @Test
    fun officialTransparentPlaceholderIsNotTreatedAsPoster() {
        assertFalse(
            isUsablePosterUrl(
                "https://gimg0.example/gimg?src=assets/images/placeholder-poster.svg",
            ),
        )
    }

    @Test
    fun normalRemotePosterRemainsLoadable() {
        assertTrue(
            isUsablePosterUrl(
                "https://gimg0.example/gimg?src=assets/images/poster.webp",
            ),
        )
    }

    @Test
    fun firstUsablePosterSkipsPlaceholder() {
        assertEquals(
            "https://gimg0.example/poster.webp",
            firstUsablePosterUrl(
                "https://gimg0.example/placeholder-poster.svg",
                "https://gimg0.example/poster.webp",
            ),
        )
    }

    @Test
    fun rejectsFaviconsRelativeAndNonHttpUrls() {
        assertFalse(isUsablePosterUrl("https://gimg0.example/favicon.ico"))
        assertFalse(isUsablePosterUrl("/assets/images/poster.webp"))
        assertFalse(isUsablePosterUrl("data:image/gif;base64,AAAA"))
        assertFalse(isUsablePosterUrl("file:///sdcard/poster.webp"))
    }

    @Test
    fun trimsPosterUrlAndDeduplicatesCandidates() {
        assertEquals(
            listOf("https://gimg0.example/poster.webp"),
            usablePosterUrls(
                "  https://gimg0.example/poster.webp  ",
                "https://gimg0.example/poster.webp",
                "https://gimg0.example/placeholder_poster.svg",
            ),
        )
    }

    @Test
    fun candidateRegistryPreservesPriorityAfterFilteringAndDeduplication() {
        val primary =
            firstUsablePosterUrl(
                "  https://img.example/first.webp  ",
                "https://img.example/placeholder-poster.svg",
                "not-a-url",
                "https://img.example/second.webp",
                "https://img.example/first.webp",
                "http://cdn.example/third.webp",
            )

        assertEquals("https://img.example/first.webp", primary)
        assertEquals(
            listOf(
                "https://img.example/first.webp",
                "https://img.example/second.webp",
                "http://cdn.example/third.webp",
            ),
            posterCandidatesFor(primary),
        )
    }

    @Test
    fun invalidPrimaryHasNoCandidateChain() {
        assertTrue(posterCandidatesFor("data:image/gif;base64,AAAA").isEmpty())
    }
}
