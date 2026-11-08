package com.qiubo.optimaltv.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.LruCache
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.qiubo.optimaltv.data.prefs.DanmakuDisplayArea
import com.qiubo.optimaltv.data.source.DanmakuItem
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
internal fun DanmakuSurfaceOverlay(
    items: List<DanmakuItem>,
    positionMs: Long,
    playbackRate: Float,
    isPlaying: Boolean,
    displayArea: DanmakuDisplayArea,
    fontScale: Float,
    opacity: Float,
    scrollSpeed: Float,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val scheduleToken =
        remember(items, displayArea, fontScale, scrollSpeed) {
            Any()
        }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { context -> DanmakuRenderSurfaceView(context) },
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(maxHeight * displayArea.heightFraction)
                    .testTag("danmaku-overlay"),
            update = { view ->
                view.update(
                    DanmakuSurfaceFrame(
                        items = items,
                        positionMs = positionMs,
                        playbackRate = playbackRate,
                        isPlaying = isPlaying,
                        displayArea = displayArea,
                        fontScale = fontScale,
                        opacity = opacity,
                        scrollSpeed = scrollSpeed,
                        density = density.density,
                        spToPx = with(density) { 1.sp.toPx() },
                        scheduleToken = scheduleToken,
                    ),
                )
            },
        )
    }
}

private data class DanmakuSurfaceFrame(
    val items: List<DanmakuItem>,
    val positionMs: Long,
    val playbackRate: Float,
    val isPlaying: Boolean,
    val displayArea: DanmakuDisplayArea,
    val fontScale: Float,
    val opacity: Float,
    val scrollSpeed: Float,
    val density: Float,
    val spToPx: Float,
    val scheduleToken: Any,
)

private class DanmakuRenderSurfaceView(
    context: Context,
) : SurfaceView(context),
    SurfaceHolder.Callback {
    private val renderer = DanmakuSurfaceRenderer(holder)

    init {
        isClickable = false
        isFocusable = false
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
        setZOrderOnTop(false)
        holder.addCallback(this)
    }

    fun update(frame: DanmakuSurfaceFrame) {
        renderer.update(frame)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderer.start()
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        renderer.resize(width, height)
        renderer.start()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderer.stop()
    }

    override fun onDetachedFromWindow() {
        renderer.stop()
        super.onDetachedFromWindow()
    }
}

private class DanmakuSurfaceRenderer(
    private val holder: SurfaceHolder,
) {
    @Volatile private var latestFrame: DanmakuSurfaceFrame? = null
    @Volatile private var needsWindowReset = true
    @Volatile private var viewportWidthPx = 0
    @Volatile private var viewportHeightPx = 0

    private val playbackClock = DanmakuPlaybackClock()
    private var renderThread: HandlerThread? = null
    private var scheduleToken: Any? = null
    private var scheduleWidthPx = 0
    private var scheduleHeightPx = 0
    private var schedule = emptyList<DanmakuPlacement>()
    private var activeWindow: DanmakuActiveWindow? = null
    private var textBitmapCache: SurfaceTextBitmapCache? = null
    private var cacheWidthPx = 0
    private var cacheFontScale = 0f
    private var cacheSpToPx = 0f
    private val bitmapPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private var lastDrawNanos = 0L
    private var statsStartedNanos = 0L
    private var statsFrameCount = 0
    private var statsActiveCount = 0
    private var statsActiveMax = 0
    private var statsDrawNanos = 0L
    private var statsHardwareFrames = 0

    fun update(frame: DanmakuSurfaceFrame) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        if (
            playbackClock.update(
                positionMs = frame.positionMs,
                realtimeMs = nowNanos / 1_000_000,
                isPlaying = frame.isPlaying,
                playbackRate = frame.playbackRate,
            )
        ) {
            needsWindowReset = true
        }
        latestFrame = frame
    }

    fun resize(
        width: Int,
        height: Int,
    ) {
        viewportWidthPx = width
        viewportHeightPx = height
    }

    @Synchronized
    fun start() {
        if (renderThread?.isAlive == true) return
        val thread =
            HandlerThread("otv-danmaku-render").also {
                renderThread = it
                it.start()
            }
        Handler(thread.looper).post {
            val choreographer = Choreographer.getInstance()
            val callback =
                object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (renderThread !== thread) return
                        drawIfDue(frameTimeNanos)
                        choreographer.postFrameCallback(this)
                    }
                }
            choreographer.postFrameCallback(callback)
        }
    }

    @Synchronized
    fun stop() {
        val thread = renderThread ?: return
        renderThread = null
        thread.quitSafely()
    }

    private fun drawIfDue(frameTimeNanos: Long) {
        if (frameTimeNanos - lastDrawNanos < FRAME_INTERVAL_NANOS) return
        lastDrawNanos = frameTimeNanos
        val frame = latestFrame ?: return
        val widthPx = viewportWidthPx
        val heightPx = viewportHeightPx
        if (widthPx <= 0 || heightPx <= 0 || !holder.surface.isValid) return

        val drawStartedNanos = SystemClock.elapsedRealtimeNanos()
        ensureSchedule(frame, widthPx, heightPx)
        if (needsWindowReset) {
            activeWindow = DanmakuActiveWindow(schedule)
            needsWindowReset = false
        }
        val hardwareCanvas = runCatching { holder.lockHardwareCanvas() }.getOrNull()
        val canvas = hardwareCanvas ?: runCatching { holder.lockCanvas() }.getOrNull() ?: return
        var activeCount = 0
        try {
            canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val placementList = schedule
            if (placementList.isEmpty()) return
            val normalizedOpacity = frame.opacity.coerceIn(0f, 1f)
            val normalizedScrollSpeed = frame.scrollSpeed.coerceIn(0.5f, 2f)
            val normalizedFontScale = frame.fontScale.coerceIn(0.75f, 1.25f)
            val laneHeightPx = 34f * frame.density * normalizedFontScale
            val topInsetPx = 22f * frame.density
            val travelSpeedPxPerMs = widthPx * normalizedScrollSpeed / 6_500f
            val frameClockMs =
                playbackClock.positionAt(
                    realtimeMs = SystemClock.elapsedRealtimeNanos() / 1_000_000,
                    playbackRate = frame.playbackRate,
                )
            val active = activeWindow?.activeAt(frameClockMs) ?: return
            activeCount = active.size
            val cache = textBitmapCache ?: return

            for (index in active) {
                val placement = placementList.getOrNull(index) ?: continue
                val item = placement.item
                val durationMs = (placement.endMs - item.timeMs).coerceAtLeast(1L)
                val remainingFraction =
                    ((placement.endMs - frameClockMs).toFloat() / durationMs.toFloat())
                        .coerceIn(0f, 1f)
                val fade =
                    if (item.sourceMode == 4 || item.sourceMode == 5) {
                        remainingFraction
                    } else {
                        1f
                    }
                val renderedOpacity = (fade * normalizedOpacity).coerceIn(0f, 1f)
                if (renderedOpacity <= 0f) continue

                val elapsedMs = (frameClockMs - item.timeMs).coerceAtLeast(0L)
                val rendered = cache.get(item)
                bitmapPaint.alpha = (renderedOpacity * 255f).roundToInt().coerceIn(0, 255)
                canvas.drawBitmap(
                    rendered.bitmap,
                    (widthPx - elapsedMs * travelSpeedPxPerMs).roundToInt().toFloat() -
                        rendered.paddingPx,
                    (topInsetPx + placement.lane * laneHeightPx).roundToInt().toFloat() -
                        rendered.paddingPx,
                    bitmapPaint,
                )
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
            recordStats(
                drawNanos = SystemClock.elapsedRealtimeNanos() - drawStartedNanos,
                activeCount = activeCount,
                hardware = hardwareCanvas != null,
            )
        }
    }

    private fun recordStats(
        drawNanos: Long,
        activeCount: Int,
        hardware: Boolean,
    ) {
        if (statsStartedNanos == 0L) statsStartedNanos = SystemClock.elapsedRealtimeNanos()
        statsFrameCount++
        statsActiveCount += activeCount
        statsActiveMax = maxOf(statsActiveMax, activeCount)
        statsDrawNanos += drawNanos
        if (hardware) statsHardwareFrames++
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - statsStartedNanos < STATS_WINDOW_NANOS) return
        val elapsedMs = (now - statsStartedNanos) / 1_000_000.0
        val fps = statsFrameCount * 1_000.0 / elapsedMs
        val averageDrawMs = statsDrawNanos / statsFrameCount / 1_000_000.0
        val averageActive = statsActiveCount.toDouble() / statsFrameCount
        com.qiubo.optimaltv.OtvLog.i(
            "danmaku surface stats fps=${"%.1f".format(fps)} " +
                "drawMs=${"%.2f".format(averageDrawMs)} activeAvg=${"%.1f".format(averageActive)} " +
                "activeMax=$statsActiveMax hardware=$statsHardwareFrames/$statsFrameCount",
        )
        statsStartedNanos = now
        statsFrameCount = 0
        statsActiveCount = 0
        statsActiveMax = 0
        statsDrawNanos = 0
        statsHardwareFrames = 0
    }

    private fun ensureSchedule(
        frame: DanmakuSurfaceFrame,
        widthPx: Int,
        heightPx: Int,
    ) {
        if (
            scheduleToken === frame.scheduleToken &&
            scheduleWidthPx == widthPx &&
            scheduleHeightPx == heightPx
        ) {
            return
        }
        val normalizedFontScale = frame.fontScale.coerceIn(0.75f, 1.25f)
        val normalizedScrollSpeed = frame.scrollSpeed.coerceIn(0.5f, 2f)
        val laneHeightPx = 34f * frame.density * normalizedFontScale
        val topInsetPx = 22f * frame.density
        val gapPx = 28f * frame.density
        val travelSpeedPxPerMs = widthPx * normalizedScrollSpeed / 6_500f
        val laneCount =
            DanmakuPresentationPolicy
                .laneCount(
                    displayArea = DanmakuDisplayArea.FULL,
                    viewportHeightPx = heightPx,
                    topInsetPx = topInsetPx,
                    laneHeightPx = laneHeightPx,
                ).coerceAtMost(frame.displayArea.maxLanes)
        schedule =
            DanmakuLanePolicy.place(
                items = frame.items,
                laneCount = laneCount,
                viewportWidthPx = widthPx.toFloat(),
                travelSpeedPxPerMs = travelSpeedPxPerMs,
                gapPx = gapPx,
            ) { item ->
                val textSizePx =
                    DanmakuPresentationPolicy.defaultTextSizeSp(normalizedFontScale) *
                        frame.spToPx
                DanmakuPresentationPolicy.estimatedTextWidthPx(
                    content = item.content,
                    textSizePx = textSizePx,
                )
            }
        activeWindow = DanmakuActiveWindow(schedule)
        if (
            cacheWidthPx != widthPx ||
            cacheFontScale != normalizedFontScale ||
            cacheSpToPx != frame.spToPx
        ) {
            textBitmapCache =
                SurfaceTextBitmapCache(
                    viewportWidthPx = widthPx,
                    normalizedFontScale = normalizedFontScale,
                    density = frame.density,
                    spToPx = frame.spToPx,
                )
            cacheWidthPx = widthPx
            cacheFontScale = normalizedFontScale
            cacheSpToPx = frame.spToPx
        }
        scheduleWidthPx = widthPx
        scheduleHeightPx = heightPx
        scheduleToken = frame.scheduleToken
    }

    private companion object {
        const val FRAME_INTERVAL_NANOS = 16_666_667L
        const val STATS_WINDOW_NANOS = 5_000_000_000L
    }
}

private data class SurfaceTextKey(
    val content: String,
    val color: Int,
)

private data class SurfaceRenderedDanmaku(
    val bitmap: Bitmap,
    val paddingPx: Int,
)

private class SurfaceTextBitmapCache(
    private val viewportWidthPx: Int,
    private val normalizedFontScale: Float,
    private val density: Float,
    private val spToPx: Float,
) {
    private val cache =
        object : LruCache<SurfaceTextKey, SurfaceRenderedDanmaku>(MAX_CACHE_BYTES) {
            override fun sizeOf(
                key: SurfaceTextKey,
                value: SurfaceRenderedDanmaku,
            ): Int = value.bitmap.allocationByteCount
        }

    fun get(item: DanmakuItem): SurfaceRenderedDanmaku {
        val key =
            SurfaceTextKey(
                content = item.content,
                color = item.color,
            )
        cache.get(key)?.let { return it }
        return render(item).also { cache.put(key, it) }
    }

    private fun render(item: DanmakuItem): SurfaceRenderedDanmaku {
        val textSizePx =
            DanmakuPresentationPolicy.defaultTextSizeSp(normalizedFontScale) * spToPx
        val fillPaint =
            AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                textSize = textSizePx
                color = (0xFF000000.toInt() or (item.color and 0xFFFFFF)).toInt()
            }
        val strokePaint =
            AndroidPaint(fillPaint).apply {
                style = AndroidPaint.Style.STROKE
                strokeWidth = (textSizePx * 0.08f).coerceAtLeast(2f * density)
                color = 0xCC000000.toInt()
            }
        val metrics = fillPaint.fontMetrics
        val paddingPx = ceil(strokePaint.strokeWidth / 2f).toInt() + 2
        val measuredWidthPx = ceil(strokePaint.measureText(item.content)).toInt()
        val widthPx =
            (measuredWidthPx + paddingPx * 2)
                .coerceIn(1, viewportWidthPx.coerceAtLeast(1))
        val heightPx =
            (ceil(metrics.descent - metrics.ascent).toInt() + paddingPx * 2)
                .coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val baseline = paddingPx - metrics.top
        canvas.drawText(item.content, paddingPx.toFloat(), baseline, strokePaint)
        canvas.drawText(item.content, paddingPx.toFloat(), baseline, fillPaint)
        return SurfaceRenderedDanmaku(bitmap, paddingPx)
    }

    private companion object {
        const val MAX_CACHE_BYTES = 12 * 1024 * 1024
    }
}
