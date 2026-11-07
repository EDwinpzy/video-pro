package com.qiubo.optimaltv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.qiubo.optimaltv.Graph
import com.qiubo.optimaltv.TvImageRequestSpec
import com.qiubo.optimaltv.data.db.HistoryEntity
import com.qiubo.optimaltv.data.model.VodItem
import com.qiubo.optimaltv.ui.components.InitialFocusEffect
import com.qiubo.optimaltv.ui.components.HOME_TAB_KEY
import com.qiubo.optimaltv.ui.components.HomeTabState
import com.qiubo.optimaltv.ui.components.MainTabBar
import com.qiubo.optimaltv.ui.components.NAV_DOWN
import com.qiubo.optimaltv.ui.components.NAV_LEFT
import com.qiubo.optimaltv.ui.components.NAV_RIGHT
import com.qiubo.optimaltv.ui.components.NAV_UP
import com.qiubo.optimaltv.ui.components.OtvHint
import com.qiubo.optimaltv.ui.components.ReturnFocus
import com.qiubo.optimaltv.ui.components.dpadFocusable
import com.qiubo.optimaltv.ui.components.createTvLazyListState
import com.qiubo.optimaltv.ui.components.tvReducedSemantics
import com.qiubo.optimaltv.ui.components.navigateToTab
import com.qiubo.optimaltv.ui.theme.OtvColors
import com.qiubo.optimaltv.ui.theme.rememberUiScale
import com.qiubo.optimaltv.ui.theme.sxs
import com.qiubo.optimaltv.ui.theme.sx
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableStateMapOf
import java.net.URLEncoder

/** 首页与五个内容标签共用同一套热门影音 hero 组件，首屏海报带铺满整屏。 */
private const val VOD_HERO_CONTENT_TOP = 180f

/** 最近观看轮播最多展示数量。 */
internal const val HOME_RECENT_HERO_LIMIT = 5

/**
 * hero 各文字块的高度上限（设计值 + 4px 余量）：title 64 / meta 54 / desc 74。
 * 2026-09-19 修复（用户报障「奥德赛」立刻播放钮整颗消失）：截图实测设备上 title/meta
 * 的文字框比设计高约 90px（字体行高差异），三行累计把尾部按钮顶出海报带、meta 与简介
 * 也整体下移。逐块封顶后，异常设备的行高被夹回设计值——位置与正常设备逐像素一致。
 */
private const val VOD_HERO_TITLE_MAX = 68f
private const val VOD_HERO_META_MAX = 58f
private const val VOD_HERO_DESC_MAX = 78f

/** hero 文字列总上限（= 68 + 8 + 58 + 10 + 78 = 222），总闸，兜住任何单块漏网 */
private const val VOD_HERO_TEXT_MAX = 222f

/** hero 播放钮离屏幕底部的安全距离。 */
private const val VOD_HERO_BTN_BOTTOM = 18f

internal const val HOME_FIRST_SECTION_ITEM_INDEX = 1
internal const val HOME_SECTION_ITEM_COUNT = 2

internal fun homeSectionHeaderItemIndex(sectionIndex: Int): Int =
    HOME_FIRST_SECTION_ITEM_INDEX + sectionIndex * HOME_SECTION_ITEM_COUNT

internal suspend fun focusHomeSection(
    listState: LazyListState,
    targetIndex: Int,
    firstCardFocus: FocusRequester,
): Boolean {
    listState.animateScrollToItem(homeSectionHeaderItemIndex(targetIndex))
    withFrameNanos { }
    repeat(16) {
        if (runCatching { firstCardFocus.requestFocus() }.isSuccess) return true
        withFrameNanos { }
    }
    return false
}

/** 影视首页的 TV 分类与三页片库规则。 */
internal object HomeLibraryUiPolicy {
    val SECTION_PAGE_TITLES = listOf("最近热门", "最近更新", "豆瓣高分")
    const val ALL_PAGE_TITLE = "全部"
    const val ALL_PAGE_INDEX = 3
    val PAGE_TITLES = SECTION_PAGE_TITLES + ALL_PAGE_TITLE

    fun categoryGridTargetIndex(index: Int, direction: Int, count: Int, columns: Int = 1): Int? {
        if (count <= 0 || columns <= 0 || index !in 0 until count) return null
        val target = when (direction) {
            NAV_LEFT -> if (index % columns > 0) index - 1 else return null
            NAV_RIGHT -> if (index % columns < columns - 1) index + 1 else return null
            NAV_UP -> index - columns
            NAV_DOWN -> index + columns
            else -> return null
        }
        return target.takeIf { it in 0 until count }
    }

    fun defaultCategoryIndex(categories: List<Pair<String, String>>): Int =
        categories.indexOfFirst {
            it.first.removePrefix("douban:") == "movie" || it.second.trim() == "电影"
        }
            .takeIf { it >= 0 } ?: 0

    fun contentPageTarget(page: Int, direction: Int, pageCount: Int): Int? {
        val target = when (direction) {
            NAV_UP -> page - 1
            NAV_DOWN -> page + 1
            else -> return null
        }
        return target.takeIf { page in 0 until pageCount && it in 0 until pageCount }
    }

    fun shouldReturnToCategoryMenuOnBack(page: Int, categoryMenuFocused: Boolean): Boolean =
        page > 0 && !categoryMenuFocused

    fun sourceSectionName(page: Int, available: List<String>): String? {
        val aliases = when (page) {
            0 -> listOf("最近热门", "近期热门", "热门推荐")
            1 -> listOf("最近更新", "最新上映", "最新上线")
            2 -> listOf("豆瓣高分")
            else -> return null
        }
        return aliases.firstOrNull(available::contains)
    }

    fun orderedCategories(source: List<Pair<String, String>>): List<Pair<String, String>> {
        val result = source.map { (id, name) ->
            id to if (id.removePrefix("douban:") == "anime") "国漫" else name
        }.toMutableList()
        val animeIndex = result.indexOfFirst { it.first.removePrefix("douban:") == "anime" }
        if (animeIndex < 0) return result
        val anime = result.removeAt(animeIndex)
        val tvIndex = result.indexOfFirst { it.first.removePrefix("douban:") == "tv" }
        val insertAt = if (tvIndex >= 0) tvIndex + 1 else minOf(2, result.size)
        result.add(insertAt, anime)
        return result
    }

    fun targetIndex(itemCount: Int, row: Int, column: Int): Int? {
        if (itemCount <= 0) return null
        val targetRow = row.coerceIn(0, 1).coerceAtMost(itemCount - 1)
        val lastColumn = (itemCount - 1 - targetRow) / 2
        return column.coerceIn(0, lastColumn) * 2 + targetRow
    }

}

@Composable
fun VodScreen(
    nav: NavController,
) {
    if (HomeTabState.currentKey == HOME_TAB_KEY) {
        HomeLandingScreen(nav)
    } else {
        CategoryVodScreen(nav)
    }
}

@Composable
private fun CategoryVodScreen(
    nav: NavController,
) {
    val tabKey = HomeTabState.currentKey
    val categoryKey = if (tabKey == "series") "tv" else tabKey
    val recommendations by Graph.repo.homeRecommendationsFlow.collectAsStateWithLifecycle()
    val categorySections = recommendations[com.qiubo.optimaltv.data.repo.VodRepository.categoryCid(categoryKey)].orEmpty()
    val sectionTitles =
        remember(categoryKey) { com.qiubo.optimaltv.data.repo.HomeFeedLayout.titles(categoryKey) }
    val sectionRowStates =
        remember(categoryKey) {
            List(sectionTitles.size) { createTvLazyListState() }
        }
    val sectionFocusRequesters =
        remember(categoryKey) {
            List(sectionTitles.size) { FocusRequester() }
        }
    val heroesTop = remember(categorySections) { HomeHeroPolicy.forCategory(categorySections) }
    val categoryLabel =
        when (tabKey) {
            "series" -> "电视剧"
            "anime" -> "国漫"
            "short" -> "短剧"
            "variety" -> "综艺"
            else -> "电影"
        }
    LaunchedEffect(categoryKey) {
        val expectedTitles = sectionTitles
        val currentSections =
            Graph.repo.homeRecommendationsFlow.value[
                com.qiubo.optimaltv.data.repo.VodRepository.categoryCid(categoryKey)
            ].orEmpty()
        if (expectedTitles.any { title -> currentSections.none { it.name == title } }) {
            Graph.repo.loadHomeRecommendations(categoryKey)
        }
    }
    val s = rememberUiScale()
    // 返回落焦（需求 影视#6-2）：从详情/播放返回时回到上次聚焦的卡片
    val returnKey = remember { ReturnFocus.take("vod") }
    val tabFocus = remember { FocusRequester() }
    val heroPlayFocus = remember { FocusRequester() }
    InitialFocusEffect(tabFocus, "vod-tab", enabled = returnKey == null)
    var restoredFocus by remember { mutableStateOf(false) }
    var returnToHeroOnResume by rememberSaveable { mutableStateOf(false) }
    val returnFocusScope = rememberCoroutineScope()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && returnToHeroOnResume) {
                    returnToHeroOnResume = false
                    returnFocusScope.launch {
                        repeat(16) {
                            if (runCatching { heroPlayFocus.requestFocus() }.isSuccess) return@launch
                            withFrameNanos { }
                        }
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val selectedHeroIds = remember { mutableStateMapOf<String, String>() }
    val selectedHeroId = selectedHeroIds[tabKey]
    val heroIdxTop = heroesTop.indexOfFirst { it.id == selectedHeroId }.coerceAtLeast(0)
    // 需求 #15：海报不自动轮播，仅焦点在播放钮上左右键手动切换

    // 根页返回：首按只提示，2.5s 内再按才彻底退出（不把应用退到后台）
    val listStates = remember { mutableMapOf<String, LazyListState>() }
    val vodListState = listStates.getOrPut(tabKey) { createTvLazyListState() }
    val heroFocusScope = rememberCoroutineScope()
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) { if (hint != null) { kotlinx.coroutines.delay(2500); hint = null } }
    val exitApp = com.qiubo.optimaltv.ui.components.rememberDoubleBackExit { hint = it }
    fun focusHeroFromContent() {
        heroFocusScope.launch {
            vodListState.scrollToItem(0)
            withFrameNanos { }
            repeat(16) {
                if (runCatching { heroPlayFocus.requestFocus() }.isSuccess) return@launch
                withFrameNanos { }
            }
        }
    }
    val sectionMoveInFlight = remember { booleanArrayOf(false) }
    fun sectionEntries(index: Int): List<VodItem> =
        categorySections.firstOrNull { it.name == sectionTitles.getOrNull(index) }?.items.orEmpty()
    fun focusSection(targetIndex: Int) {
        if (targetIndex !in sectionTitles.indices) return
        heroFocusScope.launch {
            try {
                focusHomeSection(
                    listState = vodListState,
                    targetIndex = targetIndex,
                    firstCardFocus = sectionFocusRequesters[targetIndex],
                )
            } finally {
                sectionMoveInFlight[0] = false
            }
        }
    }
    fun moveSection(fromIndex: Int, step: Int): Boolean {
        if (sectionMoveInFlight[0]) return true
        var target = fromIndex + step
        while (target in sectionTitles.indices) {
            if (sectionEntries(target).isNotEmpty()) {
                sectionMoveInFlight[0] = true
                focusSection(target)
                return true
            }
            target += step
        }
        if (step < 0) {
            focusHeroFromContent()
            return true
        }
        return false
    }
    BackHandler {
        exitApp()
    }
    LaunchedEffect(returnKey, heroesTop) {
        returnKey ?: return@LaunchedEffect
        if (restoredFocus) return@LaunchedEffect
        restoredFocus = true
        repeat(24) {
            val target = heroPlayFocus
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
        runCatching { tabFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(OtvColors.Bg).tvReducedSemantics()) {
        LazyColumn(
            state = vodListState,
            modifier = Modifier
                .fillMaxSize()
                .background(OtvColors.Bg),
        ) {
            item(key = "home-page", contentType = "home-hero") {
                Column(Modifier.height(500f.sx(s)).fillMaxWidth()) {
                    VodHeroBand(
                        nav, heroesTop, s, heroIdxTop, heroPlayFocus,
                        emptyTitle = "暂无热门内容",
                        emptyMessage = "热门${categoryLabel}加载完成后会显示在这里。",
                        listState = vodListState,
                        onOpenContent = { returnToHeroOnResume = true },
                        onPrev = {
                            selectedHeroIds[tabKey] = heroesTop.getOrNull(
                                (heroIdxTop - 1 + heroesTop.size.coerceAtLeast(1)) % heroesTop.size.coerceAtLeast(1),
                            )?.id.orEmpty()
                        },
                        onNext = {
                            selectedHeroIds[tabKey] = heroesTop.getOrNull(
                                (heroIdxTop + 1) % heroesTop.size.coerceAtLeast(1),
                            )?.id.orEmpty()
                        },
                    )
                }
            }
            sectionTitles.forEachIndexed { index, title ->
                val entries = categorySections.firstOrNull { it.name == title }?.items.orEmpty()
                item(
                    key = "recommendations-header:$index",
                    contentType = "home-recommendation-header",
                ) {
                    HomeRecommendationHeader(title = title, scale = s)
                }
                item(
                    key = "recommendations-row:$index",
                    contentType = "home-recommendation-row",
                ) {
                    HomeRecommendationRow(
                        nav = nav,
                        entries = entries,
                        scale = s,
                        rowState = sectionRowStates[index],
                        firstCardFocus = sectionFocusRequesters[index],
                        rows =
                            if (index == 0 && !com.qiubo.optimaltv.TvPerformanceProfile.isConstrained) {
                                2
                            } else {
                                1
                            },
                        loading = categorySections.none { it.name == title },
                        onDownFromLastRow = { moveSection(index, 1) },
                        onUpFromTopRow = { moveSection(index, -1) },
                    )
                }
            }
        }
        // TabBar 悬浮：固定海报直接透出，仅保留顶黑渐变保证可读性
        MainTabBar(
            tabKey, { key -> navigateToTab(nav, key) }, { nav.navigate("search") }, tabFocus,
            contentDownFocus = heroPlayFocus,
            contentDownScrollTop = { vodListState.scrollToItem(0) },
            autoHide = true,   // v1.16：光标下移进内容区隐藏顶栏，回顶部显示
        )
        OtvHint(hint)
    }
}

/** 顶部轮播条目；[history] 非空表示点击播放按钮可直接续播。 */
internal data class HeroEntry(
    val id: String,
    val title: String,
    val bg: String,
    val tag: String,
    val fallbackBg: String = "",
    val episodeNumber: Int? = null,
    val history: HistoryEntity? = null,
)

/**
 * 首页轮播只取本地历史按 updatedAt 倒序的前 5 个。
 *
 * 目录快照只用于补 [VodItem.heroUrl] 的高清横版图；目录尚未加载时仍用历史记录内的
 * 海报兜底，因此新登录后的云端历史也能立即可见，不被片库网络加载阻塞。
 */
internal fun recentHistoryHeroes(
    history: List<HistoryEntity>,
    catalog: com.qiubo.optimaltv.data.repo.Aggregated?,
    tabKey: String? = null,
    resolvedItems: Map<String, VodItem> = emptyMap(),
    catalogById: Map<String, VodItem>? = null,
): List<HeroEntry> {
    val catalogIndex =
        catalogById
            ?: catalog?.groups?.values
                ?.flatten()
                ?.associateBy(VodItem::id)
                .orEmpty()
    return history.asSequence()
        .sortedByDescending(HistoryEntity::updatedAt)
        .distinctBy(HistoryEntity::vodId)
        .filter { entry ->
            tabKey == null || recentHistoryCategory(resolvedItems[entry.vodId] ?: catalogIndex[entry.vodId]) == tabKey
        }
        .take(HOME_RECENT_HERO_LIMIT)
        .map { h ->
            val item = resolvedItems[h.vodId] ?: catalogIndex[h.vodId]
            HeroEntry(
                id = h.vodId,
                title = h.title.ifBlank { item?.title.orEmpty() },
                bg = item?.heroUrl?.takeIf(String::isNotBlank)
                    ?: item?.posterUrl?.takeIf(String::isNotBlank)
                    ?: h.posterUrl,
                fallbackBg = h.posterUrl,
                tag = item?.remark.orEmpty(),
                episodeNumber = (h.epIndex + 1).takeIf {
                    (recentHistoryCategory(item) ?: tabKey) in MULTI_EPISODE_HERO_CATEGORIES
                },
                history = h,
            )
        }
        .toList()
}

internal fun recentHistoryCategory(item: VodItem?): String? {
    item ?: return null
    val metadata = (listOf(item.meta) + item.tags).joinToString(" / ")
    val category = when (item.categoryId.removePrefix("douban:").lowercase()) {
        "movie" -> "movie"
        "tv", "series" -> "tv"
        "short", "short_drama" -> "short"
        "variety" -> "variety"
        "anime", "animation" -> "anime"
        else -> when {
            metadata.contains("短剧") -> "short"
            metadata.contains("综艺") -> "variety"
            metadata.contains("动漫") || metadata.contains("动画") -> "anime"
            listOf("电视剧", "连续剧", "国产剧", "日剧", "韩剧", "美剧", "欧美剧").any(metadata::contains) -> "tv"
            metadata.contains("电影") || Regex("(剧情|喜剧|动作|爱情|恐怖|惊悚|科幻)片").containsMatchIn(metadata) -> "movie"
            else -> ""
        }
    }
    return when (category) {
        "movie" -> "movie"
        "tv", "series" -> "series"
        "short", "short_drama" -> "short"
        "variety" -> "variety"
        "anime", "animation" -> {
            val area = item.area.trim().ifBlank {
                metadata.split("/").map(String::trim).firstOrNull { part ->
                    listOf("中国", "大陆", "国产", "台湾", "香港", "日本", "美国", "韩国", "英国", "法国").any(part::contains)
                }.orEmpty()
            }
            if (area.isNotBlank() && listOf("中国", "大陆", "国产", "台湾", "香港", "China", "CN")
                    .none { area.contains(it, ignoreCase = true) }) null else "anime"
        }
        else -> null
    }
}

/**
 * 固定 hero 海报带：热门海报顶到屏幕最顶并铺满 500px 高度；
 * 首页自动轮播，分类页同样展示热门内容；播放钮上左右键可手动切换。
 */
@Composable
internal fun VodHeroBand(
    nav: NavController,
    heroes: List<HeroEntry>,
    s: Float,
    idx: Int,
    playFocus: FocusRequester,
    emptyTitle: String,
    emptyMessage: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpenContent: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    autoAdvance: Boolean = true,
    onDown: (() -> Boolean)? = null,
) {
    val hero = heroes.getOrNull(idx % heroes.size.coerceAtLeast(1))
    val heroScope = androidx.compose.runtime.rememberCoroutineScope()
    val heroCtx = androidx.compose.ui.platform.LocalContext.current
    val heroImageSize =
        TvImageRequestSpec.heroSize(heroCtx.resources.displayMetrics.widthPixels)
    var usingFallback by remember(hero?.id) { mutableStateOf(false) }
    val heroArtwork =
        hero?.let { entry ->
            if (usingFallback && entry.fallbackBg.isNotBlank()) entry.fallbackBg else entry.bg
        }.orEmpty()
    var heroFocused by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var resumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) resumed = true
                if (event == Lifecycle.Event.ON_PAUSE) resumed = false
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(hero?.id, heroes.size, autoAdvance, resumed, heroFocused) {
        if (!autoAdvance || !resumed || heroFocused || heroes.size <= 1) return@LaunchedEffect
        delay(7_000L)
        onNext()
    }
    Box(Modifier.fillMaxSize()) {
        hero?.let { h ->
            // 需求⑦（2026-09-07）：左右切换影片时海报交叉淡入（Crossfade 双层叠化），
            // 不再瞬时跳变；内容列随 remember(h.id) 自然重组
            val heroImage: @Composable (String) -> Unit = { url ->
                AsyncImage(
                    // v1.10：ImageLoader 已放开硬件位图（低端机滚动性能），仅 hero 超宽图
                    // （3360×1080，MuMu 硬件位图解码失败=图空白）按请求走软件解码
                    model = remember(heroCtx, url, heroImageSize) {
                        TvImageRequestSpec.heroRequest(heroCtx, url, heroImageSize)
                            .crossfade(false)
                            .build()
                    },
                    contentDescription = h.title,
                    contentScale = ContentScale.Crop,
                    // The hero band is only 500px high. Bias the crop towards the
                    // upper part of the source instead of cutting faces at both edges.
                    alignment = BiasAlignment(0f, -0.5f),
                    onState = { st ->
                        if (st is coil.compose.AsyncImagePainter.State.Error) {
                            if (!usingFallback && h.fallbackBg.isNotBlank() && h.fallbackBg != h.bg) {
                                usingFallback = true
                                return@AsyncImage
                            }
                            com.qiubo.optimaltv.OtvLog.w(
                                "vod hero poster 加载失败: " +
                                    "${st.result.throwable.javaClass.simpleName} " +
                                    "url=${com.qiubo.optimaltv.ui.components.posterLogLabel(heroArtwork)}",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (com.qiubo.optimaltv.TvPerformanceProfile.navigationMotionEnabled) {
                androidx.compose.animation.Crossfade(
                    targetState = heroArtwork,
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 450),
                    label = "vodHeroBg",
                ) { url ->
                    heroImage(url)
                }
            } else {
                heroImage(heroArtwork)
            }
        }
        // White veil + left dark gradient are pre-composited into one gradient.
        // They used to be two full-screen layers; on the S621 that doubled the
        // fragment cost of every tab switch without changing the final pixels.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0x4C434343),
                        0.30f to Color(0x2C888888),
                        0.55f to Color(0x1AFFFFFF),
                        1f to Color(0x1AFFFFFF),
                    ),
                ),
        )
        // 底部渐黑：与下方内容区（实底 Bg）自然衔接
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(160f.sx(s))
                .background(Brush.verticalGradient(0f to Color.Transparent, 1f to OtvColors.Bg)),
        )
        // vcontent（top 340：title 44 / cat 27 / desc 两行；播放钮已移出本列，见下方注释）
        hero?.let { h ->
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 91f.sx(s), top = VOD_HERO_CONTENT_TOP.sx(s))
                    .width(760f.sx(s))
                    // 2026-09-19：文字列总闸（设计内容高 210）——单块见各自 heightIn
                    .heightIn(max = VOD_HERO_TEXT_MAX.sx(s)),
            ) {
                Text(
                    h.title, color = OtvColors.White, fontSize = 44f.sxs(s), fontWeight = FontWeight.Bold,
                    lineHeight = 46f.sxs(s), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    // 行高/字体内边距随设备字体变的夹子（设计框高 64）
                    modifier = Modifier.heightIn(max = VOD_HERO_TITLE_MAX.sx(s)),
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = if (com.qiubo.optimaltv.TvPerformanceProfile.isConstrained) {
                            null
                        } else {
                            androidx.compose.ui.graphics.Shadow(
                                Color(0x99000000),
                                offset = androidx.compose.ui.geometry.Offset(0f, 2f),
                                blurRadius = 20f.sx(s) / 1.dp,
                            )
                        },
                    ),
                )
                Spacer(Modifier.height(8f.sx(s)))
                // vod-cat（meta 行先显 tag，detail 到达后覆盖；评分拼行尾）
                val cachedBrief = remember(h.id) { Graph.repo.peekBrief(h.id) }
                var metaLine by remember(h.id) {
                    mutableStateOf(
                        cachedBrief?.first
                            ?.split("/")
                            ?.map { it.trim() }
                            ?.filter { it.isNotBlank() }
                            ?: listOfNotNull(
                                h.tag.takeIf(String::isNotBlank),
                            ),
                    )
                }
                var desc by remember(h.id) { mutableStateOf(cachedBrief?.second.orEmpty()) }
                var rating by remember(h.id) { mutableStateOf(cachedBrief?.third ?: 0.0) }
                LaunchedEffect(h.id) {
                    if (cachedBrief != null) return@LaunchedEffect
                    desc = "加载简介…"
                    Graph.repo.vodBrief(h.id)?.let { (m, d, r) ->
                        if (m.isNotBlank()) {
                            metaLine = m.split("/").map { it.trim() }.filter { it.isNotBlank() }
                        }
                        desc = d.ifBlank { h.title + " · My TV 精选内容，一键播放。" }
                        rating = r
                    } ?: run { desc = h.title + " · My TV 精选内容，一键播放。" }
                }
                if (metaLine.isNotEmpty() || rating > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14f.sx(s)),
                        // 夹回设计框高 54（设备字体行高异常时 meta 不再把简介/按钮往下推）
                        modifier = Modifier.heightIn(max = VOD_HERO_META_MAX.sx(s)),
                    ) {
                        if (metaLine.isNotEmpty()) {
                            Text(
                                // 2026-09-19：段数上限 6——豆瓣 regions+genres 最多可到 10 段
                                //（奥德赛：2026/美国/加拿大/冰岛/新西兰/摩洛哥/英国/动作/历史/奇幻），
                                // 760 宽单行放不下会被省略号截断且把评分挤出列外；截到 6 段与其它影片观感一致
                                metaLine.take(6).joinToString(" / "),
                                color = OtvColors.White.copy(alpha = 0.8f), fontSize = 27f.sxs(s), fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (rating > 0) {
                            Text("★ " + "%.1f".format(rating), color = OtvColors.White50, fontSize = 15f.sxs(s), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (desc.isNotBlank()) {
                    Spacer(Modifier.height(10f.sx(s)))
                    Text(
                        desc, color = OtvColors.White55, fontSize = 27f.sxs(s), fontWeight = FontWeight.Medium,
                        lineHeight = 35f.sxs(s), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        // 夹回设计框高 74（两行）；超出只收敛行数，不再挤压按钮
                        modifier = Modifier.heightIn(max = VOD_HERO_DESC_MAX.sx(s)),
                    )
                }
            }
            // vod-actions：btn-vod 白50%底黑字 h64 px40 r32 字26 Bold；聚焦纯白（focusedBg）+scale1.02
            // 首屏压缩后仍锚定在海报带底部，避免和上移后的简介重叠。
            // 起因：用户报障「奥德赛」——meta 10 段 + 简介更长时文字列被撑高，钮被顶出海报带、
            // 整颗不可见（截图实测钮落在带外）。现在钮不参与文字流测量，简介再长也只让 desc
            // 收敛行数（VOD_HERO_TEXT_MAX 封顶），钮位置与设计稿逐像素一致。
            Row(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 91f.sx(s), bottom = VOD_HERO_BTN_BOTTOM.sx(s)),
                horizontalArrangement = Arrangement.spacedBy(18f.sx(s)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .focusRequester(playFocus)
                        .dpadFocusable(
                            scaleFocused = 1.02f, focusedBg = OtvColors.White, focusedBgRadius = 32f.sx(s),
                            // 需求 #4：焦点在 hero 播放钮上时左右键翻页切换影片，海报跟随切换；
                            // UP 由引擎 topFocus 逃逸回当前页 tab（无需显式声明）
                            navOverride = { dir ->
                                when (dir) {
                                    NAV_LEFT -> if (heroes.size > 1) { onPrev(); true } else false
                                    NAV_RIGHT -> if (heroes.size > 1) { onNext(); true } else false
                                    NAV_DOWN -> onDown?.invoke() == true
                                    else -> false
                                }
                            },
                            // 需求 影视#1：光标移到「立即播放」时页面滚动回最上方
                            onFocusedChange = {
                                heroFocused = it
                                if (it) {
                                    ReturnFocus.mark("vod", "hero")
                                    if (listState.firstVisibleItemIndex != 0 ||
                                        listState.firstVisibleItemScrollOffset != 0
                                    ) {
                                        heroScope.launch { listState.scrollToItem(0) }
                                    }
                                }
                            },
                        ) {
                            onOpenContent()
                            nav.navigate("detail/" + URLEncoder.encode(h.id, "UTF-8"))
                        }
                        .background(OtvColors.White.copy(alpha = 0.5f), RoundedCornerShape(32f.sx(s)))
                        .height(64f.sx(s))
                        .padding(horizontal = 40f.sx(s)),
                ) {
                    Text(
                        "立即播放",
                        color = OtvColors.Bg, fontSize = 26f.sxs(s), fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        if (hero == null) {
            EmptyPopularHero(nav, playFocus, s, emptyTitle, emptyMessage)
        }
        // carousel-dots（带内底部居中：黑50%胶囊，当前页白点）
        if (heroes.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8f.sx(s)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 30f.sx(s))
                    .background(Color(0x80000000), RoundedCornerShape(28f.sx(s)))
                    .padding(horizontal = 10f.sx(s), vertical = 6f.sx(s)),
            ) {
                heroes.forEachIndexed { i, _ ->
                    Box(
                        Modifier
                            .size(10f.sx(s))
                            .background(
                                if (i == idx % heroes.size) OtvColors.White else OtvColors.White30,
                                CircleShape,
                            ),
                    )
                }
            }
        }
    }
}

private val MULTI_EPISODE_HERO_CATEGORIES = setOf("series", "anime", "short", "variety")

/** 热门内容暂时不可用时，给标签栏 DOWN 一个明确落点。 */
@Composable
private fun EmptyPopularHero(
    nav: NavController,
    playFocus: FocusRequester,
    s: Float,
    emptyTitle: String,
    emptyMessage: String,
) {
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 91f.sx(s)),
    ) {
        Text(
            emptyTitle,
            color = OtvColors.White,
            fontSize = 44f.sxs(s),
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(14f.sx(s)))
        Text(
            emptyMessage,
            color = OtvColors.White55,
            fontSize = 27f.sxs(s),
        )
        Spacer(Modifier.height(28f.sx(s)))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .focusRequester(playFocus)
                .dpadFocusable(
                    scaleFocused = 1.02f,
                    focusedBg = OtvColors.White,
                    focusedBgRadius = 32f.sx(s),
                ) { nav.navigate("search") }
                .background(OtvColors.White.copy(alpha = 0.5f), RoundedCornerShape(32f.sx(s)))
                .height(64f.sx(s))
                .padding(horizontal = 40f.sx(s)),
        ) {
            Text(
                "去搜索影片",
                color = OtvColors.Bg,
                fontSize = 26f.sxs(s),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

