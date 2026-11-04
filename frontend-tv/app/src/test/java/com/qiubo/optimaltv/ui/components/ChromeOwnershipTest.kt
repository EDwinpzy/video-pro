package com.qiubo.optimaltv.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeOwnershipTest {
    @Test
    fun leavingPageCannotReleaseReplacementOwner() {
        val ownership = ChromeOwnership()
        val oldPage = Any()
        val newPage = Any()

        ownership.acquire(oldPage)
        ownership.acquire(newPage)

        assertFalse(ownership.release(oldPage))
        assertTrue(ownership.release(newPage))
        assertFalse(ownership.release(newPage))
    }
}
