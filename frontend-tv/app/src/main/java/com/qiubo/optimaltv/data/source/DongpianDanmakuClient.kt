package com.qiubo.optimaltv.data.source

import android.content.Context
import com.qiubo.optimaltv.OtvLog
import com.qiubo.optimaltv.auth.DongpianAuthClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

data class DanmakuItem(
    val timeMs: Long,
    val sourceMode: Int,
    val color: Int,
    val fontSize: Int,
    val content: String,
    val sourceId: String? = null,
)

data class DanmakuSegmentSpec(
    val fromMs: Long,
    val toMs: Long,
    val count: Int,
    val sha256: String,
    val url: String,
)

data class DanmakuManifest(
    val durationMs: Long?,
    val commentCount: Int,
    val segments: List<DanmakuSegmentSpec>,
)

data class DanmakuWindow(
    val items: List<DanmakuItem>,
    val available: Boolean,
    val status: String = "",
    val fromMs: Long = 0L,
    val toMs: Long = 0L,
)

internal data class DanmakuContentRange(
    val start: Long?,
    val end: Long?,
    val total: Long?,
)

internal data class DanmakuResponsePlan(
    val action: Action,
    val append: Boolean,
    val bodyLength: Long,
    val contentEnd: Long? = null,
    val total: Long? = null,
) {
    enum class Action {
        ALREADY_COMPLETE,
        REPLACE,
        APPEND,
    }
}

internal object DongpianDanmakuTransferProtocol {
    private val rangePattern =
        Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
    private val unsatisfiedRangePattern =
        Regex("""bytes\s+\*/(\d+)""", RegexOption.IGNORE_CASE)

    fun parseContentRange(value: String?): DanmakuContentRange? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        rangePattern.matchEntire(normalized)?.let { match ->
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total =
                match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
            if (end < start || (total != null && total <= end)) return null
            return DanmakuContentRange(start, end, total)
        }
        unsatisfiedRangePattern.matchEntire(normalized)?.let { match ->
            val total = match.groupValues[1].toLongOrNull() ?: return null
            return DanmakuContentRange(start = null, end = null, total = total)
        }
        return null
    }

    fun responsePlan(
        status: Int,
        expectedStart: Long,
        contentRange: String?,
        contentLength: Long,
    ): DanmakuResponsePlan {
        val range = parseContentRange(contentRange)
        if (status == 416) {
            val unsatisfied = requireNotNull(range) { "danmaku missing unsatisfied range" }
            require(
                expectedStart > 0L &&
                    unsatisfied.start == null &&
                    unsatisfied.end == null &&
                    unsatisfied.total == expectedStart,
            ) { "danmaku invalid unsatisfied range" }
            return DanmakuResponsePlan(
                action = DanmakuResponsePlan.Action.ALREADY_COMPLETE,
                append = true,
                bodyLength = 0L,
            )
        }
        require(status == 200 || status == 206) { "danmaku http $status" }
        if (status == 200) {
            return DanmakuResponsePlan(
                action = DanmakuResponsePlan.Action.REPLACE,
                append = false,
                bodyLength = contentLength,
            )
        }
        val ranged = requireNotNull(range) { "danmaku missing content range" }
        val rangeEnd = requireNotNull(ranged.end) { "danmaku missing range end" }
        require(ranged.start == expectedStart) {
            "danmaku invalid content range"
        }
        val bodyLength = rangeEnd - expectedStart + 1L
        require(contentLength < 0L || contentLength == bodyLength) {
            "danmaku content range length mismatch"
        }
        return DanmakuResponsePlan(
            action = DanmakuResponsePlan.Action.APPEND,
            append = expectedStart > 0L,
            bodyLength = bodyLength,
            contentEnd = rangeEnd,
            total = ranged.total,
        )
    }
}

internal object DanmakuWindowPolicy {
    fun normalized(items: List<DanmakuItem>): List<DanmakuItem> =
        if (items.size < 2) {
            items
        } else {
            items
                .distinctBy { "${it.timeMs}|${it.sourceMode}|${it.content}" }
                .sortedBy(DanmakuItem::timeMs)
        }

    fun segmentIndex(
        segments: List<DanmakuSegmentSpec>,
        positionMs: Long,
    ): Int? {
        if (segments.isEmpty()) return null
        val safePosition = positionMs.coerceAtLeast(0L)
        val index = segments.indexOfLast { safePosition >= it.fromMs }.coerceAtLeast(0)
        return index.takeIf { safePosition < segments[it].toMs }
    }

    fun covers(
        window: DanmakuWindow,
        positionMs: Long,
    ): Boolean = window.toMs > window.fromMs && positionMs in window.fromMs until window.toMs

    /**
     * Keeps comments that started shortly before a new segment so they can finish scrolling
     * across the segment boundary instead of vanishing when the window is replaced.
     */
    fun mergeWindowItems(
        previous: List<DanmakuItem>,
        incoming: List<DanmakuItem>,
        incomingFromMs: Long,
        keepBehindMs: Long = 30_000L,
    ): List<DanmakuItem> {
        if (incoming.isEmpty()) return previous
        if (previous.isEmpty() || previous === incoming) return incoming
        val cutoffMs = (incomingFromMs - keepBehindMs.coerceAtLeast(0L)).coerceAtLeast(0L)
        val retained = previous.filter { it.timeMs in cutoffMs until incomingFromMs }
        if (retained.isEmpty()) return incoming
        return (retained + incoming)
            .distinctBy { "${it.timeMs}|${it.sourceMode}|${it.content}" }
            .sortedBy(DanmakuItem::timeMs)
    }
}

/**
 * Official Danmaku transport recovered from the shipped app/web player.
 *
 * The source endpoint returns a manifest URL. The manifest points at immutable,
 * hash-addressed gzip segments whose entries are compact JSON arrays.
 */
class DongpianDanmakuClient internal constructor(
    private val context: Context,
    networkClient: OkHttpClient? = null,
    segmentClient: OkHttpClient? = null,
) {
    private val http =
        networkClient ?: OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .build()
    private val segmentHttp =
        segmentClient ?: http.newBuilder()
                // A one-shot wall-clock limit discarded already downloaded bytes and
                // could expire while a valid dense segment was still making progress.
                // Bound each idle read instead; the transfer loop below owns the total
                // budget and resumes from the retained bytes after a transient stall.
                .readTimeout(35, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
    private val segmentCache =
        object : LinkedHashMap<String, List<DanmakuItem>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<DanmakuItem>>,
            ): Boolean = size > MAX_CACHED_SEGMENTS
        }

    private var cachedEpisodeId: String = ""
    private var cachedManifest: DanmakuManifest? = null
    private var manifestExpiresAt: Long = 0L
    private var sourceRetryAt: Long = 0L
    private var lastStatus: String = ""
    private var cachedWindowIndex: Int = -1
    private var cachedWindow: DanmakuWindow? = null

    suspend fun loadWindow(
        episodeId: String,
        positionMs: Long,
    ): DanmakuWindow {
        if (episodeId.isBlank() || !DongpianAuthClient.isSignedIn(context)) {
            return DanmakuWindow(emptyList(), false, "登录后可加载弹幕")
        }
        val manifest = manifestFor(episodeId) ?: return DanmakuWindow(emptyList(), false, lastStatus)
        if (manifest.segments.isEmpty()) {
            return DanmakuWindow(emptyList(), false, "当前集暂无弹幕")
        }
        val currentIndex = DanmakuWindowPolicy.segmentIndex(manifest.segments, positionMs)
            ?: return DanmakuWindow(emptyList(), false, "当前集暂无弹幕")
        if (cachedWindowIndex == currentIndex) cachedWindow?.let { return it }
        val segment = manifest.segments[currentIndex]
        val items =
            try {
                loadSegment(segment, manifestUrlOrigin, currentIndex)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                OtvLog.w(
                    "official danmaku segment failed index=$currentIndex " +
                        "fromMs=${segment.fromMs} toMs=${segment.toMs} " +
                        "count=${segment.count} type=${error.safeType()}",
                )
                return DanmakuWindow(
                    emptyList(),
                    false,
                    "弹幕加载失败",
                    segment.fromMs,
                    segment.toMs,
                )
            }
        return DanmakuWindow(
            items = items,
            available = true,
            fromMs = segment.fromMs,
            toMs = segment.toMs,
        ).also {
            cachedWindowIndex = currentIndex
            cachedWindow = it
        }
    }

    fun clearMemoryCache() {
        cachedEpisodeId = ""
        cachedManifest = null
        manifestExpiresAt = 0L
        sourceRetryAt = 0L
        lastStatus = ""
        manifestUrlOrigin = ""
        cachedWindowIndex = -1
        cachedWindow = null
        synchronized(segmentCache) { segmentCache.clear() }
    }

    private var manifestUrlOrigin: String = ""

    private fun manifestFor(episodeId: String): DanmakuManifest? {
        val now = System.currentTimeMillis()
        if (cachedEpisodeId != episodeId) {
            cachedEpisodeId = episodeId
            cachedManifest = null
            manifestExpiresAt = 0L
            sourceRetryAt = 0L
            lastStatus = ""
            segmentCache.clear()
            cachedWindowIndex = -1
            cachedWindow = null
        }
        cachedManifest?.takeIf { now < manifestExpiresAt }?.let { return it }
        val cached = cachedManifest
        if (now < sourceRetryAt) return cached
        val encoded = URLEncoder.encode(episodeId, "UTF-8").replace("+", "%20")
        val source =
            runCatching {
                DongpianAuthClient.apiJson(
                    context,
                    "/v1/danmuku/episodes/$encoded/source",
                )
            }.onFailure {
                OtvLog.w("official danmaku source failed type=${it.safeType()}")
                lastStatus = "弹幕加载失败"
                sourceRetryAt = now + FAILED_RETRY_MS
            }.getOrNull() ?: return cached

        val status = source.optString("status").trim()
        val manifestUrl =
            firstString(source, "manifestUrl", "manifest_url")
        OtvLog.i(
            "official danmaku source status=${status.ifBlank { "unknown" }} " +
                "manifest=${manifestUrl.isNotBlank()} retry=${source.optLong("retryAfterMs", 0L)}",
        )
        if (status != "ready" || manifestUrl.isBlank()) {
            lastStatus =
                when (status) {
                    "generating" -> "弹幕生成中"
                    "failed" -> "弹幕加载失败"
                    "missing" -> "当前集暂无弹幕"
                    else -> "当前集暂无弹幕"
                }
            val retryMs =
                firstLong(source, "retryAfterMs", "retry_after_ms")
                    ?.takeIf { it > 0L }
                    ?: DEFAULT_RETRY_MS
            sourceRetryAt = now + retryMs.coerceIn(MIN_RETRY_MS, MAX_RETRY_MS)
            return cached.takeIf { status == "generating" }
        }

        val resolvedUrl = resolveHttpsUrl(manifestUrl)
        val manifest =
            try {
                val manifestBytes = download(resolvedUrl, MAX_MANIFEST_BYTES)
                val manifestJson =
                    JSONObject(
                        String(decodeGzipIfNeeded(manifestBytes), StandardCharsets.UTF_8),
                    )
                DongpianDanmakuParser.parseManifest(manifestJson, resolvedUrl)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                OtvLog.w("official danmaku manifest failed type=${error.safeType()}")
                lastStatus = "弹幕加载失败"
                sourceRetryAt = now + FAILED_RETRY_MS
                return cached
            }
        cachedManifest = manifest
        manifestUrlOrigin = resolvedUrl
        manifestExpiresAt = now + MANIFEST_CACHE_MS
        sourceRetryAt = 0L
        lastStatus = ""
        OtvLog.i(
            "official danmaku manifest segments=${manifest.segments.size} " +
                "comments=${manifest.commentCount}",
        )
        return manifest
    }

    private suspend fun loadSegment(
        spec: DanmakuSegmentSpec,
        manifestUrl: String,
        index: Int,
    ): List<DanmakuItem> {
        val resolved = DongpianDanmakuParser.resolveSegmentUrl(spec.url, manifestUrl)
        val key = "$resolved:${spec.sha256}"
        synchronized(segmentCache) { segmentCache[key] }?.let {
            OtvLog.i(
                "official danmaku segment memory cache hit index=$index " +
                    "fromMs=${spec.fromMs} toMs=${spec.toMs} count=${it.size}",
            )
            return it
        }
        val raw = cachedSegmentBytes(spec, resolved, index)
        val json =
            JSONObject(
                String(decodeGzipIfNeeded(raw), StandardCharsets.UTF_8),
            )
        return DanmakuWindowPolicy
            .normalized(DongpianDanmakuParser.parseSegment(json, spec))
            .also {
            synchronized(segmentCache) { segmentCache[key] = it }
        }
    }

    private suspend fun cachedSegmentBytes(
        spec: DanmakuSegmentSpec,
        url: String,
        index: Int,
    ): ByteArray {
        val directory = File(context.cacheDir, "danmaku_segments")
        val cacheFile = File(directory, "${spec.sha256}.gzip")
        if (cacheFile.isFile && cacheFile.length() in 1..MAX_SEGMENT_BYTES.toLong()) {
            val cached = cacheFile.readBytes()
            if (MessageDigest.getInstance("SHA-256").digest(cached).toHex()
                    .equals(spec.sha256, ignoreCase = true)
            ) {
                OtvLog.i("official danmaku segment cache hit bytes=${cached.size}")
                return cached
            }
            cacheFile.delete()
        }
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            error("danmaku cache directory unavailable")
        }
        val partial = File(directory, "${spec.sha256}.part")
        val raw = downloadSegmentWithResume(spec, url, partial, index)
        val temporary = File(directory, "${spec.sha256}.tmp")
        temporary.writeBytes(raw)
        if (!temporary.renameTo(cacheFile)) {
            temporary.copyTo(cacheFile, overwrite = true)
            temporary.delete()
        }
        partial.delete()
        return raw
    }

    internal suspend fun downloadSegmentWithResume(
        spec: DanmakuSegmentSpec,
        url: String,
        partial: File,
        index: Int,
    ): ByteArray {
        val startedAt = System.nanoTime()
        val deadline = startedAt + TimeUnit.MINUTES.toNanos(SEGMENT_TRANSFER_BUDGET_MINUTES)
        var attempt = 0
        var stalledAttempts = 0
        var lastError: Exception? = null
        OtvLog.i(
            "official danmaku segment load start index=$index " +
                "fromMs=${spec.fromMs} toMs=${spec.toMs} count=${spec.count} " +
                "partialBytes=${partial.length()}",
        )
        while (System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            attempt++
            val beforeBytes = partial.length().coerceAtMost(MAX_SEGMENT_BYTES.toLong())
            val outcome =
                try {
                    val remainingMs =
                        TimeUnit.NANOSECONDS
                            .toMillis(deadline - System.nanoTime())
                            .coerceAtLeast(1L)
                    transferSegmentAttempt(
                        url = url,
                        partial = partial,
                        expectedStart = beforeBytes,
                        index = index,
                        attempt = attempt,
                        callTimeoutMs = remainingMs,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    lastError = error
                    val retainedBytes = partial.length().coerceAtMost(MAX_SEGMENT_BYTES.toLong())
                    OtvLog.w(
                        "official danmaku segment retry index=$index attempt=$attempt " +
                            "type=${error.safeType()} retainedBytes=$retainedBytes " +
                            "elapsedMs=${elapsedMsSince(startedAt)}",
                    )
                    if (retainedBytes <= beforeBytes) {
                        stalledAttempts++
                    } else {
                        stalledAttempts = 0
                    }
                    if (stalledAttempts >= MAX_STALLED_ATTEMPTS) {
                        break
                    }
                    if (System.nanoTime() >= deadline) break
                    delay((attempt * 500L).coerceAtMost(5_000L))
                    continue
                }
            val afterBytes = partial.length().coerceAtMost(MAX_SEGMENT_BYTES.toLong())
            if (outcome.complete) {
                val raw = partial.readBytes()
                val digest = MessageDigest.getInstance("SHA-256").digest(raw).toHex()
                if (!digest.equals(spec.sha256, ignoreCase = true)) {
                    if (outcome.integrityKnown) {
                        partial.delete()
                        throw IOException("danmaku segment digest mismatch")
                    }
                    lastError = IOException("danmaku segment digest mismatch")
                } else {
                    OtvLog.i(
                        "official danmaku segment load ok index=$index bytes=${raw.size} " +
                            "attempts=$attempt resumed=${outcome.rangeStart > 0} " +
                            "elapsedMs=${elapsedMsSince(startedAt)}",
                    )
                    return raw
                }
            }
            if (afterBytes <= beforeBytes) {
                stalledAttempts++
            } else {
                stalledAttempts = 0
            }
            if (stalledAttempts >= MAX_STALLED_ATTEMPTS) break
            delay((attempt * 500L).coerceAtMost(5_000L))
        }
        throw IOException(
            "danmaku segment transfer exhausted after $attempt attempts" +
                (lastError?.let { " (${it.safeType()})" } ?: ""),
        )
    }

    private suspend fun transferSegmentAttempt(
        url: String,
        partial: File,
        expectedStart: Long,
        index: Int,
        attempt: Int,
        callTimeoutMs: Long,
    ): SegmentTransfer = suspendCancellableCoroutine { continuation ->
        val requestBuilder =
            Request.Builder()
                .url(resolveHttpsUrl(url))
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .get()
        if (expectedStart > 0L) {
            requestBuilder.header("Range", "bytes=$expectedStart-")
        }
        val call =
            segmentHttp
                .newBuilder()
                .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
                .build()
                .newCall(requestBuilder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    try {
                        response.use {
                            val status = response.code
                            val contentLength = response.body?.contentLength() ?: -1L
                            val plan =
                                DongpianDanmakuTransferProtocol.responsePlan(
                                    status = status,
                                    expectedStart = expectedStart,
                                    contentRange = response.header("Content-Range"),
                                    contentLength = contentLength,
                                )
                            if (plan.action == DanmakuResponsePlan.Action.ALREADY_COMPLETE) {
                                continuation.resume(
                                    SegmentTransfer(
                                        complete = true,
                                        rangeStart = expectedStart,
                                        integrityKnown = true,
                                    ),
                                )
                                return
                            }
                            val append = plan.append
                            if (status == 200 && expectedStart > 0L) {
                                OtvLog.w(
                                    "official danmaku segment range ignored index=$index " +
                                        "attempt=$attempt requestedAt=$expectedStart",
                                )
                            }
                            val projected =
                                if (append) expectedStart + plan.bodyLength else plan.bodyLength
                            OtvLog.i(
                                "official danmaku segment response index=$index attempt=$attempt " +
                                    "status=$status contentLength=$contentLength " +
                                    "rangeLength=${plan.bodyLength} resumed=$append",
                            )
                            require(
                                plan.bodyLength < 0L ||
                                    projected <= MAX_SEGMENT_BYTES.toLong(),
                            ) { "danmaku resource too large" }
                            var received = 0L
                            var totalBytes = if (append) expectedStart else 0L
                            response.body?.byteStream()?.let { input ->
                                FileOutputStream(partial, append).use { output ->
                                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                                    while (true) {
                                        if (!continuation.isActive) {
                                            throw CancellationException("danmaku transfer cancelled")
                                        }
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        output.write(buffer, 0, read)
                                        received += read
                                        totalBytes += read
                                        require(
                                            plan.bodyLength < 0L ||
                                                received <= plan.bodyLength,
                                        ) { "danmaku response exceeds content length" }
                                        require(totalBytes <= MAX_SEGMENT_BYTES.toLong()) {
                                            "danmaku resource too large"
                                        }
                                    }
                                }
                            } ?: error("danmaku body missing")
                            continuation.resume(
                                SegmentTransfer(
                                    complete = when (plan.action) {
                                        DanmakuResponsePlan.Action.REPLACE ->
                                            plan.bodyLength < 0L || received == plan.bodyLength
                                        DanmakuResponsePlan.Action.APPEND ->
                                            (plan.bodyLength < 0L || received == plan.bodyLength) &&
                                                plan.total != null &&
                                                plan.contentEnd?.plus(1L) == plan.total
                                        DanmakuResponsePlan.Action.ALREADY_COMPLETE -> true
                                    },
                                    rangeStart = if (append) expectedStart else 0L,
                                    integrityKnown =
                                        when (plan.action) {
                                            DanmakuResponsePlan.Action.REPLACE -> plan.bodyLength >= 0L
                                            DanmakuResponsePlan.Action.APPEND ->
                                                plan.bodyLength == received &&
                                                    plan.total != null &&
                                                    plan.contentEnd?.plus(1L) == plan.total
                                            DanmakuResponsePlan.Action.ALREADY_COMPLETE -> true
                                        },
                                ),
                            )
                        }
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            },
        )
    }

    private fun download(
        url: String,
        maxBytes: Int,
        client: OkHttpClient = http,
    ): ByteArray {
        val request =
            Request.Builder()
                .url(resolveHttpsUrl(url))
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .get()
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("danmaku http ${response.code}")
            val length = response.body?.contentLength() ?: -1L
            require(length <= maxBytes) { "danmaku resource too large" }
            val bytes = response.body?.bytes() ?: error("danmaku body missing")
            require(bytes.size <= maxBytes) { "danmaku resource too large" }
            return bytes
        }
    }

    private fun resolveHttpsUrl(value: String): String {
        val uri = URI(value)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "danmaku URL must use HTTPS"
        }
        require(uri.userInfo.isNullOrBlank()) { "danmaku URL contains userinfo" }
        return uri.toString()
    }

    private fun decodeGzipIfNeeded(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 ||
            bytes[0] != 0x1f.toByte() ||
            bytes[1] != 0x8b.toByte()
        ) {
            return bytes
        }
        return GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_DECODED_BYTES) { "danmaku resource expands too large" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun firstString(
        json: JSONObject,
        vararg names: String,
    ): String =
        names.firstNotNullOfOrNull { name ->
            json.optString(name).trim().takeIf(String::isNotBlank)
        }.orEmpty()

    private fun firstLong(
        json: JSONObject,
        vararg names: String,
    ): Long? = names.firstNotNullOfOrNull { name ->
        if (json.has(name)) json.optLong(name) else null
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun Throwable.safeType(): String = javaClass.simpleName.ifBlank { "Error" }

    private fun elapsedMsSince(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private data class SegmentTransfer(
        val complete: Boolean,
        val rangeStart: Long,
        val integrityKnown: Boolean,
    )

    private companion object {
        const val MAX_CACHED_SEGMENTS = 2
        const val MAX_MANIFEST_BYTES = 256 * 1024
        const val MAX_SEGMENT_BYTES = 8 * 1024 * 1024
        const val MAX_DECODED_BYTES = 16 * 1024 * 1024
        const val DOWNLOAD_BUFFER_BYTES = 16 * 1024
        const val MAX_STALLED_ATTEMPTS = 3
        const val SEGMENT_TRANSFER_BUDGET_MINUTES = 6L
        const val MANIFEST_CACHE_MS = 5 * 60 * 1000L
        const val DEFAULT_RETRY_MS = 30_000L
        const val FAILED_RETRY_MS = 15_000L
        const val MIN_RETRY_MS = 5_000L
        const val MAX_RETRY_MS = 5 * 60 * 1000L
    }
}

internal object DongpianDanmakuParser {
    private val manifestSchemas = setOf("danmuku.manifest.v1", "danmaku.manifest.v1")
    private val segmentSchemas = setOf("danmuku.segment.v1", "danmaku.segment.v1")

    fun parseManifest(
        json: JSONObject,
        manifestUrl: String,
    ): DanmakuManifest {
        require(json.optString("schema_version") in manifestSchemas) {
            "unsupported danmaku manifest"
        }
        require(json.optString("timebase") == "milliseconds") {
            "unsupported danmaku timebase"
        }
        val segmentsJson = json.optJSONArray("segments") ?: JSONArray()
        val segments =
            (0 until segmentsJson.length()).mapNotNull { index ->
                val segment = segmentsJson.optJSONObject(index) ?: return@mapNotNull null
                val fromMs = segment.optLong("from_ms", -1L)
                val toMs = segment.optLong("to_ms", -1L)
                val url = segment.optString("url").trim()
                val sha256 = segment.optString("sha256").trim()
                require(fromMs >= 0L && toMs > fromMs && url.isNotBlank()) {
                    "invalid danmaku segment"
                }
                require(sha256.matches(Regex("^[a-f0-9]{64}$"))) {
                    "invalid danmaku segment digest"
                }
                require(segment.optString("content_encoding") == "gzip") {
                    "unsupported danmaku segment encoding"
                }
                DanmakuSegmentSpec(
                    fromMs = fromMs,
                    toMs = toMs,
                    count = segment.optInt("count", 0).coerceAtLeast(0),
                    sha256 = sha256,
                    url = resolveSegmentUrl(url, manifestUrl),
                )
            }
        var previousEnd = -1L
        segments.forEach { segment ->
            require(segment.fromMs >= previousEnd) { "overlapping danmaku segments" }
            previousEnd = segment.toMs
        }
        return DanmakuManifest(
            durationMs = json.optLong("duration_ms", 0L).takeIf { it > 0L },
            commentCount = json.optInt("comment_count", 0).coerceAtLeast(0),
            segments = segments,
        )
    }

    fun parseSegment(
        json: JSONObject,
        spec: DanmakuSegmentSpec,
    ): List<DanmakuItem> {
        require(json.optString("schema_version") in segmentSchemas) {
            "unsupported danmaku segment"
        }
        require(json.optString("timebase") == "milliseconds") {
            "unsupported danmaku timebase"
        }
        require(json.optLong("from_ms", -1L) == spec.fromMs && json.optLong("to_ms", -1L) == spec.toMs) {
            "danmaku segment range mismatch"
        }
        val items = json.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val raw = items.optJSONArray(index) ?: return@mapNotNull null
            if (raw.length() !in 5..6) return@mapNotNull null
            val timeMs = raw.optLong(0, -1L)
            val sourceMode = raw.optInt(1, -1)
            val color = raw.optInt(2, -1)
            val fontSize = raw.optInt(3, -1)
            val content = raw.optString(4).trim()
            if (timeMs !in spec.fromMs until spec.toMs ||
                sourceMode !in 1..9 ||
                color !in 0..0xFFFFFF ||
                fontSize !in 10..100 ||
                content.isBlank() ||
                content.length > 300
            ) {
                return@mapNotNull null
            }
            DanmakuItem(
                timeMs = timeMs,
                sourceMode = sourceMode,
                color = color,
                fontSize = fontSize,
                content = content,
                sourceId = raw.optString(5).trim().takeIf(String::isNotBlank),
            )
        }
    }

    fun resolveSegmentUrl(
        value: String,
        manifestUrl: String,
    ): String {
        val uri = URI(manifestUrl).resolve(value)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "danmaku segment URL must use HTTPS"
        }
        require(uri.userInfo.isNullOrBlank()) { "danmaku segment URL contains userinfo" }
        return uri.toString()
    }
}
