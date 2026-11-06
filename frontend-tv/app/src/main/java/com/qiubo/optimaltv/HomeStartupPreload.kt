package com.qiubo.optimaltv

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope

/** One transient startup request must not strand the whole home screen on the retry UI. */
internal suspend fun <T> retryStartupLoad(
    attempts: Int = 2,
    retryDelayMs: Long = 750L,
    onFailure: (Int, Exception) -> Unit = { _, _ -> },
    load: suspend () -> T,
): T {
    var lastFailure: Exception? = null
    repeat(attempts.coerceAtLeast(1)) { attempt ->
        try {
            return load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            lastFailure = failure
            onFailure(attempt + 1, failure)
            if (attempt + 1 < attempts && retryDelayMs > 0L) delay(retryDelayMs)
        }
    }
    throw lastFailure ?: IllegalStateException("startup load failed")
}

/** Entry is released only after every tab and its artwork complete successfully. */
internal suspend fun <T> preloadHomeTabs(
    categories: List<String>,
    loadTab: suspend (String) -> List<T>,
    loadArtwork: suspend (List<T>) -> Unit,
    onTabReady: (Int, Int) -> Unit,
) = supervisorScope {
    var completed = 0
    val results = categories.map { category ->
        async {
            try {
                Result.success(
                    loadTab(category).also {
                        // Children resume on the caller context; progress is serialized there.
                        onTabReady(++completed, categories.size)
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }
    }.awaitAll()
    val firstFailure =
        results.asSequence()
            .mapNotNull { it.exceptionOrNull() }
            .firstOrNull()
    if (firstFailure != null) throw firstFailure
    val cards = results.mapNotNull { it.getOrNull() }.flatten().distinct()
    loadArtwork(cards)
}
