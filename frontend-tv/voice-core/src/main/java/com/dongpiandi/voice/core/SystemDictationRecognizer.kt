package com.dongpiandi.voice.core

/** Recognition only. Implementations must never launch an assistant, show UI, or play TTS. */
interface SystemDictationRecognizer {
    interface Listener {
        fun onPartial(text: String)

        fun onResult(text: String)

        fun onError()
    }

    fun start(listener: Listener)

    fun stop()

    fun cancel()
}

object SystemDictationRecognizerFactory {
    @Volatile
    private var vendorRecognizer: SystemDictationRecognizer? = null

    fun create(context: android.content.Context): SystemDictationRecognizer {
        vendorRecognizer?.let { return it }
        return synchronized(this) {
            vendorRecognizer
                ?: JmgoDictationRecognizer(context.applicationContext).also { vendorRecognizer = it }
        }
    }

    fun prewarm(context: android.content.Context) {
        val recognizer = create(context)
        (recognizer as? JmgoDictationRecognizer)?.prewarm()
    }
}

