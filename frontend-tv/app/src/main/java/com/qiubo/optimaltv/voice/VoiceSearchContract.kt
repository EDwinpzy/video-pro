package com.qiubo.optimaltv.voice

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class VoiceSearchRequest(
    val query: String,
    val openFirstResult: Boolean,
)

internal object VoiceSearchContract {
    const val ACTION_OPEN_SEARCH = "com.dongpiandi.videopro.action.OPEN_SEARCH"
    const val EXTRA_QUERY = "query"
    const val EXTRA_OPEN_FIRST_RESULT = "openFirstResult"
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

    fun requestFromIntent(intent: Intent?): VoiceSearchRequest? {
        if (intent?.action != ACTION_OPEN_SEARCH) return null
        val query = normalizeQuery(intent.getStringExtra(EXTRA_QUERY)) ?: return null
        return VoiceSearchRequest(
            query = query,
            openFirstResult = intent.getBooleanExtra(EXTRA_OPEN_FIRST_RESULT, false),
        )
    }
}

internal object VoiceSearchBus {
    private data class PendingRequest(
        val request: VoiceSearchRequest,
        val createdAtMs: Long,
    )

    private const val MAX_AGE_MS = 10 * 60 * 1_000L

    private var pending: PendingRequest? = null
    private var navigationPending = false
    private val revisions = MutableStateFlow(0L)

    val updates: StateFlow<Long> = revisions.asStateFlow()

    @Synchronized
    fun submit(request: VoiceSearchRequest): Boolean {
        val normalized = VoiceSearchContract.normalizeQuery(request.query) ?: return false
        pending =
            PendingRequest(
                request.copy(query = normalized),
                System.currentTimeMillis(),
            )
        navigationPending = true
        revisions.value = revisions.value + 1
        return true
    }

    @Synchronized
    fun takeNavigationRequest(): VoiceSearchRequest? {
        val request = pending ?: return null
        if (isExpired(request)) {
            clear()
            return null
        }
        if (!navigationPending) return null
        navigationPending = false
        return request.request
    }

    @Synchronized
    fun takeRequest(): VoiceSearchRequest? {
        val request = pending ?: return null
        clear()
        return request.request.takeUnless { isExpired(request) }
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
        revisions.value = 0L
    }

    private fun isExpired(request: PendingRequest): Boolean =
        System.currentTimeMillis() - request.createdAtMs > MAX_AGE_MS

    private fun clear() {
        pending = null
        navigationPending = false
    }
}
