package com.dongpiandi.voicehelper

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
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import com.dongpiandi.voice.core.SystemDictationRecognizerFactory
import com.dongpiandi.voice.core.VendorKeyRouter
import com.dongpiandi.voicehelper.assistant.VendorUiTranscriptCollector
import java.util.Locale

class VoiceGatewayService : AccessibilityService() {
    private var input: VoiceInput? = null
    private var dictation: VoiceDictationController? = null
    private var vendorKeyRouter: VendorKeyRouter? = null
    private var answerOverlay: VoiceAnswerOverlay? = null
    private var transcriptCollector: VendorUiTranscriptCollector? = null
    private var assistant: FactoryVoiceAssistantController? = null
    private var microphoneForeground = false
    private var ownsBackPress = false
    private var dictationPrewarmed = false
    private var vendorReceiverRegistered = false
    private val main = Handler(Looper.getMainLooper())
    private val syncVendorRoute = Runnable { syncVendorRouteNow() }

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
                when (operate) {
                    "NEAR_START", "START" -> {
                        if (isFactoryAssistantMode()) assistant?.onFactoryStart()
                    }
                    "NEAR_STOP", "STOP" -> {
                        if (isFactoryAssistantMode() && dictation?.active != true) {
                            assistant?.onFactoryStop()
                        }
                    }
                }
            }
        }

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
        configureServiceInfo()
        val overlay = VoiceAnswerOverlay(this)
        val collector = VendorUiTranscriptCollector(this)
        answerOverlay = overlay
        transcriptCollector = collector
        assistant = FactoryVoiceAssistantController(this, overlay, collector)
        dictation =
            VoiceDictationController(
                context = this,
                editorSession = ::activeEditor,
                commit = ::commitDictation,
                keyboardVisible = ::hasVisibleInputMethod,
                beginMicrophoneForeground = ::beginMicrophoneForeground,
                endMicrophoneForeground = ::endMicrophoneForeground,
            )
        registerVendorVoiceReceiver()
        scheduleVendorRouteSync()
        Log.i(TAG, "connected flags=${serviceInfo?.flags ?: 0}")
    }

    @android.annotation.TargetApi(33)
    override fun onCreateInputMethod(): InputMethod =
        VoiceInput(this).also {
            input = it
            scheduleVendorRouteSync()
        }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        transcriptCollector?.onAccessibilityEvent(event)
        if (
            assistant?.active == true &&
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            transcriptCollector?.refreshAndGet()
        }
        scheduleVendorRouteSync()
    }

    override fun onInterrupt() {
        assistant?.cancel()
        vendorKeyRouter?.restoreFactoryKeyService()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        Log.i(
            TAG,
            "key=${event.keyCode} scan=${event.scanCode} action=${event.action} " +
                "ime=${hasVisibleInputMethod()} windows=${windowSignature()}",
        )
        val assistantController = assistant
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            when {
                event.action == KeyEvent.ACTION_DOWN &&
                    (ownsBackPress || assistantController?.hasVisibleCard == true) -> {
                    ownsBackPress = true
                    assistantController?.cancel()
                    return true
                }
                event.action == KeyEvent.ACTION_UP && ownsBackPress -> {
                    ownsBackPress = false
                    return true
                }
            }
        }

        syncVendorRouteNow()
        return dictation?.onKeyEvent(event) == true
    }

    internal fun onEditorStateChanged() {
        dictation?.onEditorChanged()
        syncVendorRouteNow()
        scheduleVendorRouteSync()
    }

    private fun activeEditor(): Long? = input?.activeEditor()

    private fun commitDictation(
        editor: Long,
        text: String,
    ): Boolean = input?.commit(editor, text) == true

    private fun hasVisibleInputMethod(): Boolean =
        runCatching {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }.getOrDefault(false)

    private fun isFactoryAssistantMode(): Boolean =
        VoiceModePolicy.resolve(
            editorAttached = activeEditor() != null,
            inputMethodVisible = hasVisibleInputMethod(),
        ) == VoiceMode.FACTORY_ASSISTANT

    private fun scheduleVendorRouteSync() {
        main.removeCallbacks(syncVendorRoute)
        main.postDelayed(syncVendorRoute, VENDOR_ROUTE_DEBOUNCE_MS)
    }

    private fun syncVendorRouteNow() {
        val router = vendorKeyRouter ?: return
        val dictationMode =
            VoiceModePolicy.shouldDisableFactoryKeyService(
                editorAttached = activeEditor() != null,
                inputMethodVisible = hasVisibleInputMethod(),
            )
        router.setDictationOnly(dictationMode)
        if (dictationMode && !dictationPrewarmed) {
            dictationPrewarmed = true
            SystemDictationRecognizerFactory.prewarm(this)
        }
    }

    private fun beginMicrophoneForeground(): Boolean {
        if (microphoneForeground) return true
        val manager = getSystemService(NotificationManager::class.java) ?: return false
        manager.createNotificationChannel(
            NotificationChannel(
                MICROPHONE_CHANNEL_ID,
                getString(R.string.microphone_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        val notification =
            Notification.Builder(this, MICROPHONE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_voice_helper)
                .setContentTitle(getString(R.string.microphone_notification_title))
                .setContentText(getString(R.string.microphone_notification_description))
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
        return true
    }

    private fun endMicrophoneForeground() {
        if (!microphoneForeground) return
        microphoneForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun registerVendorVoiceReceiver() {
        if (vendorReceiverRegistered) return
        runCatching {
            registerReceiver(
                vendorVoiceReceiver,
                IntentFilter(ACTION_VENDOR_VOICE),
                Context.RECEIVER_EXPORTED,
            )
            vendorReceiverRegistered = true
        }.onFailure { error ->
            Log.w(TAG, "vendor receiver registration failed: ${error.javaClass.simpleName}")
        }
    }

    private fun unregisterVendorVoiceReceiver() {
        if (!vendorReceiverRegistered) return
        runCatching { unregisterReceiver(vendorVoiceReceiver) }
        vendorReceiverRegistered = false
    }

    private fun windowSignature(): String =
        runCatching {
            windows.joinToString(separator = ",") { "${it.type}:${it.isActive}" }
        }.getOrElse { "error:${it.javaClass.simpleName}" }

    private fun configureServiceInfo() {
        val current = serviceInfo ?: return
        current.flags =
            current.flags or
                AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = current
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        main.removeCallbacksAndMessages(null)
        endMicrophoneForeground()
        assistant?.close()
        assistant = null
        answerOverlay?.dismiss()
        answerOverlay = null
        transcriptCollector = null
        dictation?.close()
        dictation = null
        vendorKeyRouter?.restoreFactoryKeyService()
        vendorKeyRouter?.close()
        vendorKeyRouter = null
        unregisterVendorVoiceReceiver()
        input = null
        ownsBackPress = false
        super.onDestroy()
    }

    internal companion object {
        private const val TAG = "VoiceHelper"
        private const val ACTION_VENDOR_VOICE = "com.jmgo.action.AI_VOICE"
        private const val EXTRA_VENDOR_OPERATE = "operate"
        private const val VENDOR_ROUTE_DEBOUNCE_MS = 120L
        private const val MICROPHONE_CHANNEL_ID = "voice_helper_microphone"
        private const val MICROPHONE_NOTIFICATION_ID = 610

        @Volatile
        private var instance: VoiceGatewayService? = null

        internal fun submitTextForTest(text: String) {
            instance?.assistant?.submitTextForTest(text)
        }

        internal fun cancelAssistantForTest() {
            instance?.assistant?.cancel()
        }

        internal fun pressVoiceKeyForTest(down: Boolean): Boolean {
            val service = instance ?: return false
            val now = SystemClock.uptimeMillis()
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
private class VoiceInput(
    private val service: VoiceGatewayService,
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
