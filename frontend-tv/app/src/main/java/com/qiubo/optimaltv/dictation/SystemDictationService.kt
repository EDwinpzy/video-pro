package com.qiubo.optimaltv.dictation

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.TextAttribute
import com.qiubo.optimaltv.R
import java.util.Locale

/**
 * System-wide dictation while another app owns a focused editor. It never reads window content and
 * accepts both the filtered key stream and the S621 remote's vendor voice broadcast.
 */
class SystemDictationService : AccessibilityService() {
    private var input: SystemDictationInput? = null
    private var controller: SystemDictationController? = null
    private var vendorKeyRouter: VendorKeyRouter? = null
    private var ownsBackPress = false
    private var microphoneForeground = false
    private val main = Handler(Looper.getMainLooper())
    private val syncVendorRoute =
        Runnable {
            syncVendorRouteNow()
        }
    private val vendorVoiceReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action != ACTION_VENDOR_VOICE) return
                val operate =
                    intent.getStringExtra(EXTRA_VENDOR_OPERATE)
                        .orEmpty()
                        .uppercase(Locale.US)
                Log.i(TAG, "vendor voice operate=$operate")
                when (operate) {
                    "NEAR_START", "START" -> forwardVoiceAction(down = true)
                    "NEAR_STOP", "STOP" -> forwardVoiceAction(down = false)
                }
            }
        }
    private var vendorVoiceRegistered = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (Build.VERSION.SDK_INT < 33) {
            runCatching { disableSelf() }
            return
        }
        instance = this
        vendorKeyRouter =
            VendorKeyRouter(this).also { router ->
                router.restoreInterruptedState()
            }
        Log.i(TAG, "connected flags=${serviceInfo.flags}")
        configureServiceInfo()
        Log.i(TAG, "configured flags=${serviceInfo.flags}")
        if (controller == null) {
            controller = SystemDictationController(
                context = this,
                editorSession = ::activeDictationEditor,
                commit = ::commitDictation,
                keyboardVisible = ::dictationInputAvailable,
                beginMicrophoneForeground = ::beginMicrophoneForeground,
                endMicrophoneForeground = ::endMicrophoneForeground,
            )
        }
        registerVendorVoiceReceiver()
        SystemDictationRecognizerFactory.prewarm(this)
        scheduleVendorRouteSync()
    }

    @android.annotation.TargetApi(33)
    override fun onCreateInputMethod(): InputMethod =
        SystemDictationInput(this).also {
            input = it
            scheduleVendorRouteSync()
        }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        scheduleVendorRouteSync()
    }

    override fun onInterrupt() {
        vendorKeyRouter?.restoreFactoryKeyService()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        Log.i(
            TAG,
            "key=${event.keyCode} scan=${event.scanCode} action=${event.action} " +
                "keyboard=${hasVisibleInputMethod()} windows=${windowSignature()}",
        )
        val dictation = controller ?: return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            when {
                event.action == KeyEvent.ACTION_DOWN && (ownsBackPress || dictation.active) -> {
                    ownsBackPress = true
                    dictation.cancel()
                    return true
                }
                event.action == KeyEvent.ACTION_UP && ownsBackPress -> {
                    ownsBackPress = false
                    return true
                }
            }
        }
        return dictation.onKeyEvent(event)
    }

    internal fun onEditorStateChanged() {
        controller?.onEditorChanged()
        scheduleVendorRouteSync()
    }

    private fun forwardVoiceAction(down: Boolean) {
        val now = SystemClock.uptimeMillis()
        val accepted =
            controller?.onKeyEvent(
                KeyEvent(
                    now,
                    now,
                    if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_VOICE_ASSIST,
                    0,
                ),
            ) == true
        Log.i(TAG, "vendor voice forwarded down=$down accepted=$accepted")
    }

    private fun beginMicrophoneForeground(): Boolean {
        if (microphoneForeground) return true
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    MICROPHONE_CHANNEL_ID,
                    getString(R.string.system_dictation_service_label),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    description = getString(R.string.system_dictation_service_description)
                },
            )
        }
        val notification =
            Notification.Builder(this, MICROPHONE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tvos_kbd_mic)
                .setContentTitle(getString(R.string.system_dictation_service_label))
                .setContentText(getString(R.string.system_dictation_service_description))
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        startForeground(
            MICROPHONE_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        microphoneForeground = true
        Log.i(TAG, "microphone foreground started")
        return true
    }

    private fun endMicrophoneForeground() {
        if (!microphoneForeground) return
        microphoneForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "microphone foreground stopped")
    }

    private fun registerVendorVoiceReceiver() {
        if (vendorVoiceRegistered) return
        val filter = IntentFilter(ACTION_VENDOR_VOICE)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(vendorVoiceReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(vendorVoiceReceiver, filter)
            }
            vendorVoiceRegistered = true
            Log.i(TAG, "vendor voice receiver registered")
        }.onFailure { error ->
            Log.w(TAG, "vendor voice receiver failed: ${error.javaClass.simpleName}")
        }
    }

    private fun unregisterVendorVoiceReceiver() {
        if (!vendorVoiceRegistered) return
        runCatching { unregisterReceiver(vendorVoiceReceiver) }
        vendorVoiceRegistered = false
    }

    internal fun hasVisibleInputMethod(): Boolean =
        runCatching {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }.getOrDefault(false)

    private fun scheduleVendorRouteSync() {
        main.removeCallbacks(syncVendorRoute)
        main.postDelayed(syncVendorRoute, VENDOR_ROUTE_DEBOUNCE_MS)
    }

    private fun syncVendorRouteNow() {
        val router = vendorKeyRouter ?: return
        val microphoneGranted =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val dictationOnly =
            VendorKeyRoutingPolicy.dictationOnly(
                editorAttached = activeDictationEditor() != null,
                inputMethodVisible = dictationInputAvailable(),
                microphoneGranted = microphoneGranted,
            )
        router.setDictationOnly(dictationOnly)
    }

    private fun activeDictationEditor(): Long? =
        input?.activeEditor() ?: SystemDictationHostEditorRegistry.activeToken()

    private fun commitDictation(
        editor: Long,
        text: String,
    ): Boolean =
        input?.commit(editor, text) == true ||
            SystemDictationHostEditorRegistry.commit(editor, text)

    private fun dictationInputAvailable(): Boolean =
        hasVisibleInputMethod() || SystemDictationHostEditorRegistry.hasActiveEditor()

    private fun windowSignature(): String =
        runCatching {
            windows.joinToString(separator = ",") { "${it.type}:${it.isActive}" }
        }.getOrElse { "error:${it.javaClass.simpleName}" }

    override fun onDestroy() {
        if (instance === this) instance = null
        main.removeCallbacksAndMessages(null)
        endMicrophoneForeground()
        vendorKeyRouter?.restoreFactoryKeyService()
        vendorKeyRouter?.close()
        vendorKeyRouter = null
        unregisterVendorVoiceReceiver()
        ownsBackPress = false
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
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        if (flags != current.flags) {
            current.flags = flags
            serviceInfo = current
        }
    }

    internal companion object {
        private const val TAG = "SystemDictation"
        private const val ACTION_VENDOR_VOICE = "com.jmgo.action.AI_VOICE"
        private const val EXTRA_VENDOR_OPERATE = "operate"
        private const val VENDOR_ROUTE_DEBOUNCE_MS = 120L
        private const val MICROPHONE_CHANNEL_ID = "system_dictation_microphone"
        private const val MICROPHONE_NOTIFICATION_ID = 609

        @Volatile
        private var instance: SystemDictationService? = null

        internal fun runningForTest(): Boolean = instance != null

        internal fun editorAttachedForTest(): Boolean =
            instance?.activeDictationEditor() != null

        internal fun keyboardVisibleForTest(): Boolean =
            instance?.dictationInputAvailable() == true

        internal fun notifyHostEditorStateChanged() {
            instance?.onEditorStateChanged()
        }

        internal fun startMicrophoneForegroundForTest(): Boolean =
            instance?.beginMicrophoneForeground() == true

        internal fun stopMicrophoneForegroundForTest() {
            instance?.endMicrophoneForeground()
        }

        internal fun pressVoiceKeyForTest(down: Boolean): Boolean {
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

    fun commit(editor: Long, text: String): Boolean {
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

