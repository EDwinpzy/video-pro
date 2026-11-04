package com.qiubo.optimaltv.dictation

/** Recognition only. Implementations must never launch an assistant, show UI, or play TTS. */
internal interface SystemDictationRecognizer {
    interface Listener {
        fun onPartial(text: String)

        fun onResult(text: String)

        fun onError()
    }

    fun start(listener: Listener)

    fun stop()

    fun cancel()
}

internal object SystemDictationRecognizerFactory {
    @Volatile
    private var testFactory: (() -> SystemDictationRecognizer)? = null

    @Volatile
    private var vendorRecognizer: SystemDictationRecognizer? = null

    fun create(context: android.content.Context): SystemDictationRecognizer {
        testFactory?.let { return it() }
        vendorRecognizer?.let { return it }
        return synchronized(this) {
            vendorRecognizer
                ?: JmgoDictationRecognizer(context.applicationContext).also { vendorRecognizer = it }
        }
    }

    fun prewarm(context: android.content.Context) {
        if (testFactory != null) return
        val recognizer = create(context)
        (recognizer as? JmgoDictationRecognizer)?.prewarm()
    }

    fun installForTest(factory: (() -> SystemDictationRecognizer)?) {
        testFactory = factory
    }

    fun installVendorPcmForTest(
        context: android.content.Context,
        pcm: ByteArray,
    ) {
        testFactory = null
        val engine =
            vendorRecognizer as? JmgoDictationRecognizer
                ?: JmgoDictationRecognizer(context.applicationContext).also {
                    vendorRecognizer = it
                }
        engine.useInjectedPcmForTest(pcm)
    }

    fun resetVendorPcmForTest() {
        (vendorRecognizer as? JmgoDictationRecognizer)?.useInjectedPcmForTest(null)
    }
}

