package com.dongpiandi.voicehelper

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
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.TextAttribute
import com.dongpiandi.voice.core.SystemDictationRecognizerFactory
import com.dongpiandi.voice.core.VendorKeyRouter
import java.util.Locale

class VoiceGatewayService : AccessibilityService() {
    private var input: VoiceInput? = null
    private var dictation: VoiceDictationController? = null
    private var vendorKeyRouter: VendorKeyRouter? = null
    private var assistant: VoiceAssistantController? = null
    private var microphoneForeground = false
    private var enginePrewarmed = false
    private var vendorReceiverRegistered = false
    private var accessibilitySettingsObserver: ContentObserver? = null
    private var deactivatingForConflict = false
    private val voiceKeyRoute = VoiceKeyRouteLatch()
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
                    "NEAR_START", "START", "NEAR_STOP", "STOP" ->
                        assistant?.suppressUnexpectedFactorySession()
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
        if (deactivateIfConflictingDictationEnabled()) return
        configureServiceInfo()
        val overlay = VoiceAssistantOverlay(this)
        assistant =
            VoiceAssistantController(
                context = this,
                overlay = overlay,
                beginMicrophoneForeground = ::beginMicrophoneForeground,
                endMicrophoneForeground = ::endMicrophoneForeground,
            )
        dictation =
            VoiceDictationController(
                context = this,
                editorSession = ::activeEditor,
                commit = ::commitDictation,
                beginMicrophoneForeground = ::beginMicrophoneForeground,
                endMicrophoneForeground = ::endMicrophoneForeground,
            )
        registerVendorVoiceReceiver()
        registerAccessibilitySettingsObserver()
        syncVendorRouteNow()
        Log.i(TAG, "connected flags=${serviceInfo?.flags ?: 0}")
    }

    @android.annotation.TargetApi(33)
    override fun onCreateInputMethod(): InputMethod =
        VoiceInput(this).also {
            input = it
            scheduleVendorRouteSync()
        }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (deactivateIfConflictingDictationEnabled()) return
        if (
            event?.packageName?.toString() == VENDOR_VOICE_PACKAGE &&
            (
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            )
        ) {
            assistant?.onFactoryWindowSeen()
        }
        scheduleVendorRouteSync()
    }

    override fun onInterrupt() {
        voiceKeyRoute.clear()
        assistant?.cancel()
        dictation?.cancel()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        voiceKeyRoute.clear()
        vendorKeyRouter?.restoreFactoryKeyService()
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        if (deactivateIfConflictingDictationEnabled()) return false
        Log.i(
            TAG,
            "key=${event.keyCode} scan=${event.scanCode} action=${event.action} " +
                "ime=${hasVisibleInputMethod()} editorAttached=${activeEditor() != null} " +
                "windows=${windowSignature()}",
        )
        val assistantController = assistant

        syncVendorRouteNow()
        if (assistantController?.onNavigationKeyEvent(event) == true) return true
        if (!isVoiceKey(event)) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val existing = voiceKeyRoute.owner
                val editorAttached = activeEditor() != null
                val mode = VoiceModePolicy.resolve(editorAttached = editorAttached)
                val route =
                    existing ?: when (mode) {
                        VoiceMode.DICTATION -> VoiceRoute.DICTATION
                        VoiceMode.ASSISTANT -> VoiceRoute.ASSISTANT
                    }
                Log.i(
                    TAG,
                    "voice route=$route mode=$mode editorAttached=$editorAttached " +
                        "ime=${hasVisibleInputMethod()}",
                )
                val handled = dispatchVoiceKey(route, event)
                if (handled && existing == null) voiceKeyRoute.claim(route)
                return handled
            }
            KeyEvent.ACTION_UP -> {
                val route = voiceKeyRoute.owner ?: return false
                dispatchVoiceKey(route, event)
                voiceKeyRoute.clear()
                return true
            }
            KEY_ACTION_MULTIPLE -> {
                val route = voiceKeyRoute.owner ?: return false
                dispatchVoiceKey(route, event)
                return true
            }
            else -> {
                val route = voiceKeyRoute.owner ?: return false
                when (route) {
                    VoiceRoute.DICTATION -> dictation?.cancel()
                    VoiceRoute.ASSISTANT -> assistantController?.cancel()
                }
                voiceKeyRoute.clear()
                return true
            }
        }
    }

    internal fun onEditorStateChanged() {
        if (deactivateIfConflictingDictationEnabled()) return
        dictation?.onEditorChanged()
        assistant?.onEditorChanged()
        if (
            (voiceKeyRoute.owner == VoiceRoute.DICTATION && dictation?.active != true) ||
            (voiceKeyRoute.owner == VoiceRoute.ASSISTANT && assistant?.active != true)
        ) {
            voiceKeyRoute.clear()
        }
        syncVendorRouteNow()
        scheduleVendorRouteSync()
    }

    private fun dispatchVoiceKey(
        route: VoiceRoute,
        event: KeyEvent,
    ): Boolean =
        when (route) {
            VoiceRoute.DICTATION -> dictation?.onKeyEvent(event) == true
            VoiceRoute.ASSISTANT -> assistant?.onVoiceKeyEvent(event) == true
        }

    private fun isVoiceKey(event: KeyEvent): Boolean =
        event.keyCode == VENDOR_VOICE_KEY ||
            event.scanCode == VENDOR_VOICE_SCAN_CODE ||
            event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
            event.keyCode == KeyEvent.KEYCODE_ASSIST

    private fun activeEditor(): Long? = input?.activeEditor()

    private fun commitDictation(
        editor: Long,
        text: String,
    ): Boolean = input?.commit(editor, text) == true

    private fun hasVisibleInputMethod(): Boolean =
        runCatching {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }.getOrDefault(false)

    private fun scheduleVendorRouteSync() {
        main.removeCallbacks(syncVendorRoute)
        main.postDelayed(syncVendorRoute, VENDOR_ROUTE_DEBOUNCE_MS)
    }

    private fun syncVendorRouteNow() {
        if (deactivateIfConflictingDictationEnabled()) return
        val router = vendorKeyRouter ?: return
        router.setDictationOnly(true)
        if (!enginePrewarmed) {
            enginePrewarmed = true
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

    private fun registerAccessibilitySettingsObserver() {
        if (accessibilitySettingsObserver != null) return
        val observer =
            object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    scheduleVendorRouteSync()
                }

                override fun onChange(
                    selfChange: Boolean,
                    uri: android.net.Uri?,
                ) {
                    scheduleVendorRouteSync()
                }
            }
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                observer,
            )
            accessibilitySettingsObserver = observer
        }.onFailure { error ->
            Log.w(TAG, "accessibility settings observer failed: ${error.javaClass.simpleName}")
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

    private fun deactivateIfConflictingDictationEnabled(): Boolean {
        if (!VoiceHelperAccess.conflictingDictationEnabled(this)) return false
        if (!deactivatingForConflict) {
            deactivatingForConflict = true
            voiceKeyRoute.clear()
            assistant?.cancel()
            dictation?.cancel()
            vendorKeyRouter?.restoreFactoryKeyService()
            Log.w(
                TAG,
                "Video Pro dictation service is also enabled; disabling this helper to avoid duplicate keys",
            )
            main.post { disableSelf() }
        }
        return true
    }

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
        voiceKeyRoute.clear()
        endMicrophoneForeground()
        assistant?.close()
        assistant = null
        dictation?.close()
        dictation = null
        vendorKeyRouter?.restoreFactoryKeyService()
        vendorKeyRouter?.close()
        vendorKeyRouter = null
        unregisterVendorVoiceReceiver()
        accessibilitySettingsObserver?.let { observer ->
            runCatching { contentResolver.unregisterContentObserver(observer) }
        }
        accessibilitySettingsObserver = null
        input = null
        super.onDestroy()
    }

    internal companion object {
        private const val TAG = "VoiceHelper"
        private const val VENDOR_VOICE_PACKAGE = "com.jmgo.ai.voice"
        private const val ACTION_VENDOR_VOICE = "com.jmgo.action.AI_VOICE"
        private const val EXTRA_VENDOR_OPERATE = "operate"
        private const val VENDOR_VOICE_KEY = 609
        private const val VENDOR_VOICE_SCAN_CODE = 192
        private const val KEY_ACTION_MULTIPLE = 2
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
        Log.i(
            TAG,
            "startInput inputType=${info.inputType} package=${info.packageName} " +
                "fieldId=${info.fieldId}",
        )
        service.onEditorStateChanged()
    }

    override fun onFinishInput() {
        started = false
        generation++
        super.onFinishInput()
        Log.i(TAG, "finishInput")
        service.onEditorStateChanged()
    }

    private companion object {
        private const val TAG = "A11yInputMethod"
    }
}
