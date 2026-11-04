package com.qiubo.optimaltv.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class CookieHeaderMergeTest {
    @Test
    fun replacesExistingCookieAndPreservesUnrelatedCookies() {
        val merged =
            mergeCookieHeader(
                current = "session=old; theme=dark",
                setCookieHeaders =
                    listOf(
                        "session=new; Path=/; HttpOnly; Secure; SameSite=Lax",
                        "device=abc; Path=/",
                    ),
            )

        assertEquals("session=new; theme=dark; device=abc", merged)
    }

    @Test
    fun removesCookieWhenServerExpiresIt() {
        val merged =
            mergeCookieHeader(
                current = "session=old; device=abc",
                setCookieHeaders = listOf("session=; Max-Age=0; Path=/"),
            )

        assertEquals("device=abc", merged)
    }

    @Test
    fun ignoresMalformedSetCookieEntries() {
        val merged =
            mergeCookieHeader(
                current = "session=old",
                setCookieHeaders = listOf("invalid", "=missing-name", "session=refreshed"),
            )

        assertEquals("session=refreshed", merged)
    }
}
