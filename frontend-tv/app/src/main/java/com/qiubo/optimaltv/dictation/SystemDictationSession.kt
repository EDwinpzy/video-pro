package com.qiubo.optimaltv.dictation

/** Main-thread state for exactly one press-to-talk session in one editor. */
internal class SystemDictationSession {
    enum class State {
        IDLE,
        LISTENING,
        WAITING_RESULT,
    }

    var state = State.IDLE
        private set

    var generation = 0
        private set

    private var editor = 0L
    private var latestText = ""

    fun begin(editorSession: Long): Int? {
        if (state != State.IDLE || editorSession <= 0L) return null
        generation++
        editor = editorSession
        latestText = ""
        state = State.LISTENING
        return generation
    }

    fun release(): Boolean {
        if (state != State.LISTENING) return false
        state = State.WAITING_RESULT
        return true
    }

    fun observe(token: Int, text: String) {
        if (token != generation || state == State.IDLE) return
        text.trim().takeIf(String::isNotEmpty)?.let { latestText = it }
    }

    /**
     * Returns a transcript at most once after release. The caller must pass the editor that is still
     * active so a result from a closed or replaced input connection is never committed elsewhere.
     */
    fun finish(token: Int, text: String?, currentEditor: Long?): String? {
        if (token != generation || state != State.WAITING_RESULT) return null
        if (currentEditor == null || editor != currentEditor) {
            cancel()
            return null
        }
        val transcript = text.orEmpty().trim().ifEmpty { latestText }.takeIf(String::isNotEmpty)
        cancel()
        return transcript
    }

    fun cancel() {
        generation++
        editor = 0L
        latestText = ""
        state = State.IDLE
    }
}

