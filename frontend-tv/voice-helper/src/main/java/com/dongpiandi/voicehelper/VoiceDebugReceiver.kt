package com.dongpiandi.voicehelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dongpiandi.voice.core.SystemDictationRecognizerFactory
import com.dongpiandi.voicehelper.assistant.AssistantConfigStore

/**
 * Present only in the debug manifest. It lets local QA inject text after the vendor-ASR stage
 * without changing the release attack surface.
 */
class VoiceDebugReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ACTION_SUBMIT_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                VoiceGatewayService.submitTextForTest(text)
            }
            ACTION_CANCEL -> VoiceGatewayService.cancelAssistantForTest()
            ACTION_CONFIGURE ->
                runCatching {
                    AssistantConfigStore(context).save(
                        baseUrl = intent.getStringExtra(EXTRA_BASE_URL).orEmpty(),
                        apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty(),
                    )
                }
            ACTION_PRESS_VOICE -> {
                val down = intent.getBooleanExtra(EXTRA_DOWN, false)
                VoiceGatewayService.pressVoiceKeyForTest(down)
            }
            ACTION_INJECT_PCM ->
                Thread(
                    {
                        runCatching {
                            val clear = intent.getBooleanExtra(EXTRA_CLEAR_PCM, false)
                            val pcm =
                                if (clear) {
                                    null
                                } else {
                                    context.assets
                                        .open(QA_PCM_ASSET)
                                        .use { it.readBytes() }
                                }
                            val recognizer = SystemDictationRecognizerFactory.create(context)
                            recognizer.javaClass
                                .getMethod("useInjectedPcmForTest", ByteArray::class.java)
                                .invoke(recognizer, pcm)
                            Log.i(TAG, "qa pcm ${if (pcm == null) "cleared" else "injected bytes=${pcm.size}"}")
                        }.onFailure { error ->
                            Log.w(TAG, "qa pcm injection failed: ${error.javaClass.simpleName}")
                        }
                    },
                    "voice-helper-qa-pcm",
                ).start()
        }
    }

    companion object {
        const val ACTION_SUBMIT_TEXT = "com.dongpiandi.voicehelper.DEBUG_SUBMIT_TEXT"
        const val ACTION_CANCEL = "com.dongpiandi.voicehelper.DEBUG_CANCEL"
        const val ACTION_CONFIGURE = "com.dongpiandi.voicehelper.DEBUG_CONFIGURE"
        const val ACTION_PRESS_VOICE = "com.dongpiandi.voicehelper.DEBUG_PRESS_VOICE"
        const val ACTION_INJECT_PCM = "com.dongpiandi.voicehelper.DEBUG_INJECT_PCM"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DOWN = "down"
        const val EXTRA_CLEAR_PCM = "clear_pcm"
        private const val TAG = "VoiceHelperDebug"
        private const val QA_PCM_ASSET = "dictation_zh_16k.pcm"
    }
}
