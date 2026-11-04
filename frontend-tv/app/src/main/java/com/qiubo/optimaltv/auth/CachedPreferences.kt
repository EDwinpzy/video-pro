package com.qiubo.optimaltv.auth

/**
 * Caches an expensive preferences instance while making deletion invalidate it before
 * the backing file is removed. Updates resolve the current instance under the same lock,
 * so concurrent writes cannot keep using an instance whose file was deleted.
 */
internal class CachedPreferences<T> {
    @Volatile
    private var cached: T? = null

    fun get(create: () -> T): T =
        cached ?: synchronized(this) {
            cached ?: create().also { cached = it }
        }

    fun <R> update(
        create: () -> T,
        block: (T) -> R,
    ): R = synchronized(this) { block(getLocked(create)) }

    fun invalidateAndDelete(
        clear: (T) -> Unit,
        delete: () -> Unit,
    ) {
        synchronized(this) {
            val current = cached
            cached = null
            current?.let { runCatching { clear(it) } }
            runCatching(delete)
        }
    }

    private fun getLocked(create: () -> T): T = cached ?: create().also { cached = it }
}
