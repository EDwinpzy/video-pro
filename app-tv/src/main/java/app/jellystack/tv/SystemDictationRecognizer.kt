package app.jellystack.tv

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

    fun installForTest(factory: (() -> SystemDictationRecognizer)?) {
        testFactory = factory
        if (factory != null) vendorRecognizer = null
    }
}
