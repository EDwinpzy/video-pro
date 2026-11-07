package com.qiubo.optimaltv.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.qiubo.optimaltv.R
import kotlin.math.min
import kotlin.math.roundToInt

private val Black = Color(0xFF000000)
private val White = Color(0xFFF7F4F2)
private val Wine = Color(0xFF42000A)

/**
 * The restored Video Pro widescreen opener.
 *
 * The scene is authored against the original 1280x720 artwork. The wine field
 * fills the current display while the icon and wordmark retain their aspect
 * ratio. The 1.9-second motion starts on a fully black frame and finishes in
 * 1.5 seconds, then holds the completed wine field while the
 * destination becomes ready.
 */
@Composable
fun VideoProWidescreenStartup(
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    onFinished: () -> Unit = {},
) {
    val progress = remember(animate) {
        Animatable(if (animate) 0f else 1f)
    }
    val currentOnFinished by rememberUpdatedState(onFinished)
    LaunchedEffect(animate, progress) {
        if (!animate) return@LaunchedEffect
        if (ValueAnimator.areAnimatorsEnabled()) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = VideoProWidescreenTimeline.wallMotionDurationMillis.toInt(),
                    easing = LinearEasing,
                ),
            )
        } else {
            progress.snapTo(1f)
        }
        currentOnFinished()
    }

    VideoProWidescreenFrame(
        modifier = modifier,
        timeSeconds = {
            progress.value * VideoProWidescreenTimeline.logicalMotionEndSeconds
        },
    )
}

/**
 * Static frame surface used by device rendering tests. Production reads the
 * animated clock inside the draw phase so each frame invalidates drawing only.
 */
@Composable
internal fun VideoProWidescreenFrame(
    modifier: Modifier = Modifier,
    timeSeconds: () -> Float,
) {
    val icon = ImageBitmap.imageResource(R.drawable.video_pro_startup_icon)
    val videoWordmark = ImageBitmap.imageResource(R.drawable.video_pro_wordmark_video)
    val proWordmark = ImageBitmap.imageResource(R.drawable.video_pro_wordmark_pro)
    val appName = stringResource(R.string.app_name)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("video-pro-widescreen-startup")
            .semantics { contentDescription = appName }
            .tvReducedSemantics(),
    ) {
        drawRect(Black)
        drawWidescreenFrame(
            timeSeconds = timeSeconds(),
            icon = icon,
            videoWordmark = videoWordmark,
            proWordmark = proWordmark,
        )
    }
}

private fun DrawScope.drawWidescreenFrame(
    timeSeconds: Float,
    icon: ImageBitmap,
    videoWordmark: ImageBitmap,
    proWordmark: ImageBitmap,
) {
    val viewport = VideoProWidescreenViewport.forSize(size.width, size.height)
    val frame = VideoProWidescreenTimeline.frameAt(timeSeconds)
    val openingHeight = frame.openingHeight * viewport.curtainScaleY
    val top = (size.height - openingHeight) / 2f
    clipRect(
        left = 0f,
        top = top,
        right = size.width,
        bottom = top + openingHeight,
    ) {
        drawRect(color = Wine, size = size)
        withTransform({
            translate(viewport.brandOffsetX, viewport.brandOffsetY)
            scale(viewport.brandScale, viewport.brandScale, pivot = Offset.Zero)
        }) {
            drawBrandFrame(
                frame = frame,
                icon = icon,
                videoWordmark = videoWordmark,
                proWordmark = proWordmark,
            )
        }
    }
}

private fun DrawScope.drawBrandFrame(
    frame: VideoProWidescreenFrameState,
    icon: ImageBitmap,
    videoWordmark: ImageBitmap,
    proWordmark: ImageBitmap,
) {
    drawImage(
        image = icon,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(icon.width, icon.height),
        dstOffset = IntOffset(
            x = (frame.iconX - VideoProWidescreenTimeline.iconSize / 2f).roundToInt(),
            y = (VideoProWidescreenTimeline.iconCenterY - VideoProWidescreenTimeline.iconSize / 2f)
                .roundToInt(),
        ),
        dstSize = IntSize(
            width = VideoProWidescreenTimeline.iconSize.roundToInt(),
            height = VideoProWidescreenTimeline.iconSize.roundToInt(),
        ),
        filterQuality = FilterQuality.High,
    )
    clipRect(
        left = 560f,
        top = 120f,
        right = 1200f,
        bottom = 600f,
    ) {
        drawWordmark(
            image = videoWordmark,
            x = frame.videoX + VideoProWidescreenTimeline.videoWordmarkOffsetX,
            y = VideoProWidescreenTimeline.videoWordmarkTop,
        )
        drawWordmark(
            image = proWordmark,
            x = frame.proX + VideoProWidescreenTimeline.proWordmarkOffsetX,
            y = VideoProWidescreenTimeline.proWordmarkTop,
        )
    }
}

private fun DrawScope.drawWordmark(image: ImageBitmap, x: Float, y: Float) {
    withTransform({
        translate(x, y)
    }) {
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(image.width, image.height),
            filterQuality = FilterQuality.High,
        )
    }
}

internal data class VideoProWidescreenViewport(
    val curtainScaleY: Float,
    val brandScale: Float,
    val brandOffsetX: Float,
    val brandOffsetY: Float,
) {
    companion object {
        fun forSize(width: Float, height: Float): VideoProWidescreenViewport {
            val scaleX = width / VideoProWidescreenTimeline.virtualWidth
            val scaleY = height / VideoProWidescreenTimeline.virtualHeight
            val brandScale = min(scaleX, scaleY)
            return VideoProWidescreenViewport(
                curtainScaleY = scaleY,
                brandScale = brandScale,
                brandOffsetX =
                    (width - VideoProWidescreenTimeline.virtualWidth * brandScale) / 2f,
                brandOffsetY =
                    (height - VideoProWidescreenTimeline.virtualHeight * brandScale) / 2f,
            )
        }
    }
}

internal data class VideoProWidescreenFrameState(
    val openingHeight: Float,
    val iconX: Float,
    val videoX: Float,
    val proX: Float,
) {
    val coverage: Float
        get() = openingHeight / VideoProWidescreenTimeline.virtualHeight
}

/** Pure timing geometry copied from the selected original opener. */
internal object VideoProWidescreenTimeline {
    const val virtualWidth = 1280f
    const val virtualHeight = 720f
    const val logicalMotionEndSeconds = 1.9f
    const val previewLoopSeconds = 4.5f
    const val iconSize = 350f
    const val iconCenterY = 360f
    const val videoWordmarkTop = 268f
    const val videoWordmarkOffsetX = -1f
    const val proWordmarkTop = 373f
    const val proWordmarkOffsetX = 7f
    const val closedHeight = 0f
    const val fullHeight = virtualHeight

    const val wallMotionDurationMillis = 1_500L

    val previewLoopDurationMillis: Long
        get() = (previewLoopSeconds / logicalMotionEndSeconds * wallMotionDurationMillis).toLong()

    fun frameAt(timeSeconds: Float): VideoProWidescreenFrameState {
        val opening = smooth(phase(timeSeconds, start = 0.1f, duration = 1.8f))
        val movement = easeOut(phase(timeSeconds, start = 0.2f, duration = 1.5f))
        return VideoProWidescreenFrameState(
            openingHeight = mix(closedHeight, fullHeight, opening),
            iconX = mix(540f, 355f, movement),
            videoX = mix(1100f, 595f, movement),
            proX = mix(1200f, 595f, movement),
        )
    }

    private fun phase(timeSeconds: Float, start: Float, duration: Float): Float =
        ((timeSeconds - start) / duration).coerceIn(0f, 1f)

    private fun easeOut(value: Float): Float {
        val clamped = value.coerceIn(0f, 1f)
        return 1f - (1f - clamped) * (1f - clamped) * (1f - clamped) * (1f - clamped)
    }

    private fun smooth(value: Float): Float {
        val clamped = value.coerceIn(0f, 1f)
        return if (clamped < 0.5f) {
            8f * clamped * clamped * clamped * clamped
        } else {
            1f - ((-2f * clamped + 2f).let { it * it * it * it }) / 2f
        }
    }

    private fun mix(from: Float, to: Float, progress: Float): Float =
        from + (to - from) * progress
}
