package com.dongpiandi.voicehelper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.dongpiandi.voice.core.SystemDictationRecognizer
import com.dongpiandi.voice.core.SystemDictationRecognizerFactory
import com.dongpiandi.voice.core.VendorAssistantSuppressor
import com.dongpiandi.voicehelper.assistant.AssistantConfigStore
import com.dongpiandi.voicehelper.assistant.OpenAiChatClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A complete helper-owned push-to-talk assistant turn. Recognition still comes from the installed
 * JMGO AIUI engine and spoken answers still use the factory TTS service, but the factory assistant
 * activity, dialog, NLP response, and overlay are never used.
 */
internal class VoiceAssistantController(
    private val context: Context,
    private val overlay: VoiceAssistantOverlay,
    private val beginMicrophoneForeground: () -> Boolean,
    private val endMicrophoneForeground: () -> Unit,
    private val recognizerFactory: () -> SystemDictationRecognizer = {
        SystemDictationRecognizerFactory.create(context)
    },
    private val chatClient: OpenAiChatClient =
        OpenAiChatClient(AssistantConfigStore(context)),
    private val tts: VendorTtsClient = VendorTtsClient(context),
    private val factorySuppressor: VendorAssistantSuppressor =
        VendorAssistantSuppressor(context),
) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = AssistantTurnSession()
    private var recognizer: SystemDictationRecognizer? = null
    private var answerJob: Job? = null
    private var displayDismiss: Runnable? = null
    private var ownsVoiceKey = false
    private var consumesBackSequence = false
    private var lastFactorySuppressionAt = 0L

    private val listeningTimeout =
        Runnable {
            if (session.state == AssistantTurnSession.State.LISTENING) {
                fail(context.getString(R.string.assistant_timeout))
            }
        }
    private val resultTimeout =
        Runnable {
            if (session.state != AssistantTurnSession.State.WAITING_RESULT) return@Runnable
            val token = session.generation
            val transcript = session.finishTimedOut(token)
            if (transcript == null) {
                fail(context.getString(R.string.assistant_no_speech))
            } else {
                cleanupRecognizer()
                runCatching(endMicrophoneForeground)
                overlay.showThinking(transcript)
                answer(transcript, token)
            }
        }

    val active: Boolean
        get() =
            session.active ||
                answerJob?.isActive == true ||
                ownsVoiceKey

    fun onVoiceKeyEvent(event: KeyEvent): Boolean {
        if (!isVoiceKey(event)) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount != 0 || ownsVoiceKey) return true
                ownsVoiceKey = true
                startListening()
            }
            KeyEvent.ACTION_UP -> {
                if (!ownsVoiceKey) return false
                ownsVoiceKey = false
                if (event.isCanceled) {
                    cancel()
                } else {
                    releaseVoiceKey()
                }
            }
            else -> return false
        }
        return true
    }

    fun onNavigationKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (!active && !overlay.isShowing) return false
                cancel()
                consumesBackSequence = true
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (!consumesBackSequence) return false
                consumesBackSequence = false
                return true
            }
        }
        return false
    }

    fun onEditorChanged() {
        if (active || overlay.isShowing) cancel()
    }

    fun submitTextForTest(text: String) {
        cancel()
        val token = session.injectForTest(text) ?: return
        overlay.showThinking(text)
        answer(text, token)
    }

    fun suppressUnexpectedFactorySession() {
        when (session.state) {
            AssistantTurnSession.State.LISTENING,
            AssistantTurnSession.State.WAITING_RESULT,
            -> factorySuppressor.begin()
            AssistantTurnSession.State.IDLE ->
                tts.stopFactoryReplyAndDismiss()
            AssistantTurnSession.State.RESPONDING -> Unit
        }
    }

    fun onFactoryWindowSeen() {
        val now = SystemClock.uptimeMillis()
        if (now - lastFactorySuppressionAt < FACTORY_SUPPRESSION_DEBOUNCE_MS) return
        lastFactorySuppressionAt = now
        when (session.state) {
            AssistantTurnSession.State.LISTENING,
            AssistantTurnSession.State.WAITING_RESULT,
            -> factorySuppressor.begin()
            AssistantTurnSession.State.RESPONDING ->
                tts.dismissFactoryUiOnly()
            AssistantTurnSession.State.IDLE ->
                tts.stopFactoryReplyAndDismiss()
        }
    }

    fun cancel() {
        main.removeCallbacks(listeningTimeout)
        main.removeCallbacks(resultTimeout)
        clearDisplayDismiss()
        answerJob?.cancel()
        answerJob = null
        session.cancel()
        ownsVoiceKey = false
        consumesBackSequence = false
        cleanupRecognizer()
        runCatching(endMicrophoneForeground)
        factorySuppressor.finish()
        tts.cancel()
        overlay.dismiss()
    }

    fun close() {
        cancel()
        main.removeCallbacksAndMessages(null)
        factorySuppressor.close()
        overlay.close()
        scope.cancel()
    }

    private fun startListening() {
        main.removeCallbacks(listeningTimeout)
        main.removeCallbacks(resultTimeout)
        clearDisplayDismiss()
        answerJob?.cancel()
        answerJob = null
        cleanupRecognizer()
        session.cancel()
        tts.cancel()
        factorySuppressor.begin()
        overlay.showListening()

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            fail(context.getString(R.string.dictation_permission_required))
            return
        }
        if (!runCatching(beginMicrophoneForeground).getOrDefault(false)) {
            fail(context.getString(R.string.assistant_microphone_unavailable))
            return
        }

        val token = session.begin()
        val engine = recognizerFactory()
        recognizer = engine
        try {
            engine.start(
                object : SystemDictationRecognizer.Listener {
                    override fun onPartial(text: String) {
                        main.post {
                            val current = session.observePartial(token, text) ?: return@post
                            if (session.state == AssistantTurnSession.State.LISTENING) {
                                overlay.updateTranscript(current)
                            } else {
                                overlay.showThinking(current)
                            }
                        }
                    }

                    override fun onResult(text: String) {
                        main.post {
                            val current = session.observeResult(token, text) ?: return@post
                            overlay.updateTranscript(current)
                            if (session.state == AssistantTurnSession.State.WAITING_RESULT) {
                                completeReleasedTurn(token)
                            }
                        }
                    }

                    override fun onError() {
                        main.post {
                            if (token == session.generation) {
                                fail(context.getString(R.string.assistant_no_speech))
                            }
                        }
                    }
                },
            )
            main.postDelayed(listeningTimeout, MAX_LISTENING_MS)
        } catch (error: Throwable) {
            Log.w(TAG, "recognizer start failed: ${error.javaClass.simpleName}")
            fail(context.getString(R.string.dictation_engine_unavailable))
        }
    }

    private fun releaseVoiceKey() {
        main.removeCallbacks(listeningTimeout)
        val token = session.generation
        if (!session.release(token)) {
            cleanupRecognizer()
            runCatching(endMicrophoneForeground)
            return
        }
        runCatching { recognizer?.stop() }
            .onFailure { error ->
                Log.w(TAG, "recognizer stop failed: ${error.javaClass.simpleName}")
                fail(context.getString(R.string.assistant_no_speech))
                return
            }
        factorySuppressor.finish()

        if (session.hasFinalResult) {
            completeReleasedTurn(token)
            return
        }
        overlay.showThinking(session.latestText)
        main.postDelayed(resultTimeout, MAX_RESULT_WAIT_MS)
    }

    private fun completeReleasedTurn(token: Int) {
        main.removeCallbacks(resultTimeout)
        main.removeCallbacks(listeningTimeout)
        val transcript = session.finishReleased(token) ?: return
        cleanupRecognizer()
        runCatching(endMicrophoneForeground)
        overlay.showThinking(transcript)
        answer(transcript, token)
    }

    private fun answer(
        transcript: String,
        token: Int,
    ) {
        if (transcript.isBlank()) {
            fail(context.getString(R.string.assistant_no_speech))
            return
        }
        answerJob?.cancel()
        answerJob =
            scope.launch {
                try {
                    val requestStartedAt = SystemClock.elapsedRealtime()
                    val result = chatClient.complete(transcript)
                    if (!isCurrent(token)) return@launch
                    if (result.model.isBlank() || result.answer.isBlank()) {
                        showFailure(token, result.answer.ifBlank {
                            context.getString(R.string.assistant_unavailable)
                        })
                        return@launch
                    }
                    factorySuppressor.stopPending()
                    tts.cancel()
                    overlay.showAnswer(
                        transcript,
                        result.answer,
                        result.model,
                        result.usedWebSearch,
                    )
                    tts.speak(result.answer)
                    Log.i(
                        TAG,
                        "assistant answered model=${result.model} " +
                            "webSearch=${result.usedWebSearch} chars=${result.answer.length} " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - requestStartedAt}",
                    )
                    scheduleDismiss(result.answer.length)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!isCurrent(token)) return@launch
                    Log.w(TAG, "assistant request failed: ${error.javaClass.simpleName}")
                    showFailure(token, context.getString(R.string.assistant_unavailable))
                }
            }
    }

    private fun showFailure(
        token: Int,
        message: String,
    ) {
        if (token != session.generation) return
        main.removeCallbacks(listeningTimeout)
        main.removeCallbacks(resultTimeout)
        session.cancel()
        cleanupRecognizer()
        runCatching(endMicrophoneForeground)
        factorySuppressor.stopPending()
        tts.stopFactoryReplyAndDismiss()
        overlay.showError(message)
        tts.speak(message)
        scheduleDisplayDismiss(ERROR_DISPLAY_MS) { dismissDisplayedOverlay() }
    }

    private fun fail(message: String) {
        main.removeCallbacks(listeningTimeout)
        main.removeCallbacks(resultTimeout)
        session.cancel()
        cleanupRecognizer()
        runCatching(endMicrophoneForeground)
        factorySuppressor.finish()
        factorySuppressor.stopPending()
        tts.stopFactoryReplyAndDismiss()
        overlay.showError(message)
        tts.speak(message)
        scheduleDisplayDismiss(ERROR_DISPLAY_MS) { dismissDisplayedOverlay() }
    }

    private fun scheduleDismiss(answerLength: Int) {
        val delay =
            (MIN_ANSWER_DISPLAY_MS + answerLength * ANSWER_DISPLAY_MS_PER_CHAR)
                .coerceIn(MIN_ANSWER_DISPLAY_MS, MAX_ANSWER_DISPLAY_MS)
        scheduleDisplayDismiss(delay) { finishDisplayedTurn() }
    }

    private fun finishDisplayedTurn() {
        if (session.state != AssistantTurnSession.State.RESPONDING) return
        session.cancel()
        tts.cancel()
        overlay.dismiss()
    }

    private fun dismissDisplayedOverlay() {
        session.cancel()
        tts.cancel()
        overlay.dismiss()
    }

    private fun scheduleDisplayDismiss(
        delayMs: Long,
        action: () -> Unit,
    ) {
        clearDisplayDismiss()
        val runnable =
            Runnable {
                displayDismiss = null
                action()
            }
        displayDismiss = runnable
        main.postDelayed(runnable, delayMs)
    }

    private fun clearDisplayDismiss() {
        displayDismiss?.let(main::removeCallbacks)
        displayDismiss = null
    }

    private fun cleanupRecognizer() {
        main.removeCallbacks(listeningTimeout)
        main.removeCallbacks(resultTimeout)
        runCatching { recognizer?.cancel() }
        recognizer = null
    }

    private fun isCurrent(token: Int): Boolean =
        token == session.generation &&
            session.state == AssistantTurnSession.State.RESPONDING

    private fun isVoiceKey(event: KeyEvent): Boolean =
        event.keyCode == VENDOR_VOICE_KEY ||
            event.scanCode == VENDOR_VOICE_SCAN_CODE ||
            event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
            event.keyCode == KeyEvent.KEYCODE_ASSIST

    private companion object {
        const val TAG = "VoiceHelperAssistant"
        const val VENDOR_VOICE_KEY = 609
        const val VENDOR_VOICE_SCAN_CODE = 192
        const val MAX_LISTENING_MS = 30_000L
        const val MAX_RESULT_WAIT_MS = 8_000L
        const val FACTORY_SUPPRESSION_DEBOUNCE_MS = 500L
        const val ERROR_DISPLAY_MS = 8_000L
        const val MIN_ANSWER_DISPLAY_MS = 15_000L
        const val MAX_ANSWER_DISPLAY_MS = 120_000L
        const val ANSWER_DISPLAY_MS_PER_CHAR = 180L
    }
}
