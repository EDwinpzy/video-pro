package app.jellystack.tv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.TextAttribute

/**
 * System-wide dictation while another app owns a focused editor. It never reads window content and
 * only asks for the filtered key stream while the accessibility input method is active.
 */
class SystemDictationService : AccessibilityService() {
    private var input: SystemDictationInput? = null
    private var controller: SystemDictationController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (Build.VERSION.SDK_INT < 33) {
            runCatching { disableSelf() }
            return
        }
        instance = this
        if (BuildConfig.DEBUG) Log.d(TAG, "connected flags=${serviceInfo.flags}")
        configureServiceInfo()
        if (BuildConfig.DEBUG) Log.d(TAG, "configured flags=${serviceInfo.flags}")
        if (controller == null) {
            controller =
                SystemDictationController(
                    context = this,
                    editorSession = { input?.activeEditor() },
                    commit = { editor, text -> input?.commit(editor, text) == true },
                )
        }
    }

    @android.annotation.TargetApi(33)
    override fun onCreateInputMethod(): InputMethod = SystemDictationInput(this).also { input = it }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "key=${event.keyCode} scan=${event.scanCode} action=${event.action}")
        }
        val dictation = controller ?: return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK && dictation.active) {
            dictation.cancel()
            return false
        }
        return dictation.onKeyEvent(event)
    }

    internal fun onEditorStateChanged() {
        controller?.onEditorChanged()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        controller?.close()
        controller = null
        input = null
        super.onDestroy()
    }

    private fun configureServiceInfo() {
        if (Build.VERSION.SDK_INT < 33) return
        val current = serviceInfo ?: return
        val flags =
            current.flags or
                AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        if (flags != current.flags) {
            current.flags = flags
            serviceInfo = current
        }
    }

    internal companion object {
        private const val TAG = "SystemDictation"

        @Volatile
        private var instance: SystemDictationService? = null

        internal fun runningForTest(): Boolean = instance != null

        internal fun editorAttachedForTest(): Boolean = instance?.input?.activeEditor() != null

        internal fun pressVoiceKeyForTest(down: Boolean): Boolean {
            check(BuildConfig.DEBUG)
            val service = instance ?: return false
            val now = android.os.SystemClock.uptimeMillis()
            return service.onKeyEvent(
                KeyEvent(
                    now,
                    now,
                    if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_VOICE_ASSIST,
                    0,
                ),
            )
        }
    }
}

@android.annotation.TargetApi(33)
private class SystemDictationInput(
    private val service: SystemDictationService,
) : InputMethod(service) {
    private var generation = 0L
    private var started = false

    fun activeEditor(): Long? =
        generation.takeIf {
            started &&
                currentInputStarted &&
                currentInputConnection != null &&
                currentInputEditorInfo?.inputType != android.text.InputType.TYPE_NULL
        }

    fun commit(
        editor: Long,
        text: String,
    ): Boolean {
        if (editor != activeEditor()) return false
        val connection = currentInputConnection ?: return false
        connection.commitText(text, 1, null as TextAttribute?)
        return true
    }

    override fun onStartInput(
        info: EditorInfo,
        restarting: Boolean,
    ) {
        generation++
        started = true
        super.onStartInput(info, restarting)
        service.onEditorStateChanged()
    }

    override fun onFinishInput() {
        started = false
        generation++
        super.onFinishInput()
        service.onEditorStateChanged()
    }
}
