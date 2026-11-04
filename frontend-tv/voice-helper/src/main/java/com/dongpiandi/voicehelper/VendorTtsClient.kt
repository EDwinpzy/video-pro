package com.dongpiandi.voicehelper

import android.content.Context
import android.content.Intent
import android.util.Log

internal class VendorTtsClient(
    context: Context,
) {
    private val context = context.applicationContext

    fun speak(text: String) {
        if (text.isBlank() || !isVendorInstalled()) return
        send(ACTION_TTS_PLAY, extra = text)
    }

    fun cancel() {
        if (!isVendorInstalled()) return
        send(ACTION_TTS_CANCEL)
    }

    fun stopFactoryReplyAndDismiss() {
        if (!isVendorInstalled()) return
        send(ACTION_TTS_CANCEL)
        send(ACTION_DISMISS_DIALOG)
        send(ACTION_HIDE_BLACK_BG)
    }

    fun stopFactorySpeechOnly() {
        if (!isVendorInstalled()) return
        send(ACTION_TTS_CANCEL)
    }

    private fun send(
        action: String,
        extra: String? = null,
    ) {
        val intent =
            Intent(action)
                .setPackage(VENDOR_PACKAGE)
                .apply {
                    if (extra != null) putExtra(EXTRA_DATA, extra)
                }
        runCatching { context.sendBroadcast(intent) }
            .onFailure { error ->
                Log.w(TAG, "vendor TTS action failed: ${error.javaClass.simpleName}")
            }
    }

    private fun isVendorInstalled(): Boolean =
        runCatching {
            context.packageManager.getApplicationInfo(VENDOR_PACKAGE, 0)
            true
        }.getOrDefault(false)

    private companion object {
        const val TAG = "VoiceHelperTts"
        const val VENDOR_PACKAGE = "com.jmgo.ai.voice"
        const val ACTION_TTS_PLAY = "TTS_PLAY"
        const val ACTION_TTS_CANCEL = "TTS_CANCEL"
        const val ACTION_DISMISS_DIALOG = "DISMISS_DIALOG"
        const val ACTION_HIDE_BLACK_BG = "HIDE_BLACKBG"
        const val EXTRA_DATA = "data"
    }
}
