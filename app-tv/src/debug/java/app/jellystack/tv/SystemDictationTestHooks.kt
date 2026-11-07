package app.jellystack.tv

import java.util.concurrent.atomic.AtomicInteger

/** Debug-only bridge for the instrumentation APK; no hook is packaged in release builds. */
object SystemDictationTestHooks {
    private val transcriptIndex = AtomicInteger()

    @JvmStatic
    fun installTranscripts(vararg transcripts: String) {
        transcriptIndex.set(0)
        SystemDictationRecognizerFactory.installForTest {
            object : SystemDictationRecognizer {
                private var listener: SystemDictationRecognizer.Listener? = null

                override fun start(listener: SystemDictationRecognizer.Listener) {
                    this.listener = listener
                }

                override fun stop() {
                    val index = transcriptIndex.getAndIncrement()
                    listener?.onResult(transcripts.getOrElse(index) { transcripts.lastOrNull().orEmpty() })
                }

                override fun cancel() {
                    listener = null
                }
            }
        }
    }

    @JvmStatic
    fun resetRecognizer() {
        SystemDictationRecognizerFactory.installForTest(null)
        transcriptIndex.set(0)
    }

    @JvmStatic
    fun isServiceRunning(): Boolean = SystemDictationService.runningForTest()

    @JvmStatic
    fun hasEditor(): Boolean = SystemDictationService.editorAttachedForTest()
}
