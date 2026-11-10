package com.qiubo.optimaltv.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.qiubo.optimaltv.lifecycle.AppLifecycleManager

/**
 * In-process voice-search bridge for a Video Pro task that is already visible.
 *
 * Android may silently reject a helper-owned Activity start while Video Pro is
 * foreground. The running app can still receive a signature-protected broadcast
 * and submit the query to its existing navigation pipeline.
 */
class VoiceSearchReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val request = VoiceSearchContract.requestFromIntent(intent) ?: return
        android.util.Log.i(
            "VoiceSearchEntry",
            "receiver action=${intent.action} queryChars=${request.query.length} " +
                "foreground=${AppLifecycleManager.isForeground}",
        )
        if (!AppLifecycleManager.isForeground) return
        VoiceSearchBus.submit(request)
    }
}
