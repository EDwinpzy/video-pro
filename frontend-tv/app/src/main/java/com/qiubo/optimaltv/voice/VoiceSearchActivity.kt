package com.qiubo.optimaltv.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.qiubo.optimaltv.MainActivity

/**
 * Signature-protected bridge used by the global voice helper.
 *
 * The bridge only accepts a search phrase. It cannot open a detail or player route directly.
 */
class VoiceSearchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = VoiceSearchContract.requestFromIntent(intent)
        android.util.Log.i(
            "VoiceSearchEntry",
            "onCreate action=${intent?.action} queryChars=${request?.query?.length ?: 0}",
        )
        if (request != null) {
            VoiceSearchBus.submit(request)
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                },
            )
        }
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val request = VoiceSearchContract.requestFromIntent(intent)
        android.util.Log.i(
            "VoiceSearchEntry",
            "onNewIntent action=${intent.action} queryChars=${request?.query?.length ?: 0}",
        )
        if (request != null) {
            VoiceSearchBus.submit(request)
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                },
            )
        }
        finish()
    }
}
