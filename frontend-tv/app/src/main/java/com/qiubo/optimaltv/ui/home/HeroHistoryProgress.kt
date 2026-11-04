package com.qiubo.optimaltv.ui.home

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import com.qiubo.optimaltv.data.db.HistoryEntity
import com.qiubo.optimaltv.ui.components.formatWatchHistoryLabel
import com.qiubo.optimaltv.ui.theme.OtvColors
import com.qiubo.optimaltv.ui.theme.sx
import com.qiubo.optimaltv.ui.theme.sxs
import kotlin.math.roundToInt

private val HistoryFontFamily = FontFamily.Serif

internal data class HeroHistoryPresentation(
    val episodeNumber: Int?,
    val progressFraction: Float,
    val percent: Int?,
)

internal fun heroHistoryPresentation(
    history: HistoryEntity,
    showEpisode: Boolean,
): HeroHistoryPresentation {
    val fraction =
        if (history.durationMs > 0L) {
            (history.positionMs.toFloat() / history.durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
    return HeroHistoryPresentation(
        episodeNumber = (history.epIndex + 1).takeIf { showEpisode },
        progressFraction = fraction,
        percent = (fraction * 100f).roundToInt().takeIf { history.durationMs > 0L },
    )
}

@Composable
internal fun HeroHistoryProgress(
    history: HistoryEntity,
    showEpisode: Boolean,
    s: Float,
    modifier: Modifier = Modifier,
) {
    val presentation = heroHistoryPresentation(history, showEpisode)
    Row(
        modifier = modifier.height(64f.sx(s)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val historyLabel = formatWatchHistoryLabel(presentation.episodeNumber, history.positionMs)
        Text(
            historyLabel,
            color = OtvColors.White,
            fontSize = 28f.sxs(s),
            fontFamily = HistoryFontFamily,
            fontWeight = FontWeight.SemiBold,
            textDecoration = TextDecoration.Underline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
