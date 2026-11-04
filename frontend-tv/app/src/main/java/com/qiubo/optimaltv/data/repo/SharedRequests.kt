package com.qiubo.optimaltv.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Shares in-flight work; canceling a screen's waiter does not cancel repository work. */
internal class SharedRequests<K, V>(private val scope: CoroutineScope) {
    private val requests = mutableMapOf<K, Deferred<V>>()

    suspend fun await(key: K, load: suspend () -> V): V {
        val request = synchronized(requests) {
            requests[key]?.takeUnless { it.isCompleted }
                ?: scope.async(start = CoroutineStart.LAZY) { load() }.also { pending ->
                    requests[key] = pending
                    pending.invokeOnCompletion {
                        synchronized(requests) {
                            if (requests[key] === pending) requests.remove(key)
                        }
                    }
                }
        }
        request.start()
        return request.await()
    }

    fun clear() {
        val pending = synchronized(requests) {
            requests.values.toList().also { requests.clear() }
        }
        pending.forEach { it.cancel() }
    }
}
