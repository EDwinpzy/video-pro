package com.qiubo.optimaltv.ui.player

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qiubo.optimaltv.TvPerformanceProfile
import com.qiubo.optimaltv.data.model.LineInfo
import com.qiubo.optimaltv.data.model.formatTime
import com.qiubo.optimaltv.data.prefs.DanmakuDisplayArea
import com.qiubo.optimaltv.data.prefs.SkipSegmentSettings
import com.qiubo.optimaltv.ui.components.NAV_DOWN
import com.qiubo.optimaltv.ui.components.NAV_LEFT
import com.qiubo.optimaltv.ui.components.NAV_RIGHT
import com.qiubo.optimaltv.ui.components.NAV_UP
import com.qiubo.optimaltv.ui.components.RemotePoster
import com.qiubo.optimaltv.ui.components.SystemEditText
import com.qiubo.optimaltv.ui.components.dpadFocusable
import com.qiubo.optimaltv.ui.components.episodeEntryCustomName
import kotlinx.coroutines.launch
import kotlin.math.abs

private val SidebarAccent = Color(0xFFD6A35D)
private val SidebarAccentSoft = Color(0xFFE1B777)
private val SidebarIvory = Color(0xFFF4EFE4)
private val SidebarIvoryDim = Color(0xFFD6D0C5)
private val SidebarMuted = Color(0xFF999C94)
private val SidebarMutedWarm = Color(0xFFB7B0A3)
private val SidebarPanelTop = Color(0xFF171B19)
private val SidebarPanelBottom = Color(0xFF0C100F)
private val SidebarChip = Color(0xFF1B201D)
private val SidebarChipActive = Color(0xFF2C251B)
private val SidebarPanelShape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
private val SidebarSmallShape = RoundedCornerShape(9.dp)
private val SidebarChipShape = RoundedCornerShape(8.dp)
private val SidebarSourceShape = RoundedCornerShape(10.dp)

private const val SidebarEpisodeColumns = 6
private const val SidebarEpisodePageColumns = 3
private const val SidebarSourceColumns = 2
private const val SidebarNavGroup = "player-classic-sidebar"

/**
 * Low-tier focus treatment: the state is read in the draw phase, so moving the
 * D-pad repaints one chip instead of recomposing and re-measuring the panel.
 */
private fun Modifier.sidebarLowMotionSurface(
    focused: State<Boolean>,
    idleColor: Color,
    activeColor: Color,
    activeBorderColor: Color,
    cornerRadius: Dp,
): Modifier =
    drawWithContent {
        val isFocused = focused.value
        val radius = cornerRadius.toPx()
        drawRoundRect(
            color =
                when {
                    isFocused -> Color(0xFF30342E)
                    activeColor != Color.Transparent -> activeColor
                    else -> idleColor
                },
            cornerRadius = CornerRadius(radius),
        )
        drawContent()
        val strokeWidth = if (isFocused) 2.dp.toPx() else 1.dp.toPx()
        val borderColor =
            when {
                isFocused -> SidebarIvory
                activeBorderColor != Color.Transparent -> activeBorderColor
                else -> Color.White.copy(alpha = 0.06f)
            }
        drawRoundRect(
            color = borderColor,
            topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
            size =
                Size(
                    width = (size.width - strokeWidth).coerceAtLeast(0f),
                    height = (size.height - strokeWidth).coerceAtLeast(0f),
                ),
            cornerRadius = CornerRadius((radius - strokeWidth / 2f).coerceAtLeast(0f)),
            style = Stroke(width = strokeWidth),
        )
    }

@Composable
private fun sidebarColor(
    target: Color,
    durationMs: Int,
    label: String,
): Color =
    if (TvPerformanceProfile.navigationMotionEnabled) {
        animateColorAsState(target, tween(durationMs), label = label).value
    } else {
        target
    }

private enum class SidebarTab(val label: String) {
    Episodes("选集"),
    Settings("播放设置"),
    Sources("片源"),
}

private enum class SkipEditTarget {
    Intro,
    Outro,
}

private data class SidebarStart(val row: Int, val column: Int)

private data class SidebarSettingRow(
    val label: String,
    val options: List<String>,
    val selectedIndex: Int,
    val fixedChipWidth: Dp? = null,
    val onSelect: (Int) -> Unit,
)

private fun skipValueLabel(seconds: Int): String =
    if (seconds <= 0) "关闭" else "${seconds}秒"

/**
 * Accepted playback sidebar: fixed film header, three tabs, compact settings and
 * a two-column source list. It intentionally omits duplicate status/help copy.
 */
@Composable
internal fun ClassicMorePanel(
    ui: PlayerUiState,
    vm: PlayerViewModel,
    onPickEpisode: (Int) -> Unit,
) {
    val tabs =
        remember(ui.isMovie, ui.epCount) {
            buildList {
                if (!ui.isMovie && ui.epCount > 1) add(SidebarTab.Episodes)
                add(SidebarTab.Settings)
                add(SidebarTab.Sources)
            }
        }
    var selectedTab by remember {
        mutableStateOf(if (ui.fatalMsg != null) SidebarTab.Sources else tabs.first())
    }
    LaunchedEffect(tabs) {
        if (selectedTab !in tabs) selectedTab = tabs.first()
    }

    val episodePageLabels = remember(ui.epCount) { SidebarEpisodePagePolicy.labels(ui.epCount) }
    val episodePageRows =
        remember(episodePageLabels) {
            episodePageLabels.indices.toList().chunked(SidebarEpisodePageColumns)
        }
    var episodePage by remember(ui.epCount) {
        mutableIntStateOf(SidebarEpisodePagePolicy.pageForEpisode(ui.epIndex, ui.epCount))
    }
    val pageEpisodes =
        remember(ui.epCount, episodePage) {
            SidebarEpisodePagePolicy.visibleRange(ui.epCount, episodePage).toList()
        }
    val episodeRows = remember(pageEpisodes) { pageEpisodes.chunked(SidebarEpisodeColumns) }
    val episodeRowOffset = episodePageRows.size
    val episodeKeys = remember(ui.epKeys, ui.epNames, ui.epCount) {
        ui.epKeys.takeIf { it.size == ui.epCount }
            ?: List(ui.epCount.coerceAtLeast(0)) { index -> "${ui.epNames.getOrNull(index).orEmpty()}\u0000$index" }
    }
    val episodeAnchor = remember { EpisodeFocusAnchor() }
    // Capture before keyed children detach: native fallback focus events must not
    // replace the business identity that was focused before this data change.
    val episodeRestoreRequest = remember(episodeKeys) {
        episodeAnchor.captureRefresh(episodeKeys, ui.epIndex)
    }
    val sourceRows = remember(ui.lines) { ui.lines.indices.toList().chunked(SidebarSourceColumns) }
    val speedIndex =
        PlayerViewModel.SPEED_STEPS.indexOfFirst { abs(it - ui.speed) < 0.01f }
            .takeIf { it >= 0 }
            ?: PlayerViewModel.SPEED_STEPS.indices.minByOrNull {
                abs(PlayerViewModel.SPEED_STEPS[it] - ui.speed)
            } ?: 0
    val displayAreas = DanmakuDisplayArea.menuEntries
    var editingSkip by remember { mutableStateOf<SkipEditTarget?>(null) }
    var skipDraft by remember { mutableStateOf("") }
    var pendingStart by remember { mutableStateOf<SidebarStart?>(null) }
    var contentRequest by remember { mutableIntStateOf(0) }
    val settingRows =
        listOf(
            SidebarSettingRow(
                label = "智能跳过",
                options =
                    listOf(
                        "关",
                        if (ui.officialSkipPreset != null) "已识别" else "自动",
                    ),
                selectedIndex = if (ui.smartSkipEnabled) 1 else 0,
                onSelect = { column -> vm.setSmartSkipEnabled(column == 1) },
            ),
            SidebarSettingRow(
                label = if (ui.smartSkipEnabled) "片头兜底" else "片头跳过",
                options = listOf(skipValueLabel(ui.skipIntroSeconds)),
                selectedIndex = 0,
                onSelect = {
                    editingSkip = SkipEditTarget.Intro
                    skipDraft = ui.skipIntroSeconds.toString()
                },
            ),
            SidebarSettingRow(
                label = if (ui.smartSkipEnabled) "片尾兜底" else "片尾跳过",
                options = listOf(skipValueLabel(ui.skipOutroSeconds)),
                selectedIndex = 0,
                onSelect = {
                    editingSkip = SkipEditTarget.Outro
                    skipDraft = ui.skipOutroSeconds.toString()
                },
            ),
            SidebarSettingRow(
                label = "播放倍速",
                options = PlayerViewModel.SPEED_STEPS.map { "${it}×" },
                selectedIndex = speedIndex,
                onSelect = { vm.setSpeed(PlayerViewModel.SPEED_STEPS[it]) },
            ),
            SidebarSettingRow(
                label = "弹幕",
                options = listOf("开启", "关闭"),
                selectedIndex = if (ui.danmakuEnabled) 0 else 1,
                fixedChipWidth = 72.dp,
                onSelect = { index ->
                    if ((index == 0) != ui.danmakuEnabled) vm.toggleDanmaku()
                },
            ),
            SidebarSettingRow(
                label = "显示区域",
                options = displayAreas.map { it.label },
                selectedIndex = displayAreas.indexOf(ui.danmakuDisplayArea).coerceAtLeast(0),
                onSelect = { vm.setDanmakuDisplayArea(displayAreas[it]) },
            ),
            SidebarSettingRow(
                label = "字号",
                options = listOf("小", "标准", "大"),
                selectedIndex =
                    PlayerViewModel.DANMAKU_FONT_SCALE_STEPS.indexOfFirst {
                        abs(it - ui.danmakuFontScale) < 0.01f
                    }.coerceAtLeast(0),
                onSelect = { vm.setDanmakuFontScale(PlayerViewModel.DANMAKU_FONT_SCALE_STEPS[it]) },
            ),
            SidebarSettingRow(
                label = "不透明度",
                options = listOf("40%", "60%", "80%", "100%"),
                selectedIndex =
                    PlayerViewModel.DANMAKU_OPACITY_STEPS.indexOfFirst {
                        abs(it - ui.danmakuOpacity) < 0.01f
                    }.coerceAtLeast(0),
                onSelect = { vm.setDanmakuOpacity(PlayerViewModel.DANMAKU_OPACITY_STEPS[it]) },
            ),
            SidebarSettingRow(
                label = "滚动速度",
                options = listOf("0.5x", "0.75x", "1.0x", "1.5x", "2.0x"),
                selectedIndex =
                    PlayerViewModel.DANMAKU_SCROLL_SPEED_STEPS.indexOfFirst {
                        abs(it - ui.danmakuScrollSpeed) < 0.01f
                    }.coerceAtLeast(0),
                onSelect = { vm.setDanmakuScrollSpeed(PlayerViewModel.DANMAKU_SCROLL_SPEED_STEPS[it]) },
            ),
        )

    val contentRows =
        when {
            editingSkip != null && selectedTab == SidebarTab.Settings -> emptyList()
            selectedTab == SidebarTab.Episodes ->
                episodePageRows.map { it.size } + episodeRows.map { it.size }
            selectedTab == SidebarTab.Settings -> settingRows.map { it.options.size }
            else -> sourceRows.map { it.size }
        }
    val contentFocus = rememberPlayerPanelFocus(contentRows)
    val tabRequesters = remember { SidebarTab.entries.associateWith { FocusRequester() } }
    val scope = rememberCoroutineScope()

    fun initialStart(tab: SidebarTab): SidebarStart =
        when (tab) {
            SidebarTab.Episodes -> {
                val pageStart =
                    SidebarEpisodePagePolicy.normalizePage(episodePage, ui.epCount) *
                        SidebarEpisodePagePolicy.PAGE_SIZE
                val localIndex = ui.epIndex - pageStart
                if (localIndex in pageEpisodes.indices) {
                    SidebarStart(
                        row = episodeRowOffset + localIndex / SidebarEpisodeColumns,
                        column = localIndex % SidebarEpisodeColumns,
                    )
                } else {
                    SidebarStart(row = episodeRowOffset, column = 0)
                }
            }
            SidebarTab.Settings ->
                SidebarStart(row = 0, column = if (ui.smartSkipEnabled) 1 else 0)
            SidebarTab.Sources ->
                SidebarStart(
                    row = ui.activeLine / SidebarSourceColumns,
                    column = ui.activeLine % SidebarSourceColumns,
                )
        }

    fun focusTab(tab: SidebarTab) {
        episodeAnchor.clear()
        scope.launch {
            withFrameNanos { }
            runCatching { tabRequesters.getValue(tab).requestFocus() }
        }
    }

    fun requestContentFocus(tab: SidebarTab = selectedTab) {
        if (contentRows.isEmpty()) {
            focusTab(tab)
            return
        }
        pendingStart = initialStart(tab)
        contentRequest++
    }

    fun moveTab(tab: SidebarTab) {
        editingSkip = null
        skipDraft = ""
        selectedTab = tab
        if (tab == SidebarTab.Episodes) {
            episodePage = SidebarEpisodePagePolicy.pageForEpisode(ui.epIndex, ui.epCount)
        }
        focusTab(tab)
    }

    fun selectEpisodePage(page: Int) {
        val selected = SidebarEpisodePagePolicy.normalizePage(page, ui.epCount)
        episodeAnchor.movedByUser()
        episodePage = selected
        pendingStart = SidebarStart(row = episodeRowOffset, column = 0)
        contentRequest++
    }

    fun navigateContent(row: Int, column: Int, direction: Int): Boolean {
        if (selectedTab == SidebarTab.Episodes) episodeAnchor.movedByUser()
        if (direction == NAV_UP && row == 0) {
            focusTab(selectedTab)
            return true
        }
        contentFocus.navigate(row, column, direction)
        return true
    }

    fun closeSkipEditor(save: Boolean) {
        val target = editingSkip ?: return
        if (save) {
            val seconds = SkipSegmentSettings.secondsFromDraft(skipDraft)
            when (target) {
                SkipEditTarget.Intro -> vm.setSkipIntroSeconds(seconds)
                SkipEditTarget.Outro -> vm.setSkipOutroSeconds(seconds)
            }
        }
        editingSkip = null
        skipDraft = ""
        pendingStart = SidebarStart(row = if (target == SkipEditTarget.Intro) 0 else 1, column = 0)
        contentRequest++
    }

    LaunchedEffect(Unit) {
        requestContentFocus(selectedTab)
    }
    LaunchedEffect(episodeKeys, episodePage, selectedTab, contentFocus) {
        if (selectedTab != SidebarTab.Episodes) return@LaunchedEffect
        val request = episodeRestoreRequest ?: return@LaunchedEffect
        if (!episodeAnchor.isCurrent(request)) return@LaunchedEffect
        val targetPage = SidebarEpisodePagePolicy.pageForEpisode(request.index, ui.epCount)
        if (targetPage != episodePage) {
            episodePage = targetPage
            return@LaunchedEffect
        }
        val pageStart = episodePage * SidebarEpisodePagePolicy.PAGE_SIZE
        val localIndex = request.index - pageStart
        if (localIndex !in pageEpisodes.indices) return@LaunchedEffect
        contentFocus.focusInitial(
            episodeRowOffset + localIndex / SidebarEpisodeColumns,
            localIndex % SidebarEpisodeColumns,
        )
    }
    LaunchedEffect(contentRequest, contentRows) {
        val start = pendingStart ?: return@LaunchedEffect
        if (contentRows.isEmpty()) return@LaunchedEffect
        val row = start.row.coerceIn(contentRows.indices)
        val column = start.column.coerceIn(0 until contentRows[row])
        withFrameNanos { }
        contentFocus.focusInitial(row, column)
        pendingStart = null
    }

    Box(
        Modifier
            .fillMaxSize()
            .consumePlayerPanelKeysWhileMoving(contentFocus)
            .then(
                if (!TvPerformanceProfile.suppressFocusSemantics) {
                    Modifier
                } else {
                    // The player panel is a closed navigation surface driven by the
                    // remote. On constrained projectors the vendor accessibility
                    // service rescans this large subtree on every focus move; keep
                    // the visual focus model but avoid exporting dozens of nodes.
                    Modifier.clearAndSetSemantics { }
                },
            ),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0x40000000)))
        val panelShadow =
            if (TvPerformanceProfile.navigationMotionEnabled) {
                Modifier.shadow(
                    28.dp,
                    SidebarPanelShape,
                    ambientColor = Color.Black,
                    spotColor = Color.Black,
                )
            } else {
                Modifier
            }
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(560.dp)
                .fillMaxHeight()
                .then(panelShadow)
                .clip(SidebarPanelShape)
                .background(
                    Brush.verticalGradient(
                        0f to SidebarPanelTop,
                        0.54f to Color(0xFF111513),
                        1f to SidebarPanelBottom,
                    ),
                ),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to SidebarAccent.copy(alpha = 0.42f),
                            0.28f to Color.White.copy(alpha = 0.08f),
                            0.72f to Color.White.copy(alpha = 0.03f),
                            1f to SidebarAccent.copy(alpha = 0.18f),
                        ),
                    ),
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 20.dp),
            ) {
                SidebarPlaybackHeader(ui)
                Spacer(Modifier.height(18.dp))
                SidebarTabBar(
                    tabs = tabs,
                    selectedTab = selectedTab,
                    episodeCount = ui.epCount.takeIf { !ui.isMovie && it > 1 },
                    requesters = tabRequesters,
                    onSelect = {
                        editingSkip = null
                        skipDraft = ""
                        selectedTab = it
                        if (it == SidebarTab.Episodes) {
                            episodePage = SidebarEpisodePagePolicy.pageForEpisode(ui.epIndex, ui.epCount)
                        }
                    },
                    onMove = ::moveTab,
                    onEnterContent = { requestContentFocus(selectedTab) },
                )
                Spacer(Modifier.height(14.dp))
                Box(Modifier.weight(1f)) {
                    when (selectedTab) {
                        SidebarTab.Episodes ->
                            SidebarEpisodesContent(
                                episodes = pageEpisodes,
                                episodeNames = ui.epNames,
                                episodeKeys = episodeKeys,
                                pageRows = episodePageRows,
                                pageLabels = episodePageLabels,
                                selectedPage = episodePage,
                                episodeRowOffset = episodeRowOffset,
                                currentEpisode = ui.epIndex.takeIf { ui.fatalMsg == null } ?: -1,
                                onEntryFocused = episodeAnchor::focused,
                                focus = contentFocus,
                                onPick = onPickEpisode,
                                onSelectPage = ::selectEpisodePage,
                                onNavigate = ::navigateContent,
                            )
                        SidebarTab.Settings ->
                            if (editingSkip != null) {
                                SidebarSkipEditor(
                                    target = editingSkip!!,
                                    draft = skipDraft,
                                    onDraftChange = { skipDraft = it },
                                    onCancel = { closeSkipEditor(save = false) },
                                    onConfirm = { closeSkipEditor(save = true) },
                                )
                            } else {
                                SidebarSettingsContent(
                                    rows = settingRows,
                                    focus = contentFocus,
                                    onNavigate = ::navigateContent,
                                )
                            }
                        SidebarTab.Sources ->
                            SidebarSourcesContent(
                                ui = ui,
                                rows = sourceRows,
                                focus = contentFocus,
                                onSelect = { vm.switchLine(it) },
                                onNavigate = ::navigateContent,
                            )
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarPlaybackHeader(ui: PlayerUiState) {
    val density = LocalDensity.current
    val posterShadow =
        if (TvPerformanceProfile.navigationMotionEnabled) {
            Modifier.shadow(10.dp, SidebarSmallShape, clip = false)
        } else {
            Modifier
        }
    val posterRequestWidthPx =
        remember(density) {
            with(density) { 84.dp.roundToPx() * 2 }
        }
    Row(Modifier.fillMaxWidth().height(126.dp)) {
        Box(
            Modifier
                .width(84.dp)
                .fillMaxHeight()
                .then(posterShadow)
                .clip(SidebarSmallShape)
                .border(1.dp, Color.White.copy(alpha = 0.12f), SidebarSmallShape),
        ) {
            RemotePoster(
                url = ui.posterUrl,
                title = ui.title,
                seed = ui.title.hashCode(),
                modifier = Modifier.fillMaxSize(),
                requestWidthPx = posterRequestWidthPx,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.68f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.42f),
                        ),
                    ),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = ui.title.ifBlank { "影片" },
                    color = SidebarIvory,
                    fontSize = 26.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = sidebarProgressLabel(ui),
                    color = SidebarMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            0f to SidebarIvory.copy(alpha = 0.24f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!ui.isMovie && ui.epCount > 1) {
                    Text(
                        text = sidebarEpisodeLabel(ui),
                        color = SidebarAccentSoft,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.4.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(7.dp))
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(10.dp)
                            .background(SidebarAccent.copy(alpha = 0.42f)),
                    )
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    text = sidebarMeta(ui),
                    color = SidebarMutedWarm,
                    fontSize = 9.8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(5.dp))
            if (ui.desc.isNotBlank()) {
                Text(
                    text = ui.desc,
                    color = SidebarMutedWarm.copy(alpha = 0.92f),
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SidebarTabBar(
    tabs: List<SidebarTab>,
    selectedTab: SidebarTab,
    episodeCount: Int?,
    requesters: Map<SidebarTab, FocusRequester>,
    onSelect: (SidebarTab) -> Unit,
    onMove: (SidebarTab) -> Unit,
    onEnterContent: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(Color(0xFF191E1B), RoundedCornerShape(11.dp))
            .border(1.dp, Color.White.copy(alpha = 0.055f), RoundedCornerShape(11.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        tabs.forEachIndexed { index, tab ->
            key(tab) {
                val focused = remember { mutableStateOf(false) }
                val motionEnabled = TvPerformanceProfile.navigationMotionEnabled
                val active = tab == selectedTab
                val background =
                    if (motionEnabled) {
                        sidebarColor(
                            target =
                                when {
                                    focused.value -> Color(0xFF35342C)
                                    active -> Color(0xFF282A24)
                                    else -> Color.Transparent
                                },
                            durationMs = 110,
                            label = "sidebar-tab-background",
                        )
                    } else {
                        Color.Transparent
                    }
                val foreground =
                    if (motionEnabled) {
                        sidebarColor(
                            target = if (active || focused.value) SidebarIvory else SidebarMuted,
                            durationMs = 110,
                            label = "sidebar-tab-foreground",
                        )
                    } else {
                        if (active) SidebarIvory else SidebarMutedWarm
                    }
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag("player-sidebar-tab-${tab.name.lowercase()}")
                        .onFocusChanged { focused.value = it.isFocused }
                        .dpadFocusable(
                            scaleFocused = 1.01f,
                            drawRing = false,
                            navGroup = SidebarNavGroup,
                            externalFocusRequester = requesters.getValue(tab),
                            navOverride = { direction ->
                                when (direction) {
                                    NAV_LEFT -> {
                                        onMove(tabs[(index - 1).coerceAtLeast(0)])
                                        true
                                    }
                                    NAV_RIGHT -> {
                                        onMove(tabs[(index + 1).coerceAtMost(tabs.lastIndex)])
                                        true
                                    }
                                    NAV_DOWN -> {
                                        onEnterContent()
                                        true
                                    }
                                    NAV_UP -> true
                                    else -> false
                                }
                            },
                            onClick = { onSelect(tab) },
                        )
                        .semantics {
                            role = Role.Tab
                            contentDescription = tab.label
                        }
                        .then(
                            if (motionEnabled) {
                                Modifier
                                    .background(background, SidebarChipShape)
                                    .border(
                                        1.dp,
                                        if (focused.value) {
                                            SidebarAccent.copy(alpha = 0.68f)
                                        } else {
                                            Color.Transparent
                                        },
                                        SidebarChipShape,
                                    )
                            } else {
                                Modifier.sidebarLowMotionSurface(
                                    focused = focused,
                                    idleColor = Color.Transparent,
                                    activeColor = if (active) Color(0xFF282A24) else Color.Transparent,
                                    activeBorderColor = Color.Transparent,
                                    cornerRadius = 8.dp,
                                )
                            },
                        )
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (active) {
                        Box(Modifier.size(4.dp).background(SidebarAccent, CircleShape))
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(
                        text = tab.label,
                        color = foreground,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    )
                    if (tab == SidebarTab.Episodes && episodeCount != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = episodeCount.toString(),
                            color =
                                if (active) SidebarAccentSoft.copy(alpha = 0.82f)
                                else SidebarMuted.copy(alpha = 0.62f),
                            fontSize = 8.5.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun SidebarEpisodesContent(
    episodes: List<Int>,
    episodeNames: List<String>,
    episodeKeys: List<String>,
    pageRows: List<List<Int>>,
    pageLabels: List<String>,
    selectedPage: Int,
    episodeRowOffset: Int,
    currentEpisode: Int,
    onEntryFocused: (String) -> Unit,
    focus: PlayerPanelFocus,
    onPick: (Int) -> Unit,
    onSelectPage: (Int) -> Unit,
    onNavigate: (Int, Int, Int) -> Boolean,
) {
    val scroll = rememberScrollState()
    val hasNamedEntries = remember(episodeNames) {
        episodeNames.withIndex().any { episodeEntryCustomName(it.index, it.value) != null }
    }
    Column(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        SidebarSectionHeading(if (hasNamedEntries) "选集" else "全部剧集")
        Spacer(Modifier.height(10.dp))
        if (pageRows.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                pageRows.forEachIndexed { row, pages ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        pages.forEachIndexed { column, page ->
                            key("episode-page-$page") {
                                SidebarEpisodePageChip(
                                    page = page,
                                    label = pageLabels[page],
                                    selected = page == selectedPage,
                                    focus = focus,
                                    row = row,
                                    column = column,
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .height(30.dp)
                                            .then(focus.modifier(row, column)),
                                    onNavigate = onNavigate,
                                    onClick = { onSelectPage(page) },
                                )
                            }
                        }
                        repeat(SidebarEpisodePageColumns - pages.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            episodes.chunked(SidebarEpisodeColumns).forEachIndexed { row, rowEpisodes ->
                val contentRow = episodeRowOffset + row
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowEpisodes.forEachIndexed { column, episode ->
                        val identity = episodeKeys[episode]
                        key(identity) {
                            SidebarEpisodeChip(
                                index = episode,
                                sourceName = episodeEntryCustomName(episode, episodeNames.getOrNull(episode)),
                                active = episode == currentEpisode,
                                onEntryFocused = { onEntryFocused(identity) },
                                focus = focus,
                                row = contentRow,
                                column = column,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .height(42.dp)
                                        .then(focus.modifier(contentRow, column)),
                                onNavigate = onNavigate,
                                onClick = { onPick(episode) },
                            )
                        }
                    }
                    repeat(SidebarEpisodeColumns - rowEpisodes.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarEpisodePageChip(
    page: Int,
    label: String,
    selected: Boolean,
    focus: PlayerPanelFocus,
    row: Int,
    column: Int,
    modifier: Modifier,
    onNavigate: (Int, Int, Int) -> Boolean,
    onClick: () -> Unit,
) {
    val focused = remember { mutableStateOf(false) }
    val motionEnabled = TvPerformanceProfile.navigationMotionEnabled
    val background =
        if (motionEnabled) {
            sidebarColor(
                target =
                    when {
                        focused.value -> SidebarIvory
                        selected -> SidebarChipActive
                        else -> SidebarChip
                    },
                durationMs = 90,
                label = "sidebar-episode-page-background",
            )
        } else {
            if (selected) SidebarChipActive else SidebarChip
        }
    val foreground =
        if (motionEnabled) {
            sidebarColor(
                target =
                    when {
                        focused.value -> Color(0xFF141714)
                        selected -> Color(0xFFF0C98D)
                        else -> SidebarIvoryDim.copy(alpha = 0.84f)
                    },
                durationMs = 90,
                label = "sidebar-episode-page-foreground",
            )
        } else {
            if (selected) Color(0xFFF0C98D) else SidebarIvoryDim
        }
    Box(
        modifier
            .testTag("player-sidebar-episode-page-$page")
            .onFocusChanged { focused.value = it.isFocused }
            .dpadFocusable(
                scaleFocused = 1.01f,
                drawRing = false,
                navGroup = SidebarNavGroup,
                externalFocusRequester = focus.requester(row, column),
                navOverride = { direction ->
                    if (direction == NAV_DOWN) {
                        onClick()
                        true
                    } else {
                        onNavigate(row, column, direction)
                    }
                },
                onClick = onClick,
            )
            .semantics {
                role = Role.Button
                contentDescription = label + if (selected) "，当前分组" else ""
            }
            .then(
                if (motionEnabled) {
                    Modifier
                        .background(background, SidebarChipShape)
                        .border(
                            if (focused.value) 2.dp else 1.dp,
                            if (focused.value) Color.White
                            else if (selected) SidebarAccent.copy(alpha = 0.72f)
                            else Color.White.copy(alpha = 0.06f),
                            SidebarChipShape,
                        )
                } else {
                    Modifier.sidebarLowMotionSurface(
                        focused = focused,
                        idleColor = SidebarChip,
                        activeColor = if (selected) SidebarChipActive else Color.Transparent,
                        activeBorderColor =
                            if (selected) SidebarAccent.copy(alpha = 0.72f) else Color.Transparent,
                        cornerRadius = 8.dp,
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = foreground,
            fontSize = 10.sp,
            fontWeight = if (selected || focused.value) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SidebarEpisodeChip(
    index: Int,
    sourceName: String?,
    active: Boolean,
    onEntryFocused: () -> Unit,
    focus: PlayerPanelFocus,
    row: Int,
    column: Int,
    modifier: Modifier,
    onNavigate: (Int, Int, Int) -> Boolean,
    onClick: () -> Unit,
) {
    val focused = remember { mutableStateOf(false) }
    val motionEnabled = TvPerformanceProfile.navigationMotionEnabled
    val background =
        if (motionEnabled) {
            sidebarColor(
                target =
                    when {
                        focused.value -> SidebarIvory
                        active -> SidebarChipActive
                        else -> SidebarChip
                    },
                durationMs = 90,
                label = "sidebar-episode-background",
            )
        } else {
            if (active) SidebarChipActive else SidebarChip
        }
    val foreground =
        if (motionEnabled) {
            sidebarColor(
                target =
                    when {
                        focused.value -> Color(0xFF141714)
                        active -> Color(0xFFF0C98D)
                        else -> SidebarIvoryDim.copy(alpha = 0.84f)
                    },
                durationMs = 90,
                label = "sidebar-episode-foreground",
            )
        } else {
            if (active) Color(0xFFF0C98D) else SidebarIvoryDim
        }
    Box(
        modifier
            .testTag("player-sidebar-episode-$index")
            .onFocusChanged {
                focused.value = it.isFocused
                if (it.isFocused) onEntryFocused()
            }
            .dpadFocusable(
                scaleFocused = 1.025f,
                drawRing = false,
                navGroup = SidebarNavGroup,
                externalFocusRequester = focus.requester(row, column),
                navOverride = { direction -> onNavigate(row, column, direction) },
                onClick = { focus.activate(row, column, onClick) },
            )
            .semantics {
                role = Role.Button
                contentDescription = (sourceName ?: "第 ${index + 1} 集") + if (active) "，正在播放" else ""
            }
            .then(
                if (motionEnabled) {
                    Modifier
                        .background(background, SidebarChipShape)
                        .border(
                            if (focused.value) 2.dp else 1.dp,
                            if (focused.value) Color.White
                            else if (active) SidebarAccent.copy(alpha = 0.72f)
                            else Color.White.copy(alpha = 0.06f),
                            SidebarChipShape,
                        )
                } else {
                    Modifier.sidebarLowMotionSurface(
                        focused = focused,
                        idleColor = SidebarChip,
                        activeColor = if (active) SidebarChipActive else Color.Transparent,
                        activeBorderColor =
                            if (active) SidebarAccent.copy(alpha = 0.72f) else Color.Transparent,
                        cornerRadius = 8.dp,
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = sourceName ?: (index + 1).toString().padStart(2, '0'),
            color = foreground,
            fontSize = 13.sp,
            fontWeight =
                if (!motionEnabled) {
                    if (active) FontWeight.SemiBold else FontWeight.Medium
                } else {
                    if (focused.value || active) FontWeight.SemiBold else FontWeight.Medium
                },
            letterSpacing = 0.4.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (active) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 7.dp, end = 7.dp)
                    .size(4.dp)
                    .background(
                        if (motionEnabled && focused.value) Color(0xFF171A17) else SidebarAccent,
                        CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun SidebarSettingsContent(
    rows: List<SidebarSettingRow>,
    focus: PlayerPanelFocus,
    onNavigate: (Int, Int, Int) -> Boolean,
) {
    Column(Modifier.fillMaxSize()) {
        SidebarSectionHeading("播放设置")
        Spacer(Modifier.height(5.dp))
        rows.forEachIndexed { rowIndex, row ->
            Row(
                Modifier.fillMaxWidth().height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.label,
                    color = SidebarMutedWarm,
                    fontSize = 9.5.sp,
                    modifier = Modifier.width(70.dp),
                )
                Spacer(Modifier.width(8.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.options.forEachIndexed { column, option ->
                        SidebarSettingChip(
                            text = option,
                            active = row.selectedIndex == column,
                            focus = focus,
                            row = rowIndex,
                            column = column,
                            modifier =
                                Modifier
                                    .then(
                                        row.fixedChipWidth?.let { Modifier.width(it) }
                                            ?: Modifier.weight(1f),
                                    )
                                    .height(30.dp)
                                    .then(focus.modifier(rowIndex, column)),
                            onNavigate = onNavigate,
                            onClick = { row.onSelect(column) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun SidebarSkipEditor(
    target: SkipEditTarget,
    draft: String,
    onDraftChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SidebarSectionHeading(
            if (target == SkipEditTarget.Intro) "片头跳过（秒）" else "片尾跳过（秒）",
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "直接输入秒数，0 表示关闭。",
            color = SidebarMuted,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(SidebarChip, RoundedCornerShape(10.dp))
                .border(1.dp, SidebarAccent.copy(alpha = 0.48f), RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SystemEditText(
                value = draft,
                onValueChange = { onDraftChange(SkipSegmentSettings.normalizeDraft(it)) },
                hint = "输入 0-${SkipSegmentSettings.MAX_SECONDS}",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                numeric = true,
                imeAction = EditorInfo.IME_ACTION_DONE,
                onImeAction = onConfirm,
                onKeyDown = { event ->
                    if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                        onCancel()
                        true
                    } else {
                        false
                    }
                },
                textSizeSp = 24f,
                textColor = SidebarIvory,
                hintTextColor = SidebarMuted,
                selectAllOnFocus = true,
                autoFocus = true,
                autoFocusShowsKeyboard = true,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "秒",
                color = SidebarMutedWarm,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "输入后按遥控器「完成」保存，返回取消。",
            color = SidebarMuted,
            fontSize = 9.5.sp,
        )
    }
}

@Composable
private fun SidebarSettingChip(
    text: String,
    active: Boolean,
    focus: PlayerPanelFocus,
    row: Int,
    column: Int,
    modifier: Modifier,
    onNavigate: (Int, Int, Int) -> Boolean,
    onClick: () -> Unit,
) {
    val focused = remember { mutableStateOf(false) }
    val motionEnabled = TvPerformanceProfile.navigationMotionEnabled
    val background =
        if (motionEnabled) {
            sidebarColor(
                target =
                    when {
                        focused.value -> SidebarIvory
                        active -> SidebarChipActive
                        else -> SidebarChip
                    },
                durationMs = 90,
                label = "sidebar-setting-background",
            )
        } else {
            if (active) SidebarChipActive else SidebarChip
        }
    val foreground =
        if (motionEnabled) {
            sidebarColor(
                target =
                    when {
                        focused.value -> Color(0xFF141714)
                        active -> Color(0xFFF0C98D)
                        else -> SidebarIvoryDim.copy(alpha = 0.76f)
                    },
                durationMs = 90,
                label = "sidebar-setting-foreground",
            )
        } else {
            if (active) Color(0xFFF0C98D) else SidebarIvoryDim
        }
    Box(
        modifier
            .testTag("player-sidebar-setting-$row-$column")
            .onFocusChanged { focused.value = it.isFocused }
            .dpadFocusable(
                scaleFocused = 1.01f,
                drawRing = false,
                navGroup = SidebarNavGroup,
                externalFocusRequester = focus.requester(row, column),
                navOverride = { direction -> onNavigate(row, column, direction) },
                onClick = { focus.activate(row, column, onClick) },
            )
            .semantics {
                role = Role.Button
                contentDescription = text
            }
            .then(
                if (motionEnabled) {
                    Modifier
                        .background(background, SidebarChipShape)
                        .border(
                            if (focused.value) 1.5.dp else 1.dp,
                            if (focused.value) Color.White
                            else if (active) SidebarAccent.copy(alpha = 0.62f)
                            else Color.White.copy(alpha = 0.06f),
                            SidebarChipShape,
                        )
                } else {
                    Modifier.sidebarLowMotionSurface(
                        focused = focused,
                        idleColor = SidebarChip,
                        activeColor = if (active) SidebarChipActive else Color.Transparent,
                        activeBorderColor =
                            if (active) SidebarAccent.copy(alpha = 0.62f) else Color.Transparent,
                        cornerRadius = 8.dp,
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = foreground,
            fontSize = 10.5.sp,
            fontWeight =
                if (!motionEnabled) {
                    if (active) FontWeight.SemiBold else FontWeight.Medium
                } else {
                    if (active || focused.value) FontWeight.SemiBold else FontWeight.Medium
                },
            maxLines = 1,
        )
    }
}

@Composable
private fun SidebarSourcesContent(
    ui: PlayerUiState,
    rows: List<List<Int>>,
    focus: PlayerPanelFocus,
    onSelect: (Int) -> Unit,
    onNavigate: (Int, Int, Int) -> Boolean,
) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        SidebarSectionHeading("可用片源")
        Spacer(Modifier.height(7.dp))
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = if (ui.sourceResolving) "正在加载片源…" else "暂无可用片源",
                    color = SidebarMuted,
                    fontSize = 11.sp,
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                rows.forEachIndexed { rowIndex, rowItems ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowItems.forEachIndexed { column, index ->
                            val line = ui.lines[index]
                            SidebarSourceCard(
                                line = line,
                                active = index == ui.activeLine,
                                focus = focus,
                                row = rowIndex,
                                column = column,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                        .then(focus.modifier(rowIndex, column)),
                                onNavigate = onNavigate,
                                onClick = { onSelect(index) },
                            )
                        }
                        repeat(SidebarSourceColumns - rowItems.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarSourceCard(
    line: LineInfo,
    active: Boolean,
    focus: PlayerPanelFocus,
    row: Int,
    column: Int,
    modifier: Modifier,
    onNavigate: (Int, Int, Int) -> Boolean,
    onClick: () -> Unit,
) {
    val focused = remember { mutableStateOf(false) }
    val motionEnabled = TvPerformanceProfile.navigationMotionEnabled
    val background =
        if (motionEnabled) {
            sidebarColor(
                target =
                    when {
                        focused.value -> SidebarIvory
                        active -> SidebarChipActive
                        else -> SidebarChip
                    },
                durationMs = 90,
                label = "sidebar-source-background",
            )
        } else {
            if (active) SidebarChipActive else SidebarChip
        }
    val titleColor =
        if (motionEnabled) {
            sidebarColor(
                target = if (focused.value) Color(0xFF141714) else SidebarIvory,
                durationMs = 90,
                label = "sidebar-source-title",
            )
        } else {
            SidebarIvory
        }
    Row(
        modifier
            .testTag("player-sidebar-source-${line.label}-$row-$column")
            .onFocusChanged { focused.value = it.isFocused }
            .dpadFocusable(
                scaleFocused = 1.015f,
                drawRing = false,
                navGroup = SidebarNavGroup,
                externalFocusRequester = focus.requester(row, column),
                navOverride = { direction -> onNavigate(row, column, direction) },
                onClick = { focus.activate(row, column, onClick) },
            )
            .semantics {
                role = Role.Button
                contentDescription = line.label + if (active) "，当前使用" else ""
            }
            .then(
                if (motionEnabled) {
                    Modifier
                        .background(background, SidebarSourceShape)
                        .border(
                            if (focused.value) 2.dp else 1.dp,
                            if (focused.value) Color.White
                            else if (active) SidebarAccent.copy(alpha = 0.64f)
                            else Color.White.copy(alpha = 0.06f),
                            SidebarSourceShape,
                        )
                } else {
                    Modifier.sidebarLowMotionSurface(
                        focused = focused,
                        idleColor = SidebarChip,
                        activeColor = if (active) SidebarChipActive else Color.Transparent,
                        activeBorderColor =
                            if (active) SidebarAccent.copy(alpha = 0.64f) else Color.Transparent,
                        cornerRadius = 10.dp,
                    )
                },
            )
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .border(
                    1.dp,
                    if (active) SidebarAccent else SidebarMuted.copy(alpha = 0.52f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                Box(Modifier.size(3.dp).background(SidebarAccent, CircleShape))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = line.label.ifBlank { "线路" },
            color = titleColor,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SidebarSectionHeading(title: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(13.dp).height(2.dp).background(SidebarAccent, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(
            text = title,
            color = SidebarIvory,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        )
    }
}

private fun sidebarMeta(ui: PlayerUiState): String {
    return ui.meta.trim()
}

private fun sidebarEpisodeLabel(ui: PlayerUiState): String {
    val number = (ui.epIndex + 1).toString().padStart(2, '0')
    return episodeEntryCustomName(ui.epIndex, ui.epNames.getOrNull(ui.epIndex)) ?: "第 $number 集"
}

private fun sidebarProgressLabel(ui: PlayerUiState): String =
    if (ui.durationMs > 0) {
        "${formatTime(ui.positionMs)} / ${formatTime(ui.durationMs)}"
    } else {
        formatTime(ui.positionMs)
    }

