package com.qiubo.optimaltv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.qiubo.optimaltv.BuildConfig
import com.qiubo.optimaltv.OtvLog
import com.qiubo.optimaltv.TvImageRequestSpec
import com.qiubo.optimaltv.TvImageSize
import com.qiubo.optimaltv.TvPerformanceProfile
import com.qiubo.optimaltv.data.model.posterCandidatesFor
import kotlin.math.roundToInt

/**
 * Poster loader shared by TV cards.
 *
 * Some TV/projector WebP decoders produce an unusable hardware bitmap while the
 * original bytes are valid. Decode posters in software so a successful download
 * cannot end up as a blank card.
 */
@Composable
internal fun RemotePoster(
    url: String,
    title: String,
    seed: Int,
    modifier: Modifier = Modifier,
    showPlaceholder: Boolean = true,
    requestWidthPx: Int? = null,
    imageSize: TvImageSize? = null,
) {
    val context = LocalContext.current
    val resolvedImageSize = imageSize ?: remember(context, requestWidthPx) {
        val baseImageSize =
            TvImageRequestSpec.posterSize(context.resources.displayMetrics.widthPixels)
        requestWidthPx?.let { requestedWidth ->
            val width = requestedWidth.coerceAtLeast(1)
            TvImageSize(
                widthPx = width,
                heightPx =
                    (width.toFloat() * baseImageSize.heightPx / baseImageSize.widthPx)
                        .roundToInt()
                        .coerceAtLeast(1),
            )
        } ?: baseImageSize
    }
    val candidates = remember(url) { posterCandidatesFor(url) }
    var attempt by remember(url) { mutableIntStateOf(0) }
    val candidate = candidates.getOrNull(attempt)
    val placeholderBrush =
        remember(seed) {
            posterPlaceholderBrush(seed)
        }
    val placeholderModifier =
        if (showPlaceholder) {
            Modifier.drawBehind {
                drawRect(placeholderBrush)
            }
        } else {
            Modifier
        }
    Box(modifier.then(placeholderModifier)) {
        if (candidate != null) {
            val request =
                remember(context, candidate, resolvedImageSize) {
                    TvImageRequestSpec.posterRequest(context, candidate, resolvedImageSize)
                        .crossfade(false)
                        .build()
                }
            AsyncImage(
                model = request,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onLoading = {
                    if (candidate != candidates.getOrNull(attempt)) return@AsyncImage
                    if (BuildConfig.DEBUG && !TvPerformanceProfile.isConstrained) {
                        OtvLog.i(
                            "海报开始加载 ${posterAttemptLabel(attempt, candidates.size)}: " +
                                posterLogLabel(candidate),
                        )
                    }
                },
                onSuccess = { state ->
                    if (candidate != candidates.getOrNull(attempt)) return@AsyncImage
                    val drawable = state.result.drawable
                    val decoded =
                        drawable.intrinsicWidth > 1 &&
                            drawable.intrinsicHeight > 1
                    if (decoded) {
                        if (BuildConfig.DEBUG && !TvPerformanceProfile.isConstrained) {
                            OtvLog.i(
                                "海报加载成功 ${posterAttemptLabel(attempt, candidates.size)}: " +
                                    posterLogLabel(candidate) +
                                    " source=${state.result.dataSource}" +
                                    " decoded=${drawable.intrinsicWidth}x${drawable.intrinsicHeight}",
                            )
                        }
                    } else {
                        OtvLog.w(
                            "海报资源为空或透明占位 " +
                                "${posterAttemptLabel(attempt, candidates.size)}: " +
                                posterLogLabel(candidate),
                        )
                        attempt = nextPosterCandidateIndex(attempt, candidates.size)
                    }
                },
                onError = { state ->
                    if (candidate != candidates.getOrNull(attempt)) return@AsyncImage
                    OtvLog.w(
                        "海报加载失败: ${state.result.throwable.javaClass.simpleName} " +
                            "${posterAttemptLabel(attempt, candidates.size)} " +
                            "url=${posterLogLabel(candidate)}",
                    )
                    attempt = nextPosterCandidateIndex(attempt, candidates.size)
                },
            )
        }
    }
}

internal fun nextPosterCandidateIndex(
    currentIndex: Int,
    candidateCount: Int,
): Int {
    if (candidateCount <= 0) return 0
    return (currentIndex + 1).coerceIn(0, candidateCount - 1)
}

internal fun posterAttemptLabel(
    attempt: Int,
    candidateCount: Int,
): String = "[${attempt + 1}/$candidateCount]"

internal fun posterLogLabel(url: String): String {
    val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
    val host = uri?.host.orEmpty().ifBlank { "remote" }
    val file = uri?.lastPathSegment.orEmpty().ifBlank { "poster" }.takeLast(48)
    return "$host/$file"
}
