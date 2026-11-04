package com.qiubo.optimaltv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.qiubo.optimaltv.TvPerformanceProfile

/** TV 遥控交互的统一时序策略：短促、可预测，并与显示帧同步。 */
internal object TvInteractionMotion {
    private const val DIRECTION_REPEAT_INTERVAL_MS = 65L
    const val SCROLL_REVEAL_LAYOUT_WAIT_MS = 32L

    val focusScaleSpec: FiniteAnimationSpec<Float> = tween(
        durationMillis = 90,
        easing = FastOutSlowInEasing,
    )

    /**
     * 页面为了完整露出新焦点行而补滚时，不能用瞬时位移：一次数百像素的
     * 0 帧跳变会被误认为整页闪了一下。低配设备保留 120ms 短动画，既能
     * 看清移动方向，也不会像长缓动那样阻塞连续按方向键。
     */
    val focusRevealScrollSpec: FiniteAnimationSpec<Float>
        get() = tween(
            durationMillis = if (TvPerformanceProfile.isConstrained) 120 else 180,
            easing = FastOutSlowInEasing,
        )

    fun shouldProcessDirection(repeatCount: Int, elapsedMs: Long): Boolean =
        repeatCount == 0 || elapsedMs >= DIRECTION_REPEAT_INTERVAL_MS
}

/**
 * Colour state for TV focus/content changes. Constrained devices get the final
 * colour immediately so a D-pad move does not schedule an animation frame burst
 * on top of the layout pass.
 */
@Composable
internal fun tvAnimatedColor(
    target: Color,
    label: String,
    animationSpec: AnimationSpec<Color> = spring(),
): State<Color> =
    if (TvPerformanceProfile.navigationMotionEnabled) {
        animateColorAsState(target, animationSpec, label = label)
    } else {
        rememberUpdatedState(target)
    }

/**
 * Hides the screen's semantic tree only for the constrained-projector + vendor
 * key-scanner case. Compose focus, key handling and clicks are unaffected.
 */
internal fun Modifier.tvReducedSemantics(): Modifier =
    if (TvPerformanceProfile.suppressFocusSemantics) {
        clearAndSetSemantics { }
    } else {
        this
    }
