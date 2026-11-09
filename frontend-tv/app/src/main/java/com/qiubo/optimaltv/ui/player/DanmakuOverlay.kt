package com.qiubo.optimaltv.ui.player

import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qiubo.optimaltv.data.prefs.DanmakuDisplayArea
import com.qiubo.optimaltv.data.source.DanmakuItem
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.isActive

internal data class DanmakuPlacement(
    val item: DanmakuItem,
    val lane: Int,
    val endMs: Long,
)

internal object DanmakuLanePolicy {
    fun place(
        items: List<DanmakuItem>,
        laneCount: Int,
        viewportWidthPx: Float,
        travelSpeedPxPerMs: Float,
        gapPx: Float,
        widthEstimator: (DanmakuItem) -> Float,
    ): List<DanmakuPlacement> {
        if (laneCount <= 0 || viewportWidthPx <= 0f || travelSpeedPxPerMs <= 0f) {
            return emptyList()
        }
        val nextAvailable = LongArray(laneCount) { Long.MIN_VALUE }
        return items.sortedBy(DanmakuItem::timeMs).mapNotNull { item ->
            val lane = nextAvailable.indices.firstOrNull { nextAvailable[it] <= item.timeMs }
                ?: return@mapNotNull null
            val textWidthPx =
                widthEstimator(item)
                    .coerceAtLeast(1f)
                    .coerceAtMost(viewportWidthPx)
            val tailClearMs =
                kotlin.math.ceil((textWidthPx + gapPx.coerceAtLeast(0f)) / travelSpeedPxPerMs)
                    .toLong()
            val lifetimeMs =
                kotlin.math.ceil((viewportWidthPx + textWidthPx) / travelSpeedPxPerMs)
                    .toLong()
            val endMs = item.timeMs + lifetimeMs
            nextAvailable[lane] = item.timeMs + tailClearMs
            DanmakuPlacement(item, lane, endMs)
        }
    }
}

internal object DanmakuPresentationPolicy {
    const val BASE_TEXT_SIZE_SP = 24
    /** 12s across the viewport at 1.0x: the old 9s read as rushed on the projector. */
    const val BASE_TRAVEL_DURATION_MS = 12_000f

    fun laneCount(
        displayArea: DanmakuDisplayArea,
        viewportHeightPx: Int,
        topInsetPx: Float,
        laneHeightPx: Float,
    ): Int {
        if (viewportHeightPx <= 0 || laneHeightPx <= 0f) return 0
        val availableHeightPx = viewportHeightPx * displayArea.heightFraction - topInsetPx
        if (availableHeightPx <= 0f) return 0
        return floor(availableHeightPx / laneHeightPx).toInt().coerceIn(1, displayArea.maxLanes)
    }

    fun textSizeSp(baseSize: Int, fontScale: Float): Float =
        (baseSize * fontScale.coerceIn(0.75f, 1.25f)).coerceIn(14f, 36f)

    fun defaultTextSizeSp(fontScale: Float): Float =
        textSizeSp(BASE_TEXT_SIZE_SP, fontScale)

    fun estimatedTextWidthPx(
        content: String,
        textSizePx: Float,
    ): Float {
        val glyphCount =
            content.codePointCount(0, content.length)
                .coerceAtLeast(1)
        return glyphCount * textSizePx.coerceAtLeast(1f)
    }

    fun travelSpeedPxPerMs(viewportWidthPx: Float, scrollSpeed: Float): Float =
        viewportWidthPx.coerceAtLeast(0f) *
            scrollSpeed.coerceIn(0.5f, 2f) /
            BASE_TRAVEL_DURATION_MS
}

@Composable
internal fun DanmakuOverlay(
    items: List<DanmakuItem>,
    positionMs: Long,
    playbackRate: Float,
    isPlaying: Boolean,
    displayArea: DanmakuDisplayArea,
    fontScale: Float,
    opacity: Float,
    scrollSpeed: Float,
    modifier: Modifier = Modifier,
    // The video now owns a SurfaceView layer. Keep danmaku in the Compose window so the
    // S621 compositor never has to juggle two hardware surfaces during playback.
    surfaceRenderingEnabled: Boolean = false,
) {
    if (surfaceRenderingEnabled && !LocalInspectionMode.current) {
        DanmakuSurfaceOverlay(
            items = items,
            positionMs = positionMs,
            playbackRate = playbackRate,
            isPlaying = isPlaying,
            displayArea = displayArea,
            fontScale = fontScale,
            opacity = opacity,
            scrollSpeed = scrollSpeed,
            modifier = modifier,
        )
        return
    }
    DanmakuCanvasOverlay(
        items = items,
        positionMs = positionMs,
        playbackRate = playbackRate,
        isPlaying = isPlaying,
        displayArea = displayArea,
        fontScale = fontScale,
        opacity = opacity,
        scrollSpeed = scrollSpeed,
        modifier = modifier,
    )
}

@Composable
private fun DanmakuCanvasOverlay(
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
    if (items.isEmpty()) return
    var clockMs by remember { mutableLongStateOf(positionMs) }
    val latestPositionMs = rememberUpdatedState(positionMs)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val normalizedFontScale = fontScale.coerceIn(0.75f, 1.25f)
        val normalizedOpacity = opacity.coerceIn(0f, 1f)
        val normalizedScrollSpeed = scrollSpeed.coerceIn(0.5f, 2f)
        val widthPx = constraints.maxWidth.toFloat()
        val laneHeightPx = with(density) { (34.dp * normalizedFontScale).toPx() }
        val topInsetPx = with(density) { 22.dp.toPx() }
        val gapPx = with(density) { 28.dp.toPx() }
        val travelSpeedPxPerMs =
            DanmakuPresentationPolicy.travelSpeedPxPerMs(widthPx, normalizedScrollSpeed)
        val laneCount =
            DanmakuPresentationPolicy.laneCount(
                displayArea = displayArea,
                viewportHeightPx = constraints.maxHeight,
                topInsetPx = topInsetPx,
                laneHeightPx = laneHeightPx,
            )
        val schedule =
            remember(
                items,
                laneCount,
                widthPx,
                travelSpeedPxPerMs,
                gapPx,
                normalizedFontScale,
            ) {
                DanmakuLanePolicy.place(
                    items = items,
                    laneCount = laneCount,
                    viewportWidthPx = widthPx,
                    travelSpeedPxPerMs = travelSpeedPxPerMs,
                    gapPx = gapPx,
                ) { item ->
                    val textSizePx =
                        with(density) {
                            DanmakuPresentationPolicy
                                .defaultTextSizeSp(normalizedFontScale)
                                .sp
                                .toPx()
                        }
                    DanmakuPresentationPolicy.estimatedTextWidthPx(
                        content = item.content,
                        textSizePx = textSizePx,
                    )
                }
            }
        val activeWindow = remember(schedule) { DanmakuActiveWindow(schedule) }
        val paintCache =
            remember(schedule, normalizedFontScale, density) {
                DanmakuPaintCache(
                    normalizedFontScale = normalizedFontScale,
                    density = density,
                )
            }
        LaunchedEffect(playbackRate, isPlaying, schedule) {
            if (!isPlaying) {
                clockMs = latestPositionMs.value
                return@LaunchedEffect
            }
            var anchorPosition = latestPositionMs.value
            var anchorFrame = withFrameNanos { it }
            clockMs = anchorPosition
            while (isActive) {
                val frame = withFrameNanos { it }
                val elapsedMs = (frame - anchorFrame) / 1_000_000.0
                var nextClockMs =
                    anchorPosition + (elapsedMs * playbackRate.coerceAtLeast(0.1f)).toLong()
                if (abs(nextClockMs - latestPositionMs.value) > POSITION_CORRECTION_MS) {
                    anchorPosition = latestPositionMs.value
                    anchorFrame = frame
                    nextClockMs = anchorPosition
                }
                clockMs = nextClockMs
            }
        }
        LaunchedEffect(positionMs, isPlaying, schedule) {
            if (!isPlaying) {
                clockMs = positionMs
            }
        }

        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag("danmaku-overlay"),
        ) {
            // The live clock is read only here, so the overlay invalidates drawing without
            // recomposing or laying out one Compose Text node for every comment.
            val frameClockMs = clockMs
            val canvas = drawContext.canvas.nativeCanvas
            for (index in activeWindow.activeAt(frameClockMs)) {
                val placement = schedule[index]
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
                val paint = paintCache.get(item)
                paint.alpha = (renderedOpacity * 255f).roundToInt().coerceIn(0, 255)
                canvas.drawText(
                    item.content,
                    (widthPx - elapsedMs * travelSpeedPxPerMs).roundToInt().toFloat(),
                    (
                        topInsetPx +
                            placement.lane * laneHeightPx +
                            TEXT_SHADOW_PADDING_PX -
                            paint.fontMetrics.top
                        ),
                    paint,
                )
            }
        }
    }
}

private const val POSITION_CORRECTION_MS = 1_500L

private data class DanmakuPaintKey(
    val color: Int,
)

internal class DanmakuActiveWindow(
    private val placements: List<DanmakuPlacement>,
) {
    private val activeIndexes = ArrayList<Int>()
    private var nextStartIndex = 0
    private var lastClockMs = Long.MIN_VALUE

    /**
     * Returns the mutable index view for immediate drawing. The overlay calls this once per
     * frame and consumes the view synchronously, so steady playback only walks newly started
     * comments plus the small set currently on screen.
     */
    fun activeAt(clockMs: Long): List<Int> {
        if (clockMs < lastClockMs) {
            activeIndexes.clear()
            nextStartIndex = 0
        }

        while (
            nextStartIndex < placements.size &&
            placements[nextStartIndex].item.timeMs <= clockMs
        ) {
            val index = nextStartIndex++
            if (placements[index].endMs > clockMs) {
                activeIndexes += index
            }
        }

        var writeIndex = 0
        for (readIndex in activeIndexes.indices) {
            val index = activeIndexes[readIndex]
            if (placements[index].endMs > clockMs) {
                activeIndexes[writeIndex++] = index
            }
        }
        if (writeIndex < activeIndexes.size) {
            activeIndexes.subList(writeIndex, activeIndexes.size).clear()
        }

        lastClockMs = clockMs
        return activeIndexes
    }
}

private class DanmakuPaintCache(
    private val normalizedFontScale: Float,
    private val density: Density,
) {
    private val cache = LruCache<DanmakuPaintKey, AndroidPaint>(MAX_CACHED_PAINTS)

    fun get(item: DanmakuItem): AndroidPaint {
        val key =
            DanmakuPaintKey(
                color = item.color,
            )
        cache.get(key)?.let { return it }
        return render(item).also { cache.put(key, it) }
    }

    private fun render(item: DanmakuItem): AndroidPaint =
        AndroidPaint().apply {
            isAntiAlias = true
            isSubpixelText = true
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize =
                with(density) {
                    DanmakuPresentationPolicy
                        .defaultTextSizeSp(normalizedFontScale)
                        .sp
                        .toPx()
                }
            color =
                (0xFF000000.toInt() or
                    (item.color and 0xFFFFFF)).toInt()
            setShadowLayer(
                TEXT_SHADOW_BLUR_PX,
                TEXT_SHADOW_DX_PX,
                TEXT_SHADOW_DY_PX,
                android.graphics.Color.BLACK,
            )
        }

    private companion object {
        const val MAX_CACHED_PAINTS = 32
    }
}

private const val TEXT_SHADOW_BLUR_PX = 4f
private const val TEXT_SHADOW_DX_PX = 2f
private const val TEXT_SHADOW_DY_PX = 2f
private const val TEXT_SHADOW_PADDING_PX = 8f
