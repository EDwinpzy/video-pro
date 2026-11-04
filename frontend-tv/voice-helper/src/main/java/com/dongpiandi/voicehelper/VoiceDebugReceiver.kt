package com.dongpiandi.voicehelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        }
    }

    companion object {
        const val ACTION_SUBMIT_TEXT = "com.dongpiandi.voicehelper.DEBUG_SUBMIT_TEXT"
        const val ACTION_CANCEL = "com.dongpiandi.voicehelper.DEBUG_CANCEL"
        const val ACTION_CONFIGURE = "com.dongpiandi.voicehelper.DEBUG_CONFIGURE"
        const val ACTION_PRESS_VOICE = "com.dongpiandi.voicehelper.DEBUG_PRESS_VOICE"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DOWN = "down"
    }
}
