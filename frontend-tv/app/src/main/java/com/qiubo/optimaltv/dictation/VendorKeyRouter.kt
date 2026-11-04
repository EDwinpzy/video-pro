package com.qiubo.optimaltv.dictation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

internal object VendorKeyRoutingPolicy {
    fun dictationOnly(
        editorAttached: Boolean,
        inputMethodVisible: Boolean,
        microphoneGranted: Boolean,
    ): Boolean = editorAttached && inputMethodVisible && microphoneGranted
}

/**
 * The S621 firmware's middleware accessibility service turns the voice key into an implicit
 * broadcast before every other accessibility service sees it. While a real editor and IME are
 * visible, temporarily remove only that key service so the factory assistant cannot start and the
 * voice key reaches dictation without competing for the microphone.
 */
internal class VendorKeyRouter(
    context: Context,
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val preferences =
        this.context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private var dictationOnly = false
    private var ownsDisable = false
    private var stateGeneration = 0

    fun restoreInterruptedState() {
        if (!preferences.getBoolean(KEY_OWNED_DISABLE, false)) return
        ownsDisable = true
        Log.i(TAG, "restoring factory key service after an interrupted session")
        setFactoryKeyServiceEnabled(enabled = true)
    }

    fun setDictationOnly(enabled: Boolean) {
        if (enabled == dictationOnly) return
        if (enabled) {
            if (!isFactoryKeyServiceEnabled()) {
                dictationOnly = true
                return
            }
            ownsDisable = true
            preferences.edit().putBoolean(KEY_OWNED_DISABLE, true).apply()
            setFactoryKeyServiceEnabled(enabled = false)
        } else {
            if (ownsDisable) setFactoryKeyServiceEnabled(enabled = true)
        }
        dictationOnly = enabled
    }

    fun restoreFactoryKeyService() {
        if (!ownsDisable) return
        setFactoryKeyServiceEnabled(enabled = true)
        dictationOnly = false
    }

    fun close() {
        main.removeCallbacksAndMessages(null)
    }

    private fun setFactoryKeyServiceEnabled(enabled: Boolean) {
        val generation = ++stateGeneration
        sendFactoryStop()
        send(enabled)
        verify(enabled, generation, remainingRetries = 1)
    }

    private fun verify(
        enabled: Boolean,
        generation: Int,
        remainingRetries: Int,
    ) {
        main.postDelayed(
            {
                if (generation != stateGeneration) return@postDelayed
                if (isFactoryKeyServiceEnabled() == enabled) {
                    if (enabled) {
                        ownsDisable = false
                        preferences.edit().putBoolean(KEY_OWNED_DISABLE, false).apply()
                    }
                    return@postDelayed
                }
                if (remainingRetries > 0) {
                    send(enabled)
                    verify(enabled, generation, remainingRetries - 1)
                } else {
                    Log.w(TAG, "factory key service state did not become enabled=$enabled")
                }
            },
            VERIFY_DELAY_MS,
        )
    }

    private fun send(enabled: Boolean) {
        if (!isVendorInstalled()) return
        val intent =
            Intent(ACTION_REQUEST_VENDOR_ACCESSIBILITY)
                .setPackage(VENDOR_PACKAGE)
                .putExtra(EXTRA_COMPONENT, FACTORY_KEY_COMPONENT)
                .putExtra(EXTRA_ENABLED, enabled)
        runCatching {
            context.sendBroadcast(intent)
        }.onFailure { error ->
            Log.w(TAG, "could not set factory key service enabled=$enabled: ${error.javaClass.simpleName}")
        }
    }

    private fun sendFactoryStop() {
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_AI_VOICE)
                    .setPackage(VENDOR_VOICE_PACKAGE)
                    .putExtra(EXTRA_OPERATE, "STOP"),
            )
        }
    }

    private fun isFactoryKeyServiceEnabled(): Boolean =
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
            .orEmpty()
            .split(':')
            .any { it == FACTORY_KEY_COMPONENT }

    private fun isVendorInstalled(): Boolean =
        runCatching {
            context.packageManager.getApplicationInfo(VENDOR_PACKAGE, 0)
            true
        }.getOrDefault(false)

    private companion object {
        const val TAG = "VendorKeyRouter"
        const val VENDOR_PACKAGE = "com.jmgo.hippo"
        const val VENDOR_VOICE_PACKAGE = "com.jmgo.ai.voice"
        const val FACTORY_KEY_COMPONENT =
            "com.jmgo.hippo/com.jmgo.middleware.service.JmgoKeyAccessibilityService"
        const val ACTION_REQUEST_VENDOR_ACCESSIBILITY =
            "action.jmgo.request.accessibility.service"
        const val ACTION_AI_VOICE = "com.jmgo.action.AI_VOICE"
        const val EXTRA_COMPONENT = "compontentNameStr"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_OPERATE = "operate"
        const val PREFERENCES_NAME = "vendor-key-router"
        const val KEY_OWNED_DISABLE = "owned-disable"
        const val VERIFY_DELAY_MS = 350L
    }
}
