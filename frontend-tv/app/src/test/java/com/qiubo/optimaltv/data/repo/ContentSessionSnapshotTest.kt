package com.qiubo.optimaltv.data.repo

import com.qiubo.optimaltv.auth.DongpianAuthSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContentSessionSnapshotTest {
    @Test
    fun `same stored session shares one repository request`() {
        val first = DongpianAuthSession("owner", "Example user", "session=A")
        val storedCopy = first.copy()

        assertEquals(contentSessionSnapshot(first), contentSessionSnapshot(storedCopy))
    }

    @Test
    fun `re-login with same owner does not join the old request`() {
        val first = DongpianAuthSession("owner", "Example user", "session=A")
        val relogin = DongpianAuthSession("owner", "Example user", "session=B")

        assertNotEquals(contentSessionSnapshot(first), contentSessionSnapshot(relogin))
    }

    @Test
    fun `same hash cookies remain distinct for the same owner`() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val first = DongpianAuthSession("owner", "Example user", "Aa")
        val second = DongpianAuthSession("owner", "Example user", "BB")

        assertNotEquals(contentSessionSnapshot(first), contentSessionSnapshot(second))
    }

    @Test
    fun `logout invalidates an in-flight repository request`() {
        val signedIn = DongpianAuthSession("owner", "Example user", "session=A")

        assertNotEquals(contentSessionSnapshot(signedIn), contentSessionSnapshot(null))
    }
}
