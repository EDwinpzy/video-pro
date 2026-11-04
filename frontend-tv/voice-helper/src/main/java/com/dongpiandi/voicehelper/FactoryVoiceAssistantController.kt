package com.dongpiandi.voicehelper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dongpiandi.voicehelper.assistant.AssistantConfigStore
import com.dongpiandi.voicehelper.assistant.OpenAiChatClient
import com.dongpiandi.voicehelper.assistant.VendorLogTranscriptReader
import com.dongpiandi.voicehelper.assistant.VendorUiTranscriptCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The factory package owns the listening UI and ASR. This controller only receives the resulting
 * text, prevents the factory NLP/TTS from continuing, and substitutes the configured LLM answer.
 */
internal class FactoryVoiceAssistantController(
    context: Context,
    private val overlay: VoiceAnswerOverlay,
    private val collector: VendorUiTranscriptCollector,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val configStore = AssistantConfigStore(appContext)
    private val client = OpenAiChatClient(configStore)
    private val tts = VendorTtsClient(appContext)
    private val logReader = VendorLogTranscriptReader(appContext)
    private var answerJob: Job? = null
    private var generation = 0
    private var customCardVisible = false
    private var factorySessionActive = false
    private var factoryStopPending = false

    val active: Boolean
        get() = factorySessionActive || customCardVisible

    val hasVisibleCard: Boolean
        get() = customCardVisible

    fun onFactoryStart() {
        generation++
        answerJob?.cancel()
        answerJob = null
        factorySessionActive = true
        factoryStopPending = false
        customCardVisible = false
        tts.cancel()
        overlay.dismiss()
        collector.clear()
        logReader.beginSession()
        Log.i(TAG, "factory voice session started")
    }

    fun onFactoryStop() {
        if (!factorySessionActive) {
            // NEAR_START can be missed during process reconnection; still process the release.
            onFactoryStart()
        }
        if (factoryStopPending) return
        factoryStopPending = true
        val token = generation
        tts.stopFactorySpeechOnly()
        main.postDelayed(
            {
                if (token != generation) return@postDelayed
                resolveTranscript(token)
            },
            FACTORY_RESULT_SETTLE_MS,
        )
    }

    fun submitTextForTest(text: String) {
        generation++
        val token = generation
        factorySessionActive = true
        answer(text.trim(), token)
    }

    fun cancel() {
        generation++
        answerJob?.cancel()
        answerJob = null
        main.removeCallbacksAndMessages(null)
        tts.cancel()
        overlay.dismiss()
        collector.clear()
        customCardVisible = false
        factorySessionActive = false
        factoryStopPending = false
    }

    fun close() {
        cancel()
        logReader.close()
        scope.cancel()
    }

    private fun resolveTranscript(token: Int) {
        collector.refreshAndGet()?.let { transcript ->
            answer(transcript, token)
            return
        }
        logReader.readRecent { transcript ->
            if (token != generation) return@readRecent
            if (transcript.isNullOrBlank()) {
                showFailure(token, "没有听清，请重试")
            } else {
                answer(transcript, token)
            }
        }
    }

    private fun answer(
        transcript: String,
        token: Int,
    ) {
        tts.stopFactoryReplyAndDismiss()
        factorySessionActive = false
        factoryStopPending = false
        if (transcript.isBlank()) {
            showFailure(token, "没有听清，请重试")
            return
        }
        overlay.showThinking(transcript)
        customCardVisible = true
        answerJob?.cancel()
        answerJob =
            scope.launch {
                try {
                    val result = client.complete(transcript)
                    if (token != generation) return@launch
                    if (result.model.isBlank() || result.answer.isBlank()) {
                        overlay.showError(transcript, result.answer)
                        customCardVisible = true
                        return@launch
                    }
                    overlay.showAnswer(transcript, result.answer, result.model)
                    customCardVisible = true
                    tts.speak(result.answer)
                    Log.i(TAG, "llm answered model=${result.model} chars=${result.answer.length}")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (token != generation) return@launch
                    Log.w(TAG, "assistant request failed: ${error.javaClass.simpleName}")
                    overlay.showError(transcript, "语音助手暂时无法回答，请稍后再试。")
                    customCardVisible = true
                }
            }
    }

    private fun showFailure(
        token: Int,
        message: String,
    ) {
        if (token != generation) return
        tts.stopFactoryReplyAndDismiss()
        overlay.showError("", message)
        customCardVisible = true
        factorySessionActive = false
        factoryStopPending = false
    }

    private companion object {
        const val TAG = "VoiceHelperAssistant"
        const val FACTORY_RESULT_SETTLE_MS = 650L
    }
}
