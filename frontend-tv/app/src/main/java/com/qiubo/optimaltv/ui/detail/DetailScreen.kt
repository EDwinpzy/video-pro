package com.qiubo.optimaltv.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.qiubo.optimaltv.data.model.formatTime
import com.qiubo.optimaltv.data.model.SourceAvailability
import com.qiubo.optimaltv.ui.components.FocusRegistry
import com.qiubo.optimaltv.ui.components.InitialFocusEffect
import com.qiubo.optimaltv.ui.components.OtvNav
import com.qiubo.optimaltv.ui.components.ProvideNavScrolls
import com.qiubo.optimaltv.ui.components.ReturnFocus
import com.qiubo.optimaltv.ui.components.RemotePoster
import com.qiubo.optimaltv.ui.components.dpadFocusable
import com.qiubo.optimaltv.ui.components.episodeEntryCustomName
import com.qiubo.optimaltv.ui.components.lazyNavContainer
import com.qiubo.optimaltv.ui.components.tvReducedSemantics
import com.qiubo.optimaltv.ui.theme.AccentBlue
import com.qiubo.optimaltv.ui.theme.OtvColors
import com.qiubo.optimaltv.ui.theme.rememberUiScale
import com.qiubo.optimaltv.ui.theme.sx
import com.qiubo.optimaltv.ui.theme.sxs
import java.net.URLEncoder

/**
 * 详情页（一屏式，1920×1080 设计坐标系）：
 * 上部 = 海报(320×485) + 信息列（片名46 Bold / meta+评分 / 主演 / 简介3行 / 续播提示 / 按钮行 /
 * 选集区（仅真实分集内容；电影的国语/中字等版本交给播放器自动选源）/
 * >20 集分页 tab 每页 20 集 6 列）。详情页不暴露信号源选择。
 * 整体在 weight(1f) 盒内垂直居中（需求 #13）；内容总高按 ≤1080 设计，不滚动不出屏。
 */
@Composable
fun DetailScreen(nav: NavController, vodIdArg: String) {
    val vodId = java.net.URLDecoder.decode(vodIdArg, "UTF-8")
    val vm: DetailViewModel = viewModel(
        key = "detail:$vodId",
        factory = simpleFactory { DetailViewModel(vodId) },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val fav by vm.isFavorite.collectAsStateWithLifecycle()
    val douban by vm.douban.collectAsStateWithLifecycle()
    val s = rememberUiScale()
    // 返回落焦（需求 影视#6-2）：从播放页返回时回到离开前的按钮/选集格
    val returnKey = remember { ReturnFocus.take("detail") }
    val cardReg = remember { FocusRegistry() }

    val item = ui.item
    if (item == null) {
        Box(Modifier.fillMaxSize().background(OtvColors.Bg), contentAlignment = Alignment.Center) {
            Text("加载中…", color = OtvColors.White50, fontSize = 28f.sxs(s))
        }
        return
    }

    // 初始焦点落在主按钮上（返回本页时重新聚焦）；有返回记录则回到记录位置
    val primaryFocus = remember { FocusRequester() }
    InitialFocusEffect(primaryFocus, "detail-play", enabled = returnKey == null)
    // 本页无标签栏：清空 topFocus，防 UP 到顶逃逸到已销毁屏的 tab 句柄
    LaunchedEffect(Unit) { OtvNav.topFocus = null }
    var restoredFocus by remember { mutableStateOf(false) }
    LaunchedEffect(item.id, item.sourceId) {
        val key = returnKey ?: return@LaunchedEffect
        if (restoredFocus) return@LaunchedEffect
        repeat(40) {
            if (runCatching { cardReg.fr(key).requestFocus() }.isSuccess) {
                restoredFocus = true
                return@LaunchedEffect
            }
            delay(100)
        }
        if (runCatching { primaryFocus.requestFocus() }.isSuccess) {
            restoredFocus = true
        }
    }

    Column(
        // 需求 #13：详情页内容上下居中（一屏内）。注意不能给选集区 weight(1f)——
        // weighted 子项会吸走全部剩余空间，把海报/信息区顶到最上（居中失效根因）。
        // 改为：信息区放 weight(1f) 的 Box 内居中；选集区自然高度、超高才内部滚动。
        Modifier
            .fillMaxSize()
            .background(OtvColors.Bg)
            .tvReducedSemantics()
            .padding(horizontal = 80f.sx(s)),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column {
                Row {
                    // 海报 320×485（5:7）
                    val detailPosterWidthPx =
                        with(LocalDensity.current) {
                            320f.sx(s).roundToPx()
                        }
                    Box(
                        Modifier
                            .width(320f.sx(s))
                            .aspectRatio(232f / 352f)
                            .clip(RoundedCornerShape(16f.sx(s))),
                    ) {
                        RemotePoster(
                            url = item.posterUrl,
                            title = item.title,
                            seed = item.id.hashCode(),
                            modifier = Modifier.matchParentSize(),
                            requestWidthPx = detailPosterWidthPx,
                        )
                        // 豆瓣海报叠层（原版预探测替换：加载失败透明露出下层 hhkan 图）
                        douban?.poster?.takeIf { it.isNotBlank() }?.let { dp ->
                            RemotePoster(
                                url = dp,
                                title = item.title,
                                seed = dp.hashCode(),
                                modifier = Modifier.matchParentSize(),
                                showPlaceholder = false,
                                requestWidthPx = detailPosterWidthPx,
                            )
                        }
                        Box(
                            Modifier
                                .matchParentSize()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))),
                        )
                    }
                    Spacer(Modifier.width(48f.sx(s)))
                    // 信息列（与海报同排，内容自然排布）
                    Column(Modifier.weight(1f).padding(top = 8f.sx(s))) {
                        // 片名（醒目，两行封顶）
                        Text(
                            item.title.ifBlank { "未命名影片" },
                            color = OtvColors.White, fontSize = 46f.sxs(s), fontWeight = FontWeight.Bold,
                            lineHeight = 56f.sxs(s), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(12f.sx(s)))
                        // meta 行：2026 / 美国 / 爱情片 + 评分
                        val metaLine = item.meta.ifBlank {
                            (listOf(item.year, item.area).filter { it.isNotBlank() } + item.tags).joinToString(" / ")
                        }
                        val dr = douban?.rating?.toDoubleOrNull()
                        val ratingLine = when {
                            dr != null && dr > 0 -> "豆瓣 %.1f".format(dr)
                            item.rating > 0 -> "评分 %.1f".format(item.rating)
                            else -> ""
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (metaLine.isNotBlank()) {
                                Text(
                                    metaLine, color = OtvColors.White70, fontSize = 27f.sxs(s),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                            if (ratingLine.isNotBlank()) {
                                Spacer(Modifier.width(18f.sx(s)))
                                Text("★ $ratingLine", color = Color(0xFFFFD60A), fontSize = 27f.sxs(s), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (item.actors.isNotBlank()) {
                            Spacer(Modifier.height(10f.sx(s)))
                            Text(
                                "主演  " + item.actors,
                                color = OtvColors.White55, fontSize = 24f.sxs(s),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(12f.sx(s)))
                        Text(
                            (douban?.desc?.takeIf { it.isNotBlank() } ?: item.desc).ifBlank { "暂无简介" },
                            color = OtvColors.White70, fontSize = 26f.sxs(s),
                            lineHeight = 37f.sxs(s), maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(20f.sx(s)))
                        val resumeEntryLabel =
                            episodeEntryCustomName(ui.resumeEpIndex, item.episodes.getOrNull(ui.resumeEpIndex)?.name)
                                ?: "第${ui.resumeEpIndex + 1}集"
                        // 续播提示
                        if (ui.resumeEpIndex >= 0) {
                            Text(
                                "上次看到$resumeEntryLabel ${formatTime(ui.resumePositionMs)}",
                                color = Color(0xFFFFD60A), fontSize = 24f.sxs(s),
                            )
                            Spacer(Modifier.height(14f.sx(s)))
                        }
                        // 主按钮行（原版 Go To Show 规格：min 280 / h64 / r9.7 / 29.3 Bold）
                        // v1.13：层容差由引擎自缩放（0.5×较小高度），不再手工调参
                        Row(horizontalArrangement = Arrangement.spacedBy(18f.sx(s))) {
                            val playLabel = when (ui.sourceAvailability) {
                                SourceAvailability.MATCHING -> "正在查找片源"
                                SourceAvailability.UNAVAILABLE -> "暂无片源"
                                SourceAvailability.AVAILABLE -> if (ui.resumeEpIndex >= 0) "继续播放 $resumeEntryLabel" else "立即播放"
                            }
                            ActionButton(
                                text = playLabel, primary = true, focusRequester = primaryFocus,
                                cardReg = cardReg, cardKey = "primary",
                                enabled = ui.sourceAvailability == SourceAvailability.AVAILABLE,
                            ) {
                                val ep = if (ui.resumeEpIndex >= 0) ui.resumeEpIndex else 0
                                // 详情页固定传入好好看主条目；补充源由播放器内部自动路由。
                                val resumeArg = if (ui.resumeEpIndex >= 0) "?resumeMs=${ui.resumePositionMs}" else ""
                                nav.navigate("player/" + URLEncoder.encode(vodId, "UTF-8") + "/$ep" + resumeArg)
                            }
                            ActionButton(
                                text = if (fav) "★ 已收藏" else "☆ 收藏",
                                primary = false,
                            ) { vm.toggleFavorite() }
                            if (ui.resumeEpIndex >= 0) {
                                ActionButton(text = "从头看", primary = false) { vm.clearProgress() }
                            }
                        }

                        // 选集区（需求⑧：紧贴按钮行下方，不再被居中盒推到屏幕底边；
                        // 需求⑩：区域并入常规流式布局后高度稳定、无溢出遮挡，行带导航可正常移动。
                        // 电影单集不渲染；>20 集分页 tab（每页 20 集 6 列 ≤4 行），页签超宽横向滚动）
                        if (shouldShowEpisodePicker(item)) {
                            Spacer(Modifier.height(24f.sx(s)))
                            if (item.episodes.size > 20) {
                                val pageCount = (item.episodes.size + 19) / 20
                                var page by remember(item.id) {
                                    mutableIntStateOf(
                                        if (ui.resumeEpIndex >= 0) ui.resumeEpIndex / 20 else 0,
                                    )
                                }
                                val pageRowState = androidx.compose.foundation.lazy.rememberLazyListState()
                                val pageScroll = remember(pageRowState) { lazyNavContainer(pageRowState) }
                                ProvideNavScrolls(horizontal = pageScroll) {
                                    LazyRow(
                                        state = pageRowState,
                                        horizontalArrangement = Arrangement.spacedBy(12f.sx(s)),
                                        modifier = Modifier.padding(bottom = 14f.sx(s)),
                                    ) {
                                        items(pageCount) { p ->
                                            val from = p * 20 + 1
                                            val to = minOf((p + 1) * 20, item.episodes.size)
                                            EpisodeCellFixed(label = "$from-$to", highlight = p == page, width = 120f.sx(s), s = s, cardReg = cardReg, cardKey = "page-$p") { page = p }
                                        }
                                    }
                                }
                                val pageEps = item.episodes.drop(page * 20).take(20)
                                EpisodeGrid(pageEps, ui.resumeEpIndex, s, cardReg) { ep ->
                                    nav.navigate(
                                        "player/" + URLEncoder.encode(vodId, "UTF-8") +
                                            "/${ep.index}",
                                    )
                                }
                            } else {
                                EpisodeGrid(item.episodes, ui.resumeEpIndex, s, cardReg) { ep ->
                                    nav.navigate(
                                        "player/" + URLEncoder.encode(vodId, "UTF-8") +
                                            "/${ep.index}",
                                    )
                                }
                            }
                        }
                    }
                }

            }
        }
    }
}

/**
 * 好好看会把电影的「HD中字 / HD国语 / 正片」作为多个 episode 返回。
 * 这些是播放版本而不是选集，详情页不应让用户手动选源。
 */
private fun shouldShowEpisodePicker(item: com.qiubo.optimaltv.data.model.VodItem): Boolean {
    if (item.episodes.size <= 1) return false
    return when (item.categoryId.substringAfterLast(':')) {
        "1" -> false
        "2", "3", "4", "6" -> true
        else -> {
            val seriesMeta = Regex("电视剧|连续剧|网剧|短剧|动漫|综艺|美剧|英剧|韩剧|日剧|泰剧")
            val episodicName = Regex("第?\\d+[\\s]*[集期话章]|更新至|[上中下]集")
            seriesMeta.containsMatchIn(item.meta) || item.episodes.size > 4 ||
                item.episodes.any { episodicName.containsMatchIn(it.name) }
        }
    }
}

/** 选集网格：每行 6 格等宽（行 gap14 格 gap20）；v1.13 引擎自缩放层容差防隔行/跨区斜跳 */
@Composable
private fun EpisodeGrid(
    eps: List<com.qiubo.optimaltv.data.model.Episode>,
    resumeEpIndex: Int,
    s: Float,
    cardReg: FocusRegistry? = null,
    onPick: (com.qiubo.optimaltv.data.model.Episode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14f.sx(s))) {
        eps.chunked(6).forEach { rowEps ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(20f.sx(s)),
            ) {
                rowEps.forEach { ep ->
                    EpisodeCell(
                        label = ep.name,
                        highlight = ep.index == resumeEpIndex,
                        width = null,
                        s = s,
                        cardReg = cardReg,
                        cardKey = "ep-${ep.index}",
                    ) { onPick(ep) }
                }
                // 补齐空位保持格子等宽
                repeat(6 - rowEps.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 分页签（固定宽度；非 RowScope 版本，供 LazyRow 页签行使用，需求 影视#4） */
@Composable
private fun EpisodeCellFixed(
    label: String,
    highlight: Boolean,
    width: androidx.compose.ui.unit.Dp,
    s: Float,
    cardReg: FocusRegistry? = null,
    cardKey: String? = null,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(width)
            .dpadFocusable(
                scaleFocused = 1.05f,
                onFocusedChange = { if (it && cardKey != null) ReturnFocus.mark("detail", cardKey) },
                externalFocusRequester = cardKey?.let { cardReg?.fr(it) },
                onClick = onClick,
            )
            .clip(RoundedCornerShape(12f.sx(s)))
            .background(
                if (highlight) AccentBlue.copy(alpha = 0.55f) else Color(0x24FFFFFF),
                RoundedCornerShape(12f.sx(s)),
            )
            .padding(vertical = 17f.sx(s)),
    ) {
        Text(label, color = OtvColors.White, fontSize = 25f.sxs(s), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.EpisodeCell(
    label: String,
    highlight: Boolean,
    width: androidx.compose.ui.unit.Dp?,
    s: Float,
    cardReg: FocusRegistry? = null,
    cardKey: String? = null,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(if (width != null) Modifier.width(width) else Modifier.weight(1f))
            .dpadFocusable(
                scaleFocused = 1.05f,
                onFocusedChange = { if (it && cardKey != null) ReturnFocus.mark("detail", cardKey) },
                externalFocusRequester = cardKey?.let { cardReg?.fr(it) },
                onClick = onClick,
            )
            .clip(RoundedCornerShape(12f.sx(s)))
            .background(
                if (highlight) AccentBlue.copy(alpha = 0.55f) else Color(0x24FFFFFF),
                RoundedCornerShape(12f.sx(s)),
            )
            .padding(vertical = 17f.sx(s)),
    ) {
        Text(label, color = OtvColors.White, fontSize = 25f.sxs(s), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ActionButton(
    text: String,
    primary: Boolean,
    focusRequester: FocusRequester? = null,
    cardReg: FocusRegistry? = null,
    cardKey: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // Go To Show 规格（原版详情按钮）：min-width 280 / height 64 / r9.7 / 29.3px Bold
    // primary 白50%底黑字；ghost 白12%底白字；聚焦纯白黑字
    val s = rememberUiScale()
    var btnFocused by remember { androidx.compose.runtime.mutableStateOf(false) }
    val focusable = enabled || focusRequester != null
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (focusable) Modifier.dpadFocusable(
                    scaleFocused = 1.02f, focusedBg = OtvColors.White,
                    onFocusedChange = {
                        btnFocused = it
                        if (it && cardKey != null) ReturnFocus.mark("detail", cardKey)
                    },
                    externalFocusRequester = cardKey?.let { cardReg?.fr(it) },
                    onClick = { if (enabled) onClick() },
                ) else Modifier)
            .background(
                if (primary || btnFocused) OtvColors.White.copy(alpha = if (btnFocused) 1f else 0.5f) else Color(0x2EFFFFFF),
                RoundedCornerShape(9.7f.sx(s)),
            )
            .widthIn(min = 280f.sx(s))
            .height(64f.sx(s))
            .padding(horizontal = 46f.sx(s)),
    ) {
        Text(
            text,
            fontSize = 29f.sxs(s), fontWeight = FontWeight.Bold,
            color = when {
                !enabled && !btnFocused -> OtvColors.White50
                primary || btnFocused -> OtvColors.Bg
                else -> OtvColors.White
            },
        )
    }
}

/** 简易 VM 工厂 */
fun <T : ViewModel> simpleFactory(creator: () -> T): androidx.lifecycle.ViewModelProvider.Factory =
    object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = creator() as VM
    }
