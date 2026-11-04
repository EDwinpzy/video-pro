package com.qiubo.optimaltv.dictation

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.qiubo.optimaltv.BuildConfig
import dalvik.system.PathClassLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.Proxy
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/**
 * Uses only the installed JMGO iFlytek AIUI recognition engine. It never invokes the vendor
 * VoiceService, vendor UI, command listener, LLM, or TTS playback.
 *
 * The vendor runtime is process-wide because AIUIAgent owns a static native singleton. It uses the
 * vendor's own near-field capture path, which is the path the projector's factory voice button uses.
 */
internal class JmgoDictationRecognizer(
    private val context: Context,
) : SystemDictationRecognizer {
    private val engineExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "jmgo-dictation-engine").apply { isDaemon = true }
        }
    private val sessionLock = Any()

    @Volatile
    private var activeSession: Session? = null

    @Volatile
    private var runtime: VendorRuntime? = null

    @Volatile
    private var injectedPcm: ByteArray? = null

    fun prewarm() {
        engineExecutor.execute {
            runCatching {
                val vendor = ensureRuntime()
                vendor.ensureStarted()
            }.onFailure { error ->
                Log.w(TAG, "prewarm failed: ${error.javaClass.simpleName}: ${error.message}")
            }
        }
    }

    fun useInjectedPcmForTest(pcm: ByteArray?) {
        injectedPcm = pcm
        Log.i(TAG, "injected audio ${if (pcm == null) "disabled" else "bytes=${pcm.size}"}")
    }

    override fun start(listener: SystemDictationRecognizer.Listener) {
        val session = Session(listener, injectedPcm)
        val previous =
            synchronized(sessionLock) {
                val old = activeSession
                old?.cancelSilently()
                activeSession = session
                old
            }
        if (previous != null) {
            engineExecutor.execute { stopRecord(previous) }
        }
        engineExecutor.execute { recognize(session) }
    }

    override fun stop() {
        activeSession?.let { session ->
            session.stopRequested = true
            session.captureRecord?.let { runCatching { it.stop() } }
            engineExecutor.execute { stopRecord(session) }
        }
    }

    override fun cancel() {
        activeSession?.let { session ->
            session.cancelSilently()
            session.captureRecord?.let { runCatching { it.stop() } }
            engineExecutor.execute {
                stopRecord(session)
            }
        }
    }

    private fun recognize(session: Session) {
        try {
            val vendor = ensureRuntime()
            vendor.eventSink = { event -> handleVendorEvent(session, event) }
            vendor.ensureStarted()
            Log.i(TAG, "vendor engine ready, injected=${session.injectedPcm != null}")
            if (session.cancelled) return
            if (session.stopRequested) {
                session.fail()
                return
            }
            vendor.send(CMD_SET_PARAMS, NEAR_FIELD_PARAMS)
            vendor.send(CMD_WAKEUP, AUDIO_PARAMS)
            vendor.awaitWorking(ENGINE_WORKING_TIMEOUT_SECONDS)
            session.recordStarted = true
            val injected = session.injectedPcm
            if (injected != null) {
                streamInjectedPcm(vendor, session, injected)
            } else {
                streamMicrophone(vendor, session)
            }
            if (session.stopRequested || session.cancelled) {
                stopRecord(session)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "recognize failed: ${error.javaClass.simpleName}: ${error.message}")
            if (!session.cancelled) session.fail()
        }
    }

    private fun stopRecord(session: Session) {
        if (!session.recordStarted || !session.stopSent.compareAndSet(false, true)) return
        runCatching { runtime?.send(CMD_STOP_WRITE, STOP_AUDIO_PARAMS) }
    }

    private fun streamInjectedPcm(
        vendor: VendorRuntime,
        session: Session,
        pcm: ByteArray,
    ) {
        Log.i(TAG, "stream injected pcm bytes=${pcm.size}")
        var offset = 0
        while (offset < pcm.size && !session.stopRequested && !session.cancelled) {
            val end = (offset + PCM_CHUNK_BYTES).coerceAtMost(pcm.size)
            vendor.send(CMD_WRITE, AUDIO_PARAMS, pcm.copyOfRange(offset, end))
            offset = end
            if (offset < pcm.size && !session.stopRequested && !session.cancelled) {
                Thread.sleep(PCM_CHUNK_INTERVAL_MS)
            }
        }
        stopRecord(session)
    }

    private fun streamMicrophone(
        vendor: VendorRuntime,
        session: Session,
    ) {
        var record: AudioRecord? = null
        var capturedBytes = 0L
        var capturedPeak = 0
        var capturedChunks = 0
        try {
            val minimumBuffer =
                AudioRecord.getMinBufferSize(
                    MICROPHONE_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            if (minimumBuffer <= 0) {
                Log.w(TAG, "AudioRecord buffer query failed: $minimumBuffer")
                session.fail()
                return
            }
            record =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MICROPHONE_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimumBuffer, PCM_CHUNK_BYTES * 4),
                )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord initialization failed")
                session.fail()
                return
            }
            session.captureRecord = record
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "AudioRecord start failed")
                session.fail()
                return
            }
            Log.i(TAG, "microphone capture started")
            val buffer = ByteArray(PCM_CHUNK_BYTES)
            while (!session.stopRequested && !session.cancelled) {
                val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) {
                    if (session.stopRequested || session.cancelled) break
                    Log.w(TAG, "AudioRecord read failed: $count")
                    session.fail()
                    return
                }
                capturedBytes += count
                capturedChunks++
                for (index in 0 until count - 1 step 2) {
                    val sample =
                        (
                            (buffer[index].toInt() and 0xff) or
                                (buffer[index + 1].toInt() shl 8)
                        ).toShort().toInt()
                    capturedPeak = maxOf(capturedPeak, kotlin.math.abs(sample))
                }
                if (BuildConfig.DEBUG && capturedChunks % MICROPHONE_LOG_INTERVAL_CHUNKS == 0) {
                    Log.i(
                        TAG,
                        "microphone pcm bytes=$capturedBytes peak=$capturedPeak",
                    )
                }
                vendor.send(CMD_WRITE, AUDIO_PARAMS, buffer.copyOf(count))
            }
        } catch (error: Throwable) {
            Log.w(TAG, "microphone capture failed: ${error.javaClass.simpleName}: ${error.message}")
            session.fail()
        } finally {
            session.captureRecord = null
            runCatching { record?.stop() }
            runCatching { record?.release() }
            stopRecord(session)
            if (BuildConfig.DEBUG && capturedBytes > 0) {
                Log.i(TAG, "microphone capture stopped bytes=$capturedBytes peak=$capturedPeak")
            }
        }
    }

    private fun handleVendorEvent(
        session: Session,
        event: Any,
    ) {
        if (session.cancelled || session !== activeSession) return
        val eventClass = event.javaClass
        when (eventClass.getField("eventType").getInt(event)) {
            EVENT_RESULT -> {
                val info =
                    runCatching {
                        JSONObject(eventClass.getField("info").get(event) as String)
                    }.getOrNull() ?: return
                val bundle = eventClass.getField("data").get(event) as? Bundle ?: return
                val items = info.optJSONArray("data") ?: return
                for (itemIndex in 0 until items.length()) {
                    val item = items.getJSONObject(itemIndex)
                    if (item.optJSONObject("params")?.optString("sub") != "iat") continue
                    val content = item.optJSONArray("content") ?: continue
                    for (contentIndex in 0 until content.length()) {
                        val id = content.getJSONObject(contentIndex).optString("cnt_id", "0")
                        val bytes = bundle.getByteArray(id) ?: continue
                        val payload = decode(bytes) ?: continue
                        val result = session.transcript.accept(payload) ?: continue
                        Log.i(
                            TAG,
                            "iat result length=${result.text.length} final=${result.isFinal}",
                        )
                        if (result.isFinal) {
                            session.completedNormally = true
                            session.deliverResult(result.text)
                        } else {
                            session.deliverPartial(result.text)
                        }
                    }
                }
            }
            EVENT_ERROR -> {
                Log.w(
                    TAG,
                    "vendor error arg1=${eventClass.getField("arg1").getInt(event)} " +
                        "arg2=${eventClass.getField("arg2").getInt(event)}",
                )
                session.fail()
            }
        }
    }

    private fun decode(bytes: ByteArray): String? =
        runCatching {
            val plain =
                if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                    GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader().use { it.readText() }
                } else {
                    bytes.toString(Charsets.UTF_8)
                }
            plain.takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun ensureRuntime(): VendorRuntime {
        runtime?.let { return it }
        val created = VendorRuntime.create(context)
        runtime = created
        return created
    }

    private class Session(
        private val listener: SystemDictationRecognizer.Listener,
        val injectedPcm: ByteArray?,
    ) {
        val transcript = VendorIatTranscript()

        @Volatile
        var stopRequested = false

        @Volatile
        var cancelled = false

        @Volatile
        var recordStarted = false

        @Volatile
        var captureRecord: AudioRecord? = null

        @Volatile
        var completedNormally = false

        val stopSent = java.util.concurrent.atomic.AtomicBoolean(false)

        private var errorDelivered = false

        fun fail() {
            if (cancelled || errorDelivered) return
            errorDelivered = true
            listener.onError()
        }

        fun deliverPartial(text: String) {
            if (!cancelled) listener.onPartial(text)
        }

        fun deliverResult(text: String) {
            if (!cancelled) listener.onResult(text)
        }

        fun cancelSilently() {
            cancelled = true
            stopRequested = true
        }
    }

    /** One vendor classloader per process; all AIUIAgent operations stay on one executor. */
    private class VendorRuntime private constructor(
        private val agent: Any,
        private val messageClass: Class<*>,
        private val callback: CallbackBridge,
    ) {
        var eventSink: ((Any) -> Unit)?
            get() = callback.eventSink
            set(value) {
                callback.eventSink = value
            }

        @Volatile
        private var started = false

        fun ensureStarted() {
            if (started) return
            send(CMD_START)
            check(callback.ready.await(ENGINE_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Vendor engine did not become ready"
            }
            started = true
        }

        fun awaitWorking(timeoutSeconds: Long): Boolean {
            val ready = callback.working.await(timeoutSeconds, TimeUnit.SECONDS)
            if (!ready) Log.w(TAG, "Vendor engine did not report WORKING")
            return ready
        }

        fun send(
            command: Int,
            params: String? = null,
            bytes: ByteArray? = null,
        ) {
            val message =
                messageClass
                    .getConstructor(
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        String::class.java,
                        ByteArray::class.java,
                    ).newInstance(command, 0, 0, params, bytes)
            agent.javaClass.getMethod("sendMessage", messageClass).invoke(agent, message)
        }

        fun destroy() {
            runCatching { agent.javaClass.getMethod("destroy").invoke(agent) }
        }

        companion object {
            @Volatile
            private var classes: VendorClasses? = null

            fun create(context: Context): VendorRuntime {
                if (Process.is64Bit()) {
                    error("JMGO speech engine is only available to a 32-bit process")
                }
                check(Build.SUPPORTED_32_BIT_ABIS.contains("armeabi-v7a")) {
                    "JMGO speech engine requires armeabi-v7a"
                }
                val vendor = classes(context)
                val vendorContext = context.createPackageContext(VENDOR_PACKAGE, 0)
                val config =
                    vendorContext.assets.open(vendor.configName).bufferedReader().use {
                        VendorAiuiConfig.enableDictation(
                            it.readText(),
                        )
                    }
                val engineContext = VendorAssetsContext(context, vendorContext)
                val callback = CallbackBridge()
                val listener =
                    Proxy.newProxyInstance(vendor.loader, arrayOf(vendor.callbackClass)) { proxy, method, args ->
                        when (method.name) {
                            "toString" -> "VideoProDictationListener"
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === args?.firstOrNull()
                            "onEvent" -> {
                                args?.firstOrNull()?.let(callback::onEvent)
                                null
                            }
                            else -> null
                        }
                    }
                val agent =
                    vendor.agentClass
                        .getMethod(
                            "createAgent",
                            Context::class.java,
                            String::class.java,
                            vendor.callbackClass,
                        ).invoke(null, engineContext, config, listener)
                        ?: error("Vendor engine unavailable")
                return VendorRuntime(
                    agent = agent,
                    messageClass = vendor.messageClass,
                    callback = callback,
                )
            }

            private fun classes(context: Context): VendorClasses {
                classes?.let { return it }
                return synchronized(this) {
                    classes?.let { return@synchronized it }
                    createClasses(context).also { classes = it }
                }
            }

            private fun createClasses(context: Context): VendorClasses {
                val packageManager = context.packageManager
                val vendor = packageManager.getApplicationInfo(VENDOR_PACKAGE, 0)
                val vendorContext = context.createPackageContext(vendor.packageName, 0)
                val loader = PathClassLoader(vendor.sourceDir, vendor.nativeLibraryDir, context.classLoader)
                val setting = loader.loadClass("com.iflytek.aiui.AIUISetting")
                val aiuiDirectory = File(context.filesDir, "jmgo-dictation").apply { mkdirs() }
                setting.getMethod("setAIUIDir", String::class.java)
                    .invoke(null, aiuiDirectory.absolutePath + File.separator)
                setting.getMethod("setSaveDataLog", Boolean::class.javaPrimitiveType).invoke(null, false)
                setting.getMethod("setShowLog", Boolean::class.javaPrimitiveType).invoke(null, false)
                runCatching {
                    setting.getMethod("setLocationEnable", Boolean::class.javaPrimitiveType)
                        .invoke(null, false)
                }
                val serial = readVendorSerial(loader, vendorContext)
                setting.getMethod("setSystemInfo", String::class.java, String::class.java)
                    .invoke(null, "sn", serial)
                return VendorClasses(
                    loader = loader,
                    callbackClass = loader.loadClass("com.iflytek.aiui.AIUIListener"),
                    agentClass = loader.loadClass("com.iflytek.aiui.AIUIAgent"),
                    messageClass = loader.loadClass("com.iflytek.aiui.AIUIMessage"),
                    configName = configName(loader),
                )
            }

            private fun readVendorSerial(
                loader: ClassLoader,
                vendorContext: Context,
            ): String {
                val managers =
                    listOf(
                        "com.jmgo.systemlibrary.projector.ApiDlpManager",
                        "com.jmgo.middleware.projector.JmGODlpManager",
                    )
                for (managerName in managers) {
                    val serial =
                        runCatching {
                            val managerClass = loader.loadClass(managerName)
                            val manager =
                                managerClass
                                    .getMethod("getInstance", Context::class.java)
                                    .invoke(null, vendorContext)
                            (managerClass.getMethod("getString", Int::class.javaPrimitiveType)
                                .invoke(manager, VENDOR_SERIAL_KEY) as? String)
                                ?.trim()
                                ?.takeIf(String::isNotEmpty)
                        }.getOrNull()
                    if (serial != null) return serial
                }
                // JGVoiceImpl.initSdk uses this vendor-provided fallback when no serial exists.
                return "JMGO_TEST${Random().nextInt(20)}"
            }

            private fun configName(loader: ClassLoader): String {
                val oldDevice =
                    runCatching {
                        val propertyClass = loader.loadClass("com.jmgo.ai.base.utils.JGProperty")
                        propertyClass.getMethod("isOldDevice").invoke(null) as? Boolean
                    }.getOrNull() == true
                return if (oldDevice) "cfg/aiui_phone_old.cfg" else "cfg/aiui_phone.cfg"
            }
        }
    }

    private data class VendorClasses(
        val loader: ClassLoader,
        val callbackClass: Class<*>,
        val agentClass: Class<*>,
        val messageClass: Class<*>,
        val configName: String,
    )

    private class CallbackBridge {
        val ready = CountDownLatch(1)
        val working = CountDownLatch(1)

        @Volatile
        var eventSink: ((Any) -> Unit)? = null

        private val readySeen = java.util.concurrent.atomic.AtomicBoolean(false)

        fun onEvent(event: Any) {
            when (event.javaClass.getField("eventType").getInt(event)) {
                EVENT_STATE -> {
                    val state = event.javaClass.getField("arg1").getInt(event)
                    if (state >= 2 && readySeen.compareAndSet(false, true)) {
                        ready.countDown()
                    }
                    if (state >= 3) working.countDown()
                }
                EVENT_RESULT, EVENT_ERROR -> eventSink?.invoke(event)
            }
        }
    }

    /** Minimal context whose resources come from the installed vendor APK, never the network. */
    private class VendorAssetsContext(
        base: Context,
        private val vendorContext: Context,
    ) : ContextWrapper(base.applicationContext) {
        override fun getAssets(): AssetManager = vendorContext.assets

        override fun getResources() = vendorContext.resources

        override fun getPackageResourcePath(): String = vendorContext.packageResourcePath

        override fun getPackageCodePath(): String = vendorContext.packageCodePath

        override fun getApplicationContext(): Context = this
    }

    private companion object {
        const val VENDOR_PACKAGE = "com.jmgo.ai.voice"
        const val ENGINE_START_TIMEOUT_SECONDS = 7L
        const val ENGINE_WORKING_TIMEOUT_SECONDS = 2L
        const val MICROPHONE_SAMPLE_RATE = 16_000
        const val MICROPHONE_LOG_INTERVAL_CHUNKS = 25
        const val AUDIO_PARAMS = "data_type=audio, sample_rate=16000, dwa=wpgs"
        const val STOP_AUDIO_PARAMS = "data_type=audio"
        const val NEAR_FIELD_PARAMS =
            """{"attachparams":{"iat_params":{"isFar":"0","domain":"tv"},"trs_params":"{}"},"speech":{"interact_mode":"oneshot"}}"""
        const val VENDOR_SERIAL_KEY = 147
        const val TAG = "JmgoDictation"
        const val CMD_WRITE = 2
        const val CMD_STOP_WRITE = 3
        const val CMD_START = 5
        const val CMD_SET_PARAMS = 10
        const val CMD_WAKEUP = 7
        const val PCM_CHUNK_BYTES = 1_280
        const val PCM_CHUNK_INTERVAL_MS = 40L
        const val EVENT_RESULT = 1
        const val EVENT_ERROR = 2
        const val EVENT_STATE = 3
    }
}

/** Rewrites only transport flags; the vendor's tolerant JSON-with-comments remains on-device. */
internal object VendorAiuiConfig {
    private val playMode = Regex("""("play_mode"\s*:\s*")sdk(")""")
    private val audioFocus = Regex("""("audio_focus"\s*:\s*")1(")""")
    private val interactMode = Regex("""("interact_mode"\s*:\s*")[^"]*(")""")
    private val wakeupMode = Regex("""("wakeup_mode"\s*:\s*")[^"]*(")""")
    private val dataSource = Regex("""("data_source"\s*:\s*")[^"]*(")""")

    fun enableDictation(config: String): String {
        val prepared =
            config
                .replace(playMode, "$1user$2")
                .replace(audioFocus, "$1" + "0" + "$2")
                .replace(interactMode, "$1oneshot$2")
                .replace(wakeupMode, "$1off$2")
        return prepared.replace(dataSource, "$1user$2")
    }
}

