package com.qiubo.optimaltv.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CachedPreferencesTest {
    @Test
    fun concurrentReadersShareOneCachedInstance() {
        val creates = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val cache = CachedPreferences<InMemoryPreferences>()
        try {
            val requests =
                (1..8).map {
                    pool.submit<InMemoryPreferences> {
                        start.await()
                        cache.get {
                            creates.incrementAndGet()
                            InMemoryPreferences()
                        }
                    }
                }
            start.countDown()
            val instances = requests.map { it.get(5, TimeUnit.SECONDS) }
            instances.drop(1).forEach { assertSame(instances.first(), it) }
            assertEquals(1, creates.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun deletionInvalidatesTheInstanceBeforeConcurrentUpdatesRun() {
        val instances = mutableListOf<InMemoryPreferences>()
        val deleted = AtomicInteger()
        val writes = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val cache = CachedPreferences<InMemoryPreferences>()
        try {
            cache.update(create = { newInstance(instances) }) { it.value = "old" }
            val old = instances.single()
            cache.invalidateAndDelete(
                clear = { it.value = null },
                delete = { deleted.incrementAndGet() },
            )

            val updates =
                (1..8).map {
                    pool.submit<Unit> {
                        cache.update(create = { newInstance(instances) }) {
                            it.value = "new"
                            writes.incrementAndGet()
                        }
                    }
                }
            updates.forEach { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, deleted.get())
            assertEquals(8, writes.get())
            assertEquals(2, instances.size)
            assertNull(old.value)
            assertNotSame(old, instances.last())
            assertEquals("new", instances.last().value)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun failedDeletionStillForcesTheNextWriteToCreateANewInstance() {
        val instances = mutableListOf<InMemoryPreferences>()
        val cache = CachedPreferences<InMemoryPreferences>()
        cache.update(create = { newInstance(instances) }) { it.value = "old" }

        cache.invalidateAndDelete(
            clear = { error("clear failed") },
            delete = { error("delete failed") },
        )
        cache.update(create = { newInstance(instances) }) { it.value = "new" }

        assertEquals(2, instances.size)
        assertNotSame(instances.first(), instances.last())
        assertEquals("new", instances.last().value)
    }

    private fun newInstance(instances: MutableList<InMemoryPreferences>): InMemoryPreferences =
        InMemoryPreferences().also(instances::add)

    private class InMemoryPreferences(
        var value: String? = null,
    )
}
