package app.jellystack.tv

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Process
import dalvik.system.PathClassLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/**
 * Uses only the installed JMGO iFlytek AIUI recognition engine. It never invokes the vendor
 * VoiceService, vendor UI, command listener, LLM, or TTS playback.
 *
 * The vendor runtime is process-wide because AIUIAgent owns a static native singleton. Audio is
 * captured before engine initialization and buffered so a short press does not lose its first word.
 */
internal class JmgoDictationRecognizer(
    private val context: Context,
) : SystemDictationRecognizer {
    private val engineExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "jmgo-dictation-engine").apply { isDaemon = true }
        }
    private val audioExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "jmgo-dictation-audio").apply { isDaemon = true }
        }
    private val sessionLock = Any()

    @Volatile
    private var activeSession: Session? = null

    @Volatile
    private var runtime: VendorRuntime? = null

    override fun start(listener: SystemDictationRecognizer.Listener) {
        val session = Session(listener)
        synchronized(sessionLock) {
            activeSession?.cancelSilently()
            activeSession = session
        }
        audioExecutor.execute { captureAudio(session) }
        engineExecutor.execute { recognize(session) }
    }

    override fun stop() {
        activeSession?.let { session ->
            session.stopRequested = true
            session.recorder?.let { recorder -> runCatching { recorder.stop() } }
        }
    }

    override fun cancel() {
        activeSession?.cancelSilently()
    }

    private fun captureAudio(session: Session) {
        if (
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            session.fail()
            session.finishAudio()
            return
        }
        val minimum =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        if (minimum <= 0) {
            session.fail()
            session.finishAudio()
            return
        }

        val recorder =
            runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum * 2, AUDIO_BUFFER_BYTES),
                )
            }.getOrNull()
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder?.release() }
            session.fail()
            session.finishAudio()
            return
        }

        session.recorder = recorder
        try {
            if (session.stopRequested || session.cancelled) return
            recorder.startRecording()
            val chunk = ByteArray(AUDIO_CHUNK_BYTES)
            while (!session.stopRequested && !session.cancelled) {
                val count = recorder.read(chunk, 0, chunk.size)
                when {
                    count > 0 -> session.enqueue(chunk.copyOf(count))
                    count == AudioRecord.ERROR_DEAD_OBJECT -> {
                        session.fail()
                        break
                    }
                    count < 0 -> {
                        if (!session.stopRequested && !session.cancelled) session.fail()
                        break
                    }
                }
            }
        } catch (_: Throwable) {
            if (!session.stopRequested && !session.cancelled) session.fail()
        } finally {
            session.recorder = null
            runCatching { recorder.stop() }
            recorder.release()
            session.finishAudio()
        }
    }

    private fun recognize(session: Session) {
        try {
            val vendor = ensureRuntime()
            if (session.cancelled) return
            vendor.eventSink = { event -> handleVendorEvent(session, event) }
            vendor.ensureStarted()
            if (session.cancelled) return
            vendor.send(CMD_WAKEUP)

            while (true) {
                val chunk = session.audio.poll(30, TimeUnit.SECONDS)
                if (chunk == null) {
                    session.fail()
                    break
                }
                if (chunk.isEmpty()) break
                if (session.cancelled) break
                vendor.send(CMD_WRITE, AUDIO_PARAMS, chunk)
            }
            vendor.send(CMD_STOP_WRITE, AUDIO_PARAMS)
        } catch (_: Throwable) {
            if (!session.cancelled) session.fail()
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
                        if (result.isFinal) {
                            session.deliverResult(result.text)
                        } else {
                            session.deliverPartial(result.text)
                        }
                    }
                }
            }
            EVENT_ERROR -> session.fail()
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
    ) {
        val transcript = VendorIatTranscript()
        val audio = ArrayBlockingQueue<ByteArray>(MAX_AUDIO_CHUNKS)

        @Volatile
        var recorder: AudioRecord? = null

        @Volatile
        var stopRequested = false

        @Volatile
        var cancelled = false

        private var errorDelivered = false

        fun enqueue(bytes: ByteArray) {
            if (!audio.offer(bytes)) {
                audio.poll()
                audio.offer(bytes)
            }
        }

        fun finishAudio() {
            while (!audio.offer(AUDIO_END)) audio.poll()
        }

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
            runCatching { recorder?.stop() }
            audio.offer(AUDIO_END)
        }
    }

    /** One classloader and one AIUIAgent per app process; every operation stays on one executor. */
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
            started = true
            check(callback.ready.await(ENGINE_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Vendor engine did not become ready"
            }
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

        companion object {
            fun create(context: Context): VendorRuntime {
                if (Process.is64Bit()) {
                    error("JMGO speech engine is only available to a 32-bit process")
                }
                check(Build.SUPPORTED_32_BIT_ABIS.contains("armeabi-v7a")) {
                    "JMGO speech engine requires armeabi-v7a"
                }
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
                val serial = readVendorSerial(loader, vendorContext)
                setting.getMethod("setSystemInfo", String::class.java, String::class.java)
                    .invoke(null, "sn", serial)

                val config =
                    vendorContext.assets.open("cfg/aiui_phone.cfg").bufferedReader().use {
                        VendorAiuiConfig.enableExternalAudio(it.readText())
                    }
                val engineContext = VendorAssetsContext(context, vendorContext.assets)
                val callback = CallbackBridge()
                val callbackClass = loader.loadClass("com.iflytek.aiui.AIUIListener")
                val listener =
                    Proxy.newProxyInstance(loader, arrayOf(callbackClass)) { proxy, method, args ->
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
                val agentClass = loader.loadClass("com.iflytek.aiui.AIUIAgent")
                val agent =
                    agentClass
                        .getMethod(
                            "createAgent",
                            Context::class.java,
                            String::class.java,
                            callbackClass,
                        ).invoke(null, engineContext, config, listener)
                        ?: error("Vendor engine unavailable")
                return VendorRuntime(
                    agent = agent,
                    messageClass = loader.loadClass("com.iflytek.aiui.AIUIMessage"),
                    callback = callback,
                )
            }

            private fun readVendorSerial(
                loader: ClassLoader,
                vendorContext: Context,
            ): String {
                val managerClass =
                    loader.loadClass("com.jmgo.systemlibrary.projector.ApiDlpManager")
                val manager =
                    managerClass
                        .getMethod("getInstance", Context::class.java)
                        .invoke(null, vendorContext)
                return (managerClass.getMethod("getString", Int::class.javaPrimitiveType)
                    .invoke(manager, VENDOR_SERIAL_KEY) as? String)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: error("Vendor serial number unavailable")
            }
        }
    }

    private class CallbackBridge {
        val ready = CountDownLatch(1)

        @Volatile
        var eventSink: ((Any) -> Unit)? = null

        private val readySeen = java.util.concurrent.atomic.AtomicBoolean(false)

        fun onEvent(event: Any) {
            when (event.javaClass.getField("eventType").getInt(event)) {
                EVENT_STATE -> {
                    if (event.javaClass.getField("arg1").getInt(event) >= 2 && readySeen.compareAndSet(false, true)) {
                        ready.countDown()
                    }
                }
                EVENT_RESULT, EVENT_ERROR -> eventSink?.invoke(event)
            }
        }
    }

    /** Minimal context whose resources come from the installed vendor APK, never the network. */
    private class VendorAssetsContext(
        base: Context,
        private val vendorAssets: AssetManager,
    ) : ContextWrapper(base.applicationContext) {
        override fun getAssets(): AssetManager = vendorAssets

        override fun getApplicationContext(): Context = this
    }

    private companion object {
        const val VENDOR_PACKAGE = "com.jmgo.ai.voice"
        const val SAMPLE_RATE = 16_000
        const val AUDIO_CHUNK_BYTES = 3_200
        const val AUDIO_BUFFER_BYTES = 12_800
        const val MAX_AUDIO_CHUNKS = 250
        const val ENGINE_START_TIMEOUT_SECONDS = 7L
        const val AUDIO_PARAMS = "data_type=audio,sample_rate=16000"
        const val VENDOR_SERIAL_KEY = 147
        const val CMD_WRITE = 2
        const val CMD_STOP_WRITE = 3
        const val CMD_START = 5
        const val CMD_WAKEUP = 7
        const val EVENT_RESULT = 1
        const val EVENT_ERROR = 2
        const val EVENT_STATE = 3
        val AUDIO_END = ByteArray(0)
    }
}

/** Rewrites only transport flags; the vendor's tolerant JSON-with-comments remains on-device. */
internal object VendorAiuiConfig {
    private val dataSource = Regex("""("data_source"\s*:\s*")sdk(")""")
    private val playMode = Regex("""("play_mode"\s*:\s*")sdk(")""")
    private val audioFocus = Regex("""("audio_focus"\s*:\s*")1(")""")

    fun enableExternalAudio(config: String): String =
        config
            .replace(dataSource, "$1user$2")
            .replace(playMode, "$1user$2")
            .replace(audioFocus, "$1" + "0" + "$2")
}
