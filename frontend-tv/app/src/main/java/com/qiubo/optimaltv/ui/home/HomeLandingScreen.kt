package com.qiubo.optimaltv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.qiubo.optimaltv.Graph
import com.qiubo.optimaltv.data.model.VodItem
import com.qiubo.optimaltv.data.repo.VodRepository
import com.qiubo.optimaltv.ui.components.HOME_TAB_KEY
import com.qiubo.optimaltv.ui.components.InitialFocusEffect
import com.qiubo.optimaltv.ui.components.MainTabBar
import com.qiubo.optimaltv.ui.components.OtvHint
import com.qiubo.optimaltv.ui.components.navigateToTab
import com.qiubo.optimaltv.ui.components.rememberTvLazyListState
import com.qiubo.optimaltv.ui.components.tvReducedSemantics
import com.qiubo.optimaltv.ui.player.PlaybackResumePolicy
import com.qiubo.optimaltv.ui.theme.OtvColors
import com.qiubo.optimaltv.ui.theme.rememberUiScale
import com.qiubo.optimaltv.ui.theme.sx
import com.qiubo.optimaltv.ui.theme.sxs
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Composable
internal fun HomeLandingScreen(nav: NavController) {
    val scale = rememberUiScale()
    val listState = rememberTvLazyListState()
    val catalog by Graph.repo.state.collectAsStateWithLifecycle()
    val historyFlow = remember { Graph.db.vodDao().historyFlow(Int.MAX_VALUE) }
    val history by historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val recommendations by Graph.repo.homeRecommendationsFlow.collectAsStateWithLifecycle()

    val homeItems =
        remember(recommendations) {
            HomeHeroPolicy.HOME_CATEGORIES.associateWith { category ->
                val cid = VodRepository.categoryCid(category)
                recommendations[cid].orEmpty()
                    .firstOrNull { it.name == "大家都在看" }
                    ?.items
                    .orEmpty()
            }
        }
    val heroes = remember(homeItems) { HomeHeroPolicy.forHome(homeItems) }

    val catalogById =
        remember(catalog.catalog) {
            catalog.catalog?.groups?.values
                ?.flatten()
                ?.associateBy(VodItem::id)
                .orEmpty()
        }
    val resolvedHistory = remember { mutableStateMapOf<String, VodItem>() }
    LaunchedEffect(history.map { it.vodId }.take(RecentWatchPolicy.MAX_ITEMS), catalogById) {
        val missing =
            history.asSequence()
                .map { it.vodId }
                .distinct()
                .filter { it !in catalogById && it !in resolvedHistory }
                .take(RecentWatchPolicy.MAX_ITEMS)
                .toList()
        if (missing.isEmpty()) return@LaunchedEffect
        val permits = Semaphore(4)
        coroutineScope {
            missing.map { id ->
                async {
                    permits.withPermit {
                        try {
                            Graph.repo.resolveDetail(id)?.let { resolvedHistory[id] = it }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                        }
                    }
                }
            }.awaitAll()
        }
    }
    val recentEntries =
        remember(history, catalogById, resolvedHistory.size) {
            RecentWatchPolicy.build(history, catalogById, resolvedHistory)
        }

    LaunchedEffect(Unit) {
        Graph.repo.ensureCatalogFresh()
        HomeHeroPolicy.HOME_CATEGORIES.forEach { category ->
            val cid = VodRepository.categoryCid(category) ?: return@forEach
            val hasPopular =
                Graph.repo.homeRecommendationsFlow.value[cid].orEmpty()
                    .any { it.name == "大家都在看" && it.items.isNotEmpty() }
            if (!hasPopular) Graph.repo.loadHomeFirstSection(category)
        }
    }

    val tabFocus = remember { FocusRequester() }
    val heroPlayFocus = remember { FocusRequester() }
    val recentFirstFocus = remember { FocusRequester() }
    InitialFocusEffect(tabFocus, "home-tab")
    val scope = rememberCoroutineScope()
    var tabFocused by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) {
        if (hint != null) {
            kotlinx.coroutines.delay(2500)
            hint = null
        }
    }
    val exitApp = com.qiubo.optimaltv.ui.components.rememberDoubleBackExit { hint = it }

    var selectedHeroId by rememberSaveable { mutableStateOf<String?>(null) }
    val heroIndex =
        heroes.indexOfFirst { it.id == selectedHeroId }
            .takeIf { it >= 0 }
            ?: 0
    fun moveHero(delta: Int) {
        if (heroes.isEmpty()) return
        val next = Math.floorMod(heroIndex + delta, heroes.size)
        selectedHeroId = heroes[next].id
    }
    fun focusRecent(): Boolean {
        if (recentEntries.isEmpty()) return false
        scope.launch {
            if (listState.firstVisibleItemIndex > 1) listState.scrollToItem(1)
            withFrameNanos { }
            repeat(16) {
                if (runCatching { recentFirstFocus.requestFocus() }.isSuccess) return@launch
                withFrameNanos { }
            }
        }
        return true
    }
    fun focusHero(): Boolean {
        scope.launch {
            listState.scrollToItem(0)
            withFrameNanos { }
            repeat(16) {
                if (runCatching { heroPlayFocus.requestFocus() }.isSuccess) return@launch
                withFrameNanos { }
            }
        }
        return true
    }

    BackHandler {
        if (tabFocused) {
            exitApp()
        } else {
            scope.launch {
                listState.scrollToItem(0)
                runCatching { tabFocus.requestFocus() }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(OtvColors.Bg).tvReducedSemantics()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(OtvColors.Bg),
        ) {
            item(key = "home-hero", contentType = "home-hero") {
                Column(Modifier.height(500f.sx(scale)).fillMaxWidth()) {
                    VodHeroBand(
                        nav = nav,
                        heroes = heroes,
                        s = scale,
                        idx = heroIndex,
                        playFocus = heroPlayFocus,
                        emptyTitle = "暂无热门内容",
                        emptyMessage = "热门电影、电视剧和国漫会显示在这里。",
                        listState = listState,
                        onOpenContent = {},
                        onPrev = { moveHero(-1) },
                        onNext = { moveHero(1) },
                        autoAdvance = true,
                        onDown = if (recentEntries.isEmpty()) null else ::focusRecent,
                    )
                }
            }
            item(key = "recent-header", contentType = "home-recent-header") {
                HomeRecommendationHeader("最近观看", scale)
            }
            item(key = "recent-watch", contentType = "home-recent-watch") {
                if (recentEntries.isEmpty()) {
                    EmptyRecentWatch(scale)
                } else {
                    RecentWatchRow(
                        items = recentEntries,
                        scale = scale,
                        firstCardFocus = recentFirstFocus,
                        onUpFromRow = ::focusHero,
                    ) { entry ->
                        val resumeMs =
                            PlaybackResumePolicy.startPositionMs(
                                0L,
                                entry.history.positionMs,
                                entry.history.durationMs,
                            )
                        nav.navigate(
                            "player/" + URLEncoder.encode(entry.history.vodId, "UTF-8") +
                                "/${entry.history.epIndex}?resumeMs=$resumeMs",
                        )
                    }
                }
            }
            item(key = "home-bottom-space") {
                Spacer(Modifier.height(36f.sx(scale)))
            }
        }
        MainTabBar(
            currentKey = HOME_TAB_KEY,
            onSelect = { key -> navigateToTab(nav, key) },
            onSearch = { nav.navigate("search") },
            initialFocus = tabFocus,
            contentDownFocus = heroPlayFocus,
            contentDownScrollTop = { listState.scrollToItem(0) },
            onTabFocused = { tabFocused = it },
            autoHide = true,
        )
        OtvHint(hint)
    }
}

@Composable
private fun EmptyRecentWatch(scale: Float) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(220f.sx(scale))
                .padding(horizontal = 86f.sx(scale)),
    ) {
        Column {
            Text(
                "还没有观看记录",
                color = OtvColors.White,
                fontSize = 30f.sxs(scale),
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10f.sx(scale)))
            Text(
                "播放过的影片会出现在这里，并显示续播进度。",
                color = OtvColors.White55,
                fontSize = 24f.sxs(scale),
            )
        }
    }
}
