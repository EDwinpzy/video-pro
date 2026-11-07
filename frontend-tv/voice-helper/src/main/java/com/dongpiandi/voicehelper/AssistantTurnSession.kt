package com.dongpiandi.voicehelper

/**
 * Main-thread state for one push-to-talk assistant turn. Recognition may finish before or after
 * the remote key is released, so the turn keeps the best partial text until a final result arrives.
 */
internal class AssistantTurnSession {
    enum class State {
        IDLE,
        LISTENING,
        WAITING_RESULT,
        RESPONDING,
    }

    var state = State.IDLE
        private set

    var generation = 0
        private set

    var latestText = ""
        private set

    var hasFinalResult = false
        private set

    val active: Boolean
        get() = state != State.IDLE

    fun begin(): Int {
        generation++
        latestText = ""
        hasFinalResult = false
        state = State.LISTENING
        return generation
    }

    fun observePartial(
        token: Int,
        text: String,
    ): String? {
        if (!acceptsRecognition(token)) return null
        text.trim().takeIf(String::isNotEmpty)?.let { latestText = it }
        return latestText.takeIf(String::isNotEmpty)
    }

    fun observeResult(
        token: Int,
        text: String,
    ): String? {
        if (!acceptsRecognition(token)) return null
        hasFinalResult = true
        text.trim().takeIf(String::isNotEmpty)?.let { latestText = it }
        return latestText.takeIf(String::isNotEmpty)
    }

    fun release(token: Int): Boolean {
        if (token != generation || state != State.LISTENING) return false
        state = State.WAITING_RESULT
        return true
    }

    fun finishReleased(token: Int): String? {
        if (token != generation || state != State.WAITING_RESULT) return null
        val transcript = latestText.trim()
        if (transcript.isEmpty()) return null
        state = State.RESPONDING
        return transcript
    }

    fun finishTimedOut(token: Int): String? {
        if (token != generation || state != State.WAITING_RESULT) return null
        return finishReleased(token)
    }

    fun injectFinal(
        token: Int,
        text: String,
    ): Boolean {
        if (token != generation || state == State.IDLE) return false
        val transcript = text.trim()
        if (transcript.isEmpty()) return false
        latestText = transcript
        hasFinalResult = true
        state = State.RESPONDING
        return true
    }

    fun injectForTest(text: String): Int? {
        val transcript = text.trim()
        if (transcript.isEmpty()) return null
        generation++
        latestText = transcript
        hasFinalResult = true
        state = State.RESPONDING
        return generation
    }

    fun fail(token: Int): Boolean {
        if (token != generation) return false
        cancel()
        return true
    }

    fun cancel() {
        generation++
        latestText = ""
        hasFinalResult = false
        state = State.IDLE
    }

    private fun acceptsRecognition(token: Int): Boolean =
        token == generation &&
            (state == State.LISTENING || state == State.WAITING_RESULT)
}
