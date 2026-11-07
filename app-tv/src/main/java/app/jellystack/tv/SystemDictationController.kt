package app.jellystack.tv

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast

/**
 * Owns one press, one recognizer result, and one editor. It deliberately has no path to the
 * assistant, search Activity, LLM, or TTS.
 */
internal class SystemDictationController(
    private val context: Context,
    private val editorSession: () -> Long?,
    private val commit: (editor: Long, text: String) -> Boolean,
    private val recognizerFactory: () -> SystemDictationRecognizer = {
        SystemDictationRecognizerFactory.create(context)
    },
) {
    private val main = Handler(Looper.getMainLooper())
    private val session = SystemDictationSession()
    private var recognizer: SystemDictationRecognizer? = null
    private var pendingResult: String? = null
    private var resultReady = false
    private var ownsPress = false

    private val timeout =
        Runnable {
            if (session.state == SystemDictationSession.State.IDLE) return@Runnable
            fail(R.string.system_dictation_timeout)
        }

    val active: Boolean
        get() = session.state != SystemDictationSession.State.IDLE

    fun onEditorChanged() {
        if (active) cancel()
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isVoiceKey(event)) return false

        if (event.action == KeyEvent.ACTION_UP && ownsPress) {
            ownsPress = false
            if (event.isCanceled) cancel() else release()
            return true
        }

        val editor = editorSession() ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        ownsPress = true
        if (event.repeatCount != 0 || active) return true
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(R.string.system_dictation_permission_required)
            return true
        }

        val token = session.begin(editor) ?: return true
        pendingResult = null
        resultReady = false
        try {
            val engine = recognizerFactory()
            recognizer = engine
            engine.start(
                object : SystemDictationRecognizer.Listener {
                    override fun onPartial(text: String) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "partial length=${text.length}")
                        main.post {
                            if (token != session.generation) return@post
                            session.observe(token, text)
                        }
                    }

                    override fun onResult(text: String) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "result length=${text.length}")
                        main.post {
                            if (token != session.generation) return@post
                            session.observe(token, text)
                            pendingResult = text
                            resultReady = true
                            if (session.state == SystemDictationSession.State.WAITING_RESULT) finish(token)
                        }
                    }

                    override fun onError() {
                        if (BuildConfig.DEBUG) Log.d(TAG, "recognizer error")
                        main.post {
                            if (token == session.generation) fail(R.string.system_dictation_failed)
                        }
                    }
                },
            )
            main.postDelayed(timeout, MAX_RECORDING_MS)
        } catch (_: Throwable) {
            fail(R.string.system_dictation_engine_unavailable)
        }
        return true
    }

    fun cancel() {
        if (session.state == SystemDictationSession.State.IDLE && recognizer == null) return
        session.cancel()
        cleanup()
    }

    fun close() {
        session.cancel()
        cleanup()
        main.removeCallbacksAndMessages(null)
    }

    private fun release() {
        if (!session.release()) return
        main.removeCallbacks(timeout)
        if (resultReady) {
            finish(session.generation)
            return
        }
        val engine = recognizer
        if (engine == null) {
            fail(R.string.system_dictation_failed)
            return
        }
        try {
            engine.stop()
            main.postDelayed(timeout, MAX_RESULT_WAIT_MS)
        } catch (_: Throwable) {
            fail(R.string.system_dictation_failed)
        }
    }

    private fun finish(token: Int) {
        val editor = editorSession()
        val text = session.finish(token, pendingResult, editor)
        if (text == null) {
            cleanup()
            return
        }
        cleanup()
        if (editor != null) {
            val committed = commit(editor, text)
            if (BuildConfig.DEBUG) Log.d(TAG, "commit length=${text.length} accepted=$committed")
        }
    }

    private fun fail(message: Int) {
        cancel()
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun cleanup() {
        main.removeCallbacks(timeout)
        runCatching { recognizer?.cancel() }
        recognizer = null
        pendingResult = null
        resultReady = false
    }

    private fun isVoiceKey(event: KeyEvent): Boolean =
        event.keyCode == VENDOR_VOICE_KEY ||
            event.scanCode == VENDOR_VOICE_SCAN_CODE ||
            event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
            event.keyCode == KeyEvent.KEYCODE_ASSIST

    private companion object {
        const val VENDOR_VOICE_KEY = 609
        const val VENDOR_VOICE_SCAN_CODE = 192
        const val TAG = "SystemDictation"
        const val MAX_RECORDING_MS = 30_000L
        const val MAX_RESULT_WAIT_MS = 8_000L
    }
}
