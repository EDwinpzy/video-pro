package com.qiubo.optimaltv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvRowFocusMemoryTest {
    @Test
    fun reorderPreservesVisibleBusinessIdentity() {
        val memory = TvRowFocusMemory()
        memory.focused("b")
        assertEquals("b", memory.entryKey(listOf("c", "b", "a"), setOf("a", "b", "c")))
    }

    @Test
    fun removedOrRecycledItemFallsBackToMountedItem() {
        val memory = TvRowFocusMemory()
        memory.focused("b")
        assertEquals("c", memory.entryKey(listOf("a", "b", "c"), setOf("c")))
        assertEquals("a", memory.entryKey(listOf("a", "c"), setOf("a", "c")))
        assertNull(memory.entryKey(emptyList(), emptySet()))
        assertNull(memory.entryKey(listOf("a"), emptySet()))
    }
}
