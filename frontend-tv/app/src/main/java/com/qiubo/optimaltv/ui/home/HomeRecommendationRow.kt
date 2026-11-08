package com.qiubo.optimaltv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavController
import com.qiubo.optimaltv.TvImageRequestSpec
import com.qiubo.optimaltv.TvImageSize
import com.qiubo.optimaltv.ui.components.RemotePoster
import com.qiubo.optimaltv.data.model.VodItem
import com.qiubo.optimaltv.ui.components.CARD_FOCUS_SCALE
import com.qiubo.optimaltv.ui.components.NAV_DOWN
import com.qiubo.optimaltv.ui.components.NAV_UP
import com.qiubo.optimaltv.ui.components.TvDirectionKeyHandler
import com.qiubo.optimaltv.ui.components.dpadFocusable
import com.qiubo.optimaltv.ui.theme.OtvColors
import com.qiubo.optimaltv.ui.theme.sx
import com.qiubo.optimaltv.ui.theme.sxs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.URLEncoder

@Composable
internal fun HomeRecommendationHeader(
    title: String,
    scale: Float,
) {
    Column(Modifier.fillMaxWidth().padding(top = 20f.sx(scale))) {
        Text(
            title,
            color = OtvColors.White,
            fontSize = 32f.sxs(scale),
            modifier = Modifier.padding(horizontal = 86f.sx(scale)),
        )
        Spacer(Modifier.height(18f.sx(scale)))
    }
}

/**
 * The poster strip is a separate LazyColumn item from its heading. On a
 * 1080p projector the next heading can be visible while the next strip is
 * still below the fold; keeping both in one item made Compose create seven
 * off-screen poster composition trees during every tab switch.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun HomeRecommendationRow(
    nav: NavController,
    entries: List<VodItem>,
    scale: Float,
    rowState: LazyListState,
    firstCardFocus: FocusRequester? = null,
    columnFocusRequesters: MutableMap<Int, FocusRequester>? = null,
    rows: Int = 1,
    loading: Boolean = false,
    onDownFromLastRow: ((Int) -> Boolean)? = null,
    onUpFromTopRow: ((Int) -> Boolean)? = null,
) {
    val context = LocalContext.current
    val posterSize = remember(context.resources.displayMetrics.widthPixels) {
        TvImageRequestSpec.posterSize(context.resources.displayMetrics.widthPixels)
    }
    val columns = remember(entries, rows) { recommendationColumns(entries, rows) }
    val focusedRow = remember { intArrayOf(0) }
    val focusedColumn = remember { intArrayOf(0) }
    val currentOnDownFromLastRow by rememberUpdatedState(onDownFromLastRow)
    val currentOnUpFromTopRow by rememberUpdatedState(onUpFromTopRow)
    val directionKeys = remember { TvDirectionKeyHandler() }
    val revealScope = rememberCoroutineScope()
    val activeRevealJob = remember { arrayOfNulls<Job>(1) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 20f.sx(scale)),
    ) {
        if (entries.isEmpty()) {
            Text(if (loading) "正在加载…" else "暂无内容", color = OtvColors.White55, fontSize = 24f.sxs(scale), modifier = Modifier.padding(horizontal = 86f.sx(scale)))
        } else {
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = 86f.sx(scale)),
                horizontalArrangement = Arrangement.spacedBy(24f.sx(scale)),
            ) {
                itemsIndexed(
                    items = columns,
                    key = { index, _ -> index },
                    contentType = { _, _ -> "home-poster-column" },
                ) { columnIndex, column ->
                    Column(verticalArrangement = Arrangement.spacedBy(20f.sx(scale))) {
                        column.forEachIndexed { rowIndex, entry ->
                            val cardReveal = remember(entry.id) { BringIntoViewRequester() }
                            Column(
                                Modifier
                                    .width(232f.sx(scale))
                                    .bringIntoViewRequester(cardReveal),
                            ) {
                                RemotePoster(
                                    url = entry.posterUrl,
                                    title = entry.title,
                                    seed = entry.id.hashCode(),
                                    imageSize = posterSize,
                                    modifier = Modifier
                                        .onFocusChanged {
                                            if (it.isFocused) {
                                                focusedRow[0] = rowIndex
                                                focusedColumn[0] = columnIndex
                                                activeRevealJob[0]?.cancel()
                                                activeRevealJob[0] =
                                                    revealScope.launch { cardReveal.bringIntoView() }
                                            }
                                        }
                                        .dpadFocusable(
                                            scaleFocused = CARD_FOCUS_SCALE,
                                            allowFocusScaleOnConstrained = true,
                                            focusHandle =
                                                columnFocusRequesters
                                                    ?.takeIf { rowIndex == 0 }
                                                    ?.getOrPut(columnIndex) { FocusRequester() }
                                                    ?: firstCardFocus.takeIf {
                                                        columnIndex == 0 && rowIndex == 0
                                                    },
                                            onClick = {
                                                nav.navigate("detail/" + URLEncoder.encode(entry.id, "UTF-8"))
                                            },
                                            keyOverride = keyOverride@{ event ->
                                                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                                                    activeRevealJob[0]?.cancel()
                                                }
                                                return@keyOverride directionKeys.handle(
                                                    keyCode = event.keyCode,
                                                    action = event.action,
                                                    onDirection = { direction ->
                                                        when (direction) {
                                                            NAV_DOWN -> {
                                                                val lastRow =
                                                                    columns
                                                                        .getOrNull(focusedColumn[0])
                                                                        ?.lastIndex ?: 0
                                                                focusedRow[0] >= lastRow &&
                                                                    currentOnDownFromLastRow
                                                                        ?.invoke(focusedColumn[0]) == true
                                                            }
                                                            NAV_UP ->
                                                                focusedRow[0] == 0 &&
                                                                    currentOnUpFromTopRow
                                                                        ?.invoke(focusedColumn[0]) == true
                                                            else -> false
                                                        }
                                                    },
                                                )
                                            },
                                        )
                                        .fillMaxWidth().height(348f.sx(scale)).background(OtvColors.Bg),
                                )
                                Spacer(Modifier.height(10f.sx(scale)))
                                Text(entry.title, color = OtvColors.White, fontSize = 24f.sxs(scale), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (column.size < rows) Spacer(Modifier.height(406f.sx(scale)))
                    }
                }
            }
        }
    }
}

internal fun recommendationColumns(entries: List<VodItem>, rows: Int): List<List<VodItem>> {
    require(rows in 1..2)
    val distinct = entries.distinctBy(VodItem::id)
    val width = (distinct.size + rows - 1) / rows
    return (0 until width).map { column ->
        (0 until rows).mapNotNull { row -> distinct.getOrNull(row * width + column) }
    }
}
