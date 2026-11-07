package com.qiubo.optimaltv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import com.qiubo.optimaltv.ui.components.CARD_FOCUS_SCALE
import com.qiubo.optimaltv.ui.components.NAV_UP
import com.qiubo.optimaltv.ui.components.RemotePoster
import com.qiubo.optimaltv.ui.components.dpadFocusable
import com.qiubo.optimaltv.ui.components.rememberTvLazyListState
import com.qiubo.optimaltv.ui.theme.OtvColors
import com.qiubo.optimaltv.ui.theme.sx
import com.qiubo.optimaltv.ui.theme.sxs

@Composable
internal fun RecentWatchRow(
    items: List<RecentWatchEntry>,
    scale: Float,
    firstCardFocus: FocusRequester? = null,
    onUpFromRow: (() -> Boolean)? = null,
    onOpen: (RecentWatchEntry) -> Unit,
) {
    val rowState = rememberTvLazyListState()
    LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = 86f.sx(scale)),
        horizontalArrangement = Arrangement.spacedBy(24f.sx(scale)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(
            items = items,
            key = { _, item -> item.history.vodId },
            contentType = { _, _ -> "recent-watch-card" },
        ) { index, item ->
            RecentWatchCard(
                item = item,
                scale = scale,
                focusRequester = firstCardFocus.takeIf { index == 0 },
                onUpFromRow = onUpFromRow,
                onClick = { onOpen(item) },
            )
        }
    }
}

@Composable
private fun RecentWatchCard(
    item: RecentWatchEntry,
    scale: Float,
    focusRequester: FocusRequester?,
    onUpFromRow: (() -> Boolean)?,
    onClick: () -> Unit,
) {
    Column(Modifier.width(232f.sx(scale))) {
        Box(
            Modifier
                .then(
                    if (focusRequester != null) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    },
                )
                .dpadFocusable(
                    scaleFocused = CARD_FOCUS_SCALE,
                    allowFocusScaleOnConstrained = true,
                    navOverride = { direction ->
                        direction == NAV_UP && onUpFromRow?.invoke() == true
                    },
                    onClick = onClick,
                )
                .fillMaxWidth()
                .height(352f.sx(scale))
                .clip(RoundedCornerShape(12f.sx(scale))),
        ) {
            RemotePoster(
                url = item.posterUrl,
                title = item.title,
                seed = item.history.vodId.hashCode(),
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                item.progressLabel,
                color = OtvColors.White,
                fontSize = 17f.sxs(scale),
                lineHeight = 20f.sxs(scale),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6f.sx(scale))
                        .background(ColorScrim, RoundedCornerShape(5f.sx(scale)))
                        .padding(horizontal = 8f.sx(scale), vertical = 3f.sx(scale)),
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4f.sx(scale))
                    .background(OtvColors.White.copy(alpha = 0.18f)),
            ) {
                if (item.progress > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(item.progress)
                            .fillMaxHeight()
                            .background(OtvColors.White.copy(alpha = 0.92f)),
                    )
                }
            }
        }
        Spacer(Modifier.height(10f.sx(scale)))
        Text(
            item.title,
            color = OtvColors.White,
            fontSize = 21f.sxs(scale),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val ColorScrim = androidx.compose.ui.graphics.Color(0x99666668)
