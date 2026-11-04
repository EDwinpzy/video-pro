package com.dongpiandi.voicehelper.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Privileged fallback for firmware builds whose voice window exposes the ASR text only in logcat.
 *
 * The result is delivered on the main thread and is never logged or persisted. Every permission,
 * process, format, or timeout failure produces `null`.
 */
class VendorLogTranscriptReader(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val sessionStartedAtMillis = AtomicLong(0L)
    private val worker =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "vendor-asr-logcat").apply { isDaemon = true }
        }

    @Volatile
    private var activeProcess: Process? = null

    fun canReadLogs(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.READ_LOGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Call on voice-key down so the reader cannot return a transcript from an earlier press. */
    fun beginSession() {
        if (!closed.get()) sessionStartedAtMillis.set(System.currentTimeMillis())
    }

    /**
     * Returns false when no read was scheduled. The callback still receives `null` on the main
     * thread for permission or process failures. After [close], no callback is delivered.
     */
    fun readRecent(onResult: (String?) -> Unit): Boolean {
        if (closed.get()) return false
        if (!canReadLogs()) {
            deliver(onResult, null)
            return false
        }

        val sessionStart = sessionStartedAtMillis.get()
        val sinceMillis =
            if (sessionStart > 0L) {
                sessionStart
            } else {
                System.currentTimeMillis() - DEFAULT_RECENT_WINDOW_MILLIS
            }
        return try {
            worker.execute {
                val transcript =
                    if (closed.get()) {
                        null
                    } else {
                        runCatching {
                            readLogcatTail(sinceMillis)?.let(VendorLogTranscriptParser::parse)
                        }.getOrNull()
                    }
                if (closed.get()) return@execute
                deliver(onResult, transcript)
            }
            true
        } catch (_: RejectedExecutionException) {
            deliver(onResult, null)
            false
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeProcess?.destroyForcibly()
        activeProcess = null
        worker.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun readLogcatTail(sinceMillis: Long): List<String>? {
        val since =
            SimpleDateFormat(LOGCAT_TIME_PATTERN, Locale.US)
                .format(Date(sinceMillis - SINCE_PADDING_MILLIS))
        val process =
            runCatching {
                ProcessBuilder(
                    LOGCAT_BINARY,
                    "-d",
                    "-v",
                    "threadtime",
                    "-T",
                    since,
                    "-t",
                    MAX_OUTPUT_LINES.toString(),
                )
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull() ?: return null

        activeProcess = process
        return try {
            if (closed.get()) return null
            process.outputStream.close()
            val lines = ArrayList<String>(MAX_OUTPUT_LINES)
            var totalCharacters = 0
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                while (lines.size < MAX_OUTPUT_LINES) {
                    val line = reader.readLine() ?: break
                    if (line.length > MAX_LINE_CHARS) continue
                    totalCharacters += line.length
                    if (totalCharacters > MAX_OUTPUT_CHARS) return null
                    lines += line
                }
            }

            if (!process.waitFor(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null
            if (process.exitValue() != 0) return null
            lines
        } catch (_: Exception) {
            null
        } finally {
            if (process.isAlive) process.destroyForcibly()
            activeProcess = null
        }
    }

    private fun deliver(
        onResult: (String?) -> Unit,
        transcript: String?,
    ) {
        if (closed.get()) return
        val safeResult = if (closed.get()) null else transcript
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onResult(safeResult)
        } else {
            mainHandler.post {
                if (!closed.get()) onResult(safeResult)
            }
        }
    }

    private companion object {
        const val LOGCAT_BINARY = "/system/bin/logcat"
        const val LOGCAT_TIME_PATTERN = "MM-dd HH:mm:ss.SSS"
        const val MAX_OUTPUT_LINES = 1_600
        const val MAX_LINE_CHARS = 8_192
        const val MAX_OUTPUT_CHARS = 1_000_000
        const val READ_TIMEOUT_SECONDS = 3L
        const val DEFAULT_RECENT_WINDOW_MILLIS = 8_000L
        const val SINCE_PADDING_MILLIS = 500L
    }
}

object VendorLogTranscriptParser {
    private val asrLine =
        Regex(
            pattern = """onAsr asr is(.*?)- isFinished is (?:true|false),\s*mAsrText=""",
            option = RegexOption.IGNORE_CASE,
        )

    fun parse(lines: Iterable<String>): String? {
        var selected: String? = null
        for (line in lines) {
            val candidate = parseLine(line) ?: continue
            selected = VendorTranscriptPolicy.select(listOf(candidate)) ?: selected
        }
        return selected
    }

    fun parseLine(line: CharSequence?): String? {
        if (line == null) return null
        val match = asrLine.findAll(line).lastOrNull() ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }
}
