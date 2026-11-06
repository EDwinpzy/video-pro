package com.qiubo.optimaltv.voice

import android.content.Intent

internal object VoiceSearchContract {
    const val ACTION_OPEN_SEARCH = "com.dongpiandi.videopro.action.OPEN_SEARCH"
    const val EXTRA_QUERY = "query"
    const val PERMISSION = "com.dongpiandi.videopro.permission.VOICE_SEARCH"
    const val MAX_QUERY_LENGTH = 80

    fun normalizeQuery(raw: String?): String? {
        val cleaned =
            raw
                ?.replace(Regex("\\s+"), " ")
                ?.filterNot(Char::isISOControl)
                ?.trim()
                ?: return null
        return cleaned.take(MAX_QUERY_LENGTH).takeIf(String::isNotEmpty)
    }

    fun queryFromIntent(intent: Intent?): String? {
        if (intent?.action != ACTION_OPEN_SEARCH) return null
        return normalizeQuery(intent.getStringExtra(EXTRA_QUERY))
    }
}

internal object VoiceSearchBus {
    private data class PendingRequest(
        val query: String,
        val createdAtMs: Long,
    )

    private const val MAX_AGE_MS = 10 * 60 * 1_000L

    private var pending: PendingRequest? = null
    private var navigationPending = false

    @Synchronized
    fun submit(query: String): Boolean {
        val normalized = VoiceSearchContract.normalizeQuery(query) ?: return false
        pending = PendingRequest(normalized, System.currentTimeMillis())
        navigationPending = true
        return true
    }

    @Synchronized
    fun takeNavigationQuery(): String? {
        val request = pending ?: return null
        if (isExpired(request)) {
            clear()
            return null
        }
        if (!navigationPending) return null
        navigationPending = false
        return request.query
    }

    @Synchronized
    fun takeQuery(): String? {
        val request = pending ?: return null
        clear()
        return request.query.takeUnless { isExpired(request) }
    }

    @Synchronized
    fun hasPendingQuery(): Boolean {
        val request = pending ?: return false
        if (isExpired(request)) {
            clear()
            return false
        }
        return true
    }

    @Synchronized
    fun resetForTest() {
        clear()
    }

    private fun isExpired(request: PendingRequest): Boolean =
        System.currentTimeMillis() - request.createdAtMs > MAX_AGE_MS

    private fun clear() {
        pending = null
        navigationPending = false
    }
}
