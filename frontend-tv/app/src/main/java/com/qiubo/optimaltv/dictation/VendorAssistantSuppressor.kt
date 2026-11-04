package com.qiubo.optimaltv.dictation

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.qiubo.optimaltv.BuildConfig

/**
 * The projector's preinstalled accessibility service also sees the voice key and asks its factory
 * assistant to start. A normal broadcast cannot be cancelled, so stop that second session as soon
 * as it appears. "STOP" clears the factory recording/NLP path; the dialog and TTS actions prevent
 * either the assistant window or a spoken answer from surviving the race.
 */
internal class VendorAssistantSuppressor(
    context: Context,
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayList<Runnable>()

    private val killFactorySession =
        Runnable {
            send("STOP")
            send("TTS_CANCEL")
            send("DISMISS_DIALOG")
            send("HIDE_BLACKBG")
        }

    fun begin() {
        clearScheduled()
        killFactorySession.run()
        schedule(ACTIVE_PRESS_DELAYS_MS)
    }

    fun finish() {
        clearScheduled()
        killFactorySession.run()
        schedule(RELEASE_DELAYS_MS)
    }

    fun close() {
        clearScheduled()
        main.removeCallbacksAndMessages(null)
    }

    private fun schedule(delays: LongArray) {
        for (delayMs in delays) {
            val action =
                Runnable {
                    killFactorySession.run()
                }
            pending += action
            main.postDelayed(action, delayMs)
        }
    }

    private fun clearScheduled() {
        pending.forEach(main::removeCallbacks)
        pending.clear()
    }

    private fun send(operation: String) {
        if (!isVendorInstalled()) return
        val intent =
            Intent(ACTION_AI_VOICE)
                .setPackage(VENDOR_PACKAGE)
                .putExtra(EXTRA_OPERATE, operation)
        runCatching {
            context.sendBroadcast(intent)
        }.onFailure { error ->
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Could not send $operation: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun isVendorInstalled(): Boolean =
        runCatching {
            context.packageManager.getApplicationInfo(VENDOR_PACKAGE, 0)
            true
        }.getOrDefault(false)

    private companion object {
        const val VENDOR_PACKAGE = "com.jmgo.ai.voice"
        const val ACTION_AI_VOICE = "com.jmgo.action.AI_VOICE"
        const val EXTRA_OPERATE = "operate"
        const val TAG = "VendorVoice"
        val ACTIVE_PRESS_DELAYS_MS = longArrayOf(40L, 120L, 260L, 520L, 1_000L, 1_600L)
        val RELEASE_DELAYS_MS = longArrayOf(120L, 360L, 800L, 1_400L)
    }
}
