package com.qiubo.optimaltv.dictation

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.qiubo.optimaltv.ui.search.SearchDebugBus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only, UI-free control surface for the external system-dictation smoke check. Android skips
 * manifest broadcasts while another app owns the foreground editor, so adb calls this provider.
 */
class SystemDictationDebugProvider : ContentProvider() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle =
        when (method) {
            METHOD_INSTALL -> {
                SystemDictationTestHooks.installTranscripts(FIRST_TRANSCRIPT, SECOND_TRANSCRIPT)
                Bundle().apply { putBoolean(RESULT_OK, true) }
            }
            METHOD_INSTALL_VENDOR_PCM -> {
                val appContext = context
                if (appContext == null) {
                    Bundle().apply { putBoolean(RESULT_OK, false) }
                } else {
                    SystemDictationTestHooks.installVendorPcm(appContext, VENDOR_PCM_ASSET)
                    Bundle().apply { putBoolean(RESULT_OK, true) }
                }
            }
            METHOD_PRESS_VOICE -> pressVoice()
            METHOD_PRESS_DOWN -> pressVoice(down = true)
            METHOD_PRESS_UP -> pressVoice(down = false)
            METHOD_START_MIC_FOREGROUND ->
                Bundle().apply {
                    putBoolean(
                        RESULT_OK,
                        SystemDictationService.startMicrophoneForegroundForTest(),
                    )
                }
            METHOD_STOP_MIC_FOREGROUND -> {
                SystemDictationService.stopMicrophoneForegroundForTest()
                Bundle().apply { putBoolean(RESULT_OK, true) }
            }
            METHOD_ENABLE_ACCESSIBILITY -> enableAccessibility()
            METHOD_STATUS -> status()
            METHOD_RESET -> {
                SystemDictationTestHooks.resetRecognizer()
                Bundle().apply { putBoolean(RESULT_OK, true) }
            }
            else -> Bundle().apply { putBoolean(RESULT_OK, false) }
        }

    private fun pressVoice(down: Boolean): Bundle {
        val completed = CountDownLatch(1)
        val accepted = AtomicBoolean(false)
        main.post {
            accepted.set(SystemDictationService.pressVoiceKeyForTest(down))
            completed.countDown()
        }
        completed.await(2, TimeUnit.SECONDS)
        return status().apply { putBoolean(RESULT_OK, accepted.get()) }
    }

    private fun pressVoice(): Bundle {
        val completed = CountDownLatch(1)
        val accepted = AtomicBoolean(false)
        main.post {
            accepted.set(SystemDictationService.pressVoiceKeyForTest(down = true))
            completed.countDown()
            if (accepted.get()) {
                main.postDelayed(
                    { SystemDictationService.pressVoiceKeyForTest(down = false) },
                    250L,
                )
            }
        }
        completed.await(2, TimeUnit.SECONDS)
        return status().apply { putBoolean(RESULT_OK, accepted.get()) }
    }

    private fun status(): Bundle =
        Bundle().apply {
            putBoolean(
                RESULT_EDITOR,
                SystemDictationService.editorAttachedForTest(),
            )
            putBoolean(
                RESULT_KEYBOARD,
                SystemDictationService.keyboardVisibleForTest(),
            )
            putBoolean(
                RESULT_SEARCH_VOICE,
                SystemDictationHostEditorRegistry.hasActiveEditor(),
            )
            putString(RESULT_SEARCH_QUERY, SearchDebugBus.currentQuery)
        }

    private fun enableAccessibility(): Bundle {
        val appContext = context ?: return Bundle().apply { putBoolean(RESULT_OK, false) }
        val request = SystemDictationAccess.requestEnable(appContext)
        if (request == SystemDictationAccess.EnableRequest.VENDOR_REQUESTED) {
            for (attempt in 0 until 20) {
                if (SystemDictationAccess.isEnabled(appContext)) break
                Thread.sleep(300)
            }
        }
        return status().apply {
            putBoolean(RESULT_OK, SystemDictationAccess.isEnabled(appContext))
            putString(RESULT_ENABLE_REQUEST, request.name)
        }
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val METHOD_INSTALL = "install"
        const val METHOD_INSTALL_VENDOR_PCM = "installVendorPcm"
        const val METHOD_PRESS_VOICE = "pressVoice"
        const val METHOD_PRESS_DOWN = "pressVoiceDown"
        const val METHOD_PRESS_UP = "pressVoiceUp"
        const val METHOD_START_MIC_FOREGROUND = "startMicForeground"
        const val METHOD_STOP_MIC_FOREGROUND = "stopMicForeground"
        const val METHOD_ENABLE_ACCESSIBILITY = "enableAccessibility"
        const val METHOD_STATUS = "status"
        const val METHOD_RESET = "reset"
        const val RESULT_OK = "ok"
        const val RESULT_EDITOR = "editor"
        const val RESULT_KEYBOARD = "keyboard"
        const val RESULT_SEARCH_VOICE = "searchVoice"
        const val RESULT_SEARCH_QUERY = "searchQuery"
        const val RESULT_ENABLE_REQUEST = "enableRequest"
        const val FIRST_TRANSCRIPT = "第一句"
        const val SECOND_TRANSCRIPT = "第二句"
        const val VENDOR_PCM_ASSET = "dictation_zh_16k.pcm"
    }
}

