package app.jellystack.tv

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
            METHOD_PRESS_VOICE -> pressVoice()
            METHOD_RESET -> {
                SystemDictationTestHooks.resetRecognizer()
                Bundle().apply { putBoolean(RESULT_OK, true) }
            }
            else -> Bundle().apply { putBoolean(RESULT_OK, false) }
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
        return Bundle().apply {
            putBoolean(RESULT_OK, accepted.get())
            putBoolean(
                RESULT_EDITOR,
                SystemDictationService.editorAttachedForTest(),
            )
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
        const val METHOD_PRESS_VOICE = "pressVoice"
        const val METHOD_RESET = "reset"
        const val RESULT_OK = "ok"
        const val RESULT_EDITOR = "editor"
        const val FIRST_TRANSCRIPT = "第一句"
        const val SECOND_TRANSCRIPT = "第二句"
    }
}
