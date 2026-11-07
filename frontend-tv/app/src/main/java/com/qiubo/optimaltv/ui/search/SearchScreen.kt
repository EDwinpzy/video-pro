package com.qiubo.optimaltv.ui.search

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavController
import com.qiubo.optimaltv.BuildConfig
import com.qiubo.optimaltv.Graph
import com.qiubo.optimaltv.R
import com.qiubo.optimaltv.data.model.VodItem
import com.qiubo.optimaltv.ui.components.CARD_FOCUS_SCALE
import com.qiubo.optimaltv.ui.components.FocusRegistry
import com.qiubo.optimaltv.ui.components.LocalNavOverride
import com.qiubo.optimaltv.ui.components.MainTabBar
import com.qiubo.optimaltv.ui.components.NAV_DOWN
import com.qiubo.optimaltv.ui.components.NAV_UP
import com.qiubo.optimaltv.ui.components.OtvNav
import com.qiubo.optimaltv.ui.components.ProvideNavScrolls
import com.qiubo.optimaltv.ui.components.RemotePoster
import com.qiubo.optimaltv.ui.components.ReturnFocus
import com.qiubo.optimaltv.ui.components.SystemEditText
import com.qiubo.optimaltv.ui.components.dpadFocusable
import com.qiubo.optimaltv.ui.components.lazyGridNavContainer
import com.qiubo.optimaltv.ui.components.rememberSystemEditTextHandle
import com.qiubo.optimaltv.ui.components.tvReducedSemantics
import com.qiubo.optimaltv.ui.theme.OtvColors
import com.qiubo.optimaltv.ui.theme.rememberUiScale
import com.qiubo.optimaltv.ui.theme.sx
import com.qiubo.optimaltv.ui.theme.sxs
import com.qiubo.optimaltv.voice.VoiceSearchBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * 搜索页：顶部标签栏 → Android 系统 EditText（系统输入法与候选栏）→
 * 结果分类 tab → 结果网格。文本输入、光标、选择、输入法和候选词均由系统控件负责。
 */
/** 搜索结果分类 tab（需求④）。「综艺」补齐：6 个，移动版竖屏每行 3 个刚好两整行。 */
internal val RESULT_CATS = listOf("全部", "电影", "电视剧", "动漫", "综艺", "短剧")

internal const val RESULT_FOCUS_RETRY_ATTEMPTS = 12
internal const val SEARCH_RESULT_COLUMNS = 7

/** The matching result directly above this grid item, or null when it is in the first row. */
internal fun previousSearchResultIndex(index: Int, columns: Int = SEARCH_RESULT_COLUMNS): Int? =
    if (columns > 0 && index >= columns) index - columns else null

/** The matching result directly below this grid item, or null when it is in the last row. */
internal fun nextSearchResultIndex(
    index: Int,
    itemCount: Int,
    columns: Int = SEARCH_RESULT_COLUMNS,
): Int? {
    if (columns <= 0 || index < 0 || index >= itemCount) return null
    return (index + columns).takeIf { it < itemCount }
}

/** True only when the entire lazy-grid item is inside the vertical viewport. */
internal fun isSearchResultItemFullyVisible(
    itemTop: Int,
    itemBottom: Int,
    viewportStart: Int,
    viewportEnd: Int,
): Boolean = itemBottom > itemTop && itemTop >= viewportStart && itemBottom <= viewportEnd

/** Compose 的 requestFocus() 在当前 BOM 返回 Unit；用焦点事件确认后再结束重试。 */
internal fun shouldRetryResultFocus(attempt: Int, focusObserved: Boolean): Boolean =
    !focusObserved && attempt < RESULT_FOCUS_RETRY_ATTEMPTS - 1

/** Debug-only deterministic items for exercising the real search grid and D-pad focus path. */
internal fun searchFocusFixtureItems(count: Int): List<VodItem> =
    List(count.coerceIn(0, 60)) { index ->
        VodItem(
            id = "debug:search-focus-$count-$index",
            sourceId = "debug",
            title = "焦点测试 ${index + 1}",
            categoryId = "douban:movie",
        )
    }

/** 详情 meta/集数 → 分类（源站频道 tag：欧美剧/国产剧等以「剧」结尾的类型 + 综艺/动漫/短剧直配） */
private val TV_TAG_RE = Regex("欧美剧|国产剧|香港剧|台湾剧|韩国剧|日本剧|海外剧|泰剧|美剧|英剧|连续剧|电视剧|网剧")

/** 调试词注入通道（adb 广播 → 搜索页 query，仅 debug 构建有写入方；绕过模拟器丢键问题） */
object SearchDebugBus {
    @Volatile var pendingQuery: String? = null
    @Volatile var pendingFixtureCount: Int? = null
    @Volatile var currentQuery: String = ""
}

internal fun classifyVod(meta: String, epCount: Int): String = when {
    meta.contains("短剧") -> "短剧"
    meta.contains("动漫") -> "动漫"
    meta.contains("综艺") -> "综艺"
    TV_TAG_RE.containsMatchIn(meta) -> "电视剧"
    epCount > 1 -> "电视剧"
    else -> "电影"
}

/** categoryId → 分类；`douban:<mediaType>` 与旧 `hhkan:<频道号>` 都认，认不出返回 null。 */
internal fun searchResultCategory(categoryId: String): String? =
    when (categoryId.substringAfterLast(':').trim()) {
        "movie", "1" -> "电影"
        "tv", "2" -> "电视剧"
        "anime", "3" -> "动漫"
        "variety", "4" -> "综艺"
        "short", "6" -> "短剧"
        else -> null
    }

/** 认不出分类的条目才拉详情兜底，最多这么多条。 */
private const val DETAIL_FALLBACK_LIMIT = 24

/**
 * 合并目录内命中与后端结果：remote 在前、local 补充，同 id 取先出现的那条。
 *
 * 本地目录按「首次出现的浏览频道」打 categoryId（那是分区不是真实分类），
 * local 排在前面会让错分类的副本遮住后端已认定 douban:anime 的同名条目。
 */
internal fun mergeSearchResults(
    localHits: List<com.qiubo.optimaltv.data.model.VodItem>,
    remote: List<com.qiubo.optimaltv.data.model.VodItem>,
): List<com.qiubo.optimaltv.data.model.VodItem> =
    (remote + localHits).distinctBy { it.id }

/**
 * 搜索结果 → (id → 分类)。categoryId 可认的对全部结果生效；认不出的才走
 * [detailFallback] 拉详情，且最多 [DETAIL_FALLBACK_LIMIT] 条。
 */
internal suspend fun classifySearchResults(
    items: List<com.qiubo.optimaltv.data.model.VodItem>,
    detailFallback: suspend (com.qiubo.optimaltv.data.model.VodItem) -> String?,
): Map<String, String> {
    val map = HashMap<String, String>(items.size)
    var fallbackBudget = DETAIL_FALLBACK_LIMIT
    for (item in items) {
        val known = searchResultCategory(item.categoryId)
        if (known != null) {
            map[item.id] = known
            continue
        }
        if (fallbackBudget <= 0) continue
        fallbackBudget--
        detailFallback(item)?.let { map[item.id] = it }
    }
    return map
}

@Composable
fun SearchScreen(nav: NavController) {
    var query by rememberSaveable { mutableStateOf("") }
    // 返回落焦（需求 影视#6-2）：从结果卡进入详情再返回时，回到该结果卡
    val returnKey = remember { ReturnFocus.take("search") }
    val tabFocus = remember { FocusRequester() }
    val cardReg = remember { FocusRegistry() }
    val scope = rememberCoroutineScope()
    val searchInput = rememberSystemEditTextHandle()
    var restoredFocus by remember { mutableStateOf(false) }
    val voiceSearchAtEntry = remember { VoiceSearchBus.hasPendingQuery() }
    val s = rememberUiScale()
    SideEffect {
        SearchDebugBus.currentQuery = query
    }

    // 区域落点句柄（需求⑤：定向跳转目标）
    val firstTabFocus = remember { FocusRequester() }    // 分类 tab 行首（全部）
    val firstResultFocus = remember { FocusRequester() } // 结果首卡
    // 结果网格到顶 UP（引擎逃逸兜底）回分类 tab 行
    LaunchedEffect(Unit) { OtvNav.topFocus = firstTabFocus }

    /** 定向落焦带重试（目标节点可能尚未组合/布局完成） */
    fun focusTarget(fr: FocusRequester?) {
        if (fr == null) return
        scope.launch {
            repeat(12) {
                if (runCatching { fr.requestFocus() }.isSuccess) return@launch
                delay(40)
            }
        }
    }

    // 调试注入：adb intent 注入查询词及确定数量的结果，供模拟器实测焦点导航。
    var debugFixtureItems by remember { mutableStateOf<List<VodItem>?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            VoiceSearchBus.takeQuery()?.let {
                searchInput.hideKeyboard()
                query = it
            }
            SearchDebugBus.pendingQuery?.let {
                SearchDebugBus.pendingQuery = null
                query = it
            }
            if (BuildConfig.DEBUG) {
                SearchDebugBus.pendingFixtureCount?.let { count ->
                    SearchDebugBus.pendingFixtureCount = null
                    debugFixtureItems = searchFocusFixtureItems(count)
                    android.util.Log.i("SearchFocusFixture", "results=${debugFixtureItems?.size} applied")
                }
            }
            delay(200)
        }
    }

    val searchable = remember(query) { query.trim() }
    var remote by remember(searchable) { mutableStateOf<List<VodItem>>(emptyList()) }
    var remoteLoading by remember { mutableStateOf(false) }
    var lastSearched by remember(searchable) { mutableStateOf("") }
    LaunchedEffect(searchable) {
        if (searchable.isEmpty()) {
            remote = emptyList(); remoteLoading = false
            lastSearched = ""   // 清空后重输同一词也要重搜
            return@LaunchedEffect
        }
        remoteLoading = false
        if (searchable == lastSearched) return@LaunchedEffect
        delay(250)
        if (!isActive) return@LaunchedEffect
        remoteLoading = true
        try {
            val found = Graph.live.searchRemote(searchable)
            if (isActive) {
                remote = found
                lastSearched = searchable
            }
        } finally {
            if (isActive) remoteLoading = false
        }
    }
    // 版本化 SQLite 搜索在 Repository 的 IO dispatcher 执行；种子目录回退扫描在 Default 执行。
    var localHits by remember(searchable) { mutableStateOf<List<VodItem>>(emptyList()) }
    LaunchedEffect(searchable) {
        localHits = if (searchable.isBlank()) emptyList()
        else Graph.repo.search(searchable)
    }
    val results = remember(localHits, remote, debugFixtureItems) {
        debugFixtureItems ?: mergeSearchResults(localHits, remote)
    }

    // 结果分类（需求④）：认 categoryId → 认不出才拉详情兜底（见 classifySearchResults）
    var catMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(results) {
        if (results.isEmpty()) return@LaunchedEffect
        catMap = classifySearchResults(results) { item ->
            runCatching { Graph.repo.resolveDetail(item.id) }.getOrNull()?.let { d ->
                classifyVod(d.meta, d.episodes.size)
            }
        }
    }
    var selectedCat by rememberSaveable { mutableStateOf("全部") }
    val shown = remember(results, catMap, selectedCat) {
        if (selectedCat == "全部") results
        else results.filter { catMap[it.id] == selectedCat }
    }
    val resultsGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    var focusedResultId by remember { mutableStateOf<String?>(null) }

    // 已提交搜索词变化后从全部分类和首行结果开始；否则 rememberSaveable 的旧分类和
    // 网格滚动位置会让首卡不在组合树里，分类栏 DOWN 发出的焦点请求就落空。
    LaunchedEffect(searchable) {
        selectedCat = "全部"
        resultsGridState.scrollToItem(0)
    }

    suspend fun requestResultFocus(itemId: String, index: Int, requester: FocusRequester): Boolean {
        if (index !in shown.indices) return false

        fun isMounted(): Boolean = resultsGridState.layoutInfo.visibleItemsInfo.any { it.index == index }

        // The focus target is the poster, not its taller poster-and-title grid item.
        // Native Compose brings that target into view after focus is requested.
        // Waiting for the entire cell to fit can permanently block a short viewport.
        if (!isMounted()) {
            resultsGridState.scrollToItem(index)
        }
        repeat(RESULT_FOCUS_RETRY_ATTEMPTS) { attempt ->
            if (isMounted()) {
                runCatching { requester.requestFocus() }
                val focusObserved = focusedResultId == itemId
                if (!shouldRetryResultFocus(attempt, focusObserved)) return focusObserved
            } else if (attempt == RESULT_FOCUS_RETRY_ATTEMPTS - 1) {
                return false
            }
            delay(40)
        }
        return focusedResultId == itemId
    }

    val focusFirstResult: () -> Unit = {
        val first = shown.firstOrNull()
        if (first != null) scope.launch {
            requestResultFocus(first.id, 0, firstResultFocus)
        }
    }

    fun focusResultAt(index: Int) {
        val item = shown.getOrNull(index) ?: return
        val requester = if (index == 0) firstResultFocus else cardReg.fr(item.id)
        scope.launch { requestResultFocus(item.id, index, requester) }
    }

    // 返回落焦恢复（需求 影视#6-2）：结果到齐后回到上次聚焦的结果卡
    LaunchedEffect(shown) {
        val key = returnKey ?: return@LaunchedEffect
        if (restoredFocus || shown.isEmpty()) return@LaunchedEffect
        val index = shown.indexOfFirst { it.id == key }
        if (index < 0) return@LaunchedEffect
        restoredFocus = true
        val target = if (index == 0) firstResultFocus else cardReg.fr(key)
        android.util.Log.d("OptimalTV", "search return-focus: key=$key firstId=${shown.firstOrNull()?.id}")
        if (requestResultFocus(key, index, target)) {
            android.util.Log.d("OptimalTV", "search return-focus OK")
            return@LaunchedEffect
        }
        android.util.Log.d("OptimalTV", "search return-focus FAILED → input")
        searchInput.requestFocus()
    }

    val keyDown: () -> Unit = {
        if (results.isNotEmpty()) focusTarget(firstTabFocus)
    }
    // 分类 tab UP → 原生输入框；DOWN → 结果首卡
    val tabUp: () -> Unit = { searchInput.requestFocus() }
    val tabDown: () -> Unit = { focusFirstResult() }

    // 返回键回上一页（标签栏仅键盘到顶 UP 定向可达，方向键不会误入）
    androidx.activity.compose.BackHandler { nav.popBackStack() }

    Column(Modifier.fillMaxSize().background(Color(0xFF2C2C2E)).tvReducedSemantics()) {
        // 顶部标签页（需求④：保持不变）；tab 上 DOWN 直达软键盘
        MainTabBar(
            "search",
            { key -> com.qiubo.optimaltv.ui.components.navigateToTab(nav, key) },
            { nav.navigate("search") { launchSingleTop = true } },
            initialFocus = tabFocus,
            interactive = true,
            contentDownAction = {
                searchInput.requestFocus()
                true
            },
            autoHide = true,   // v1.16：光标下移进键盘/内容区隐藏顶栏，回顶部显示
        )

        Column(Modifier.fillMaxSize().padding(horizontal = 70f.sx(s))) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(140f.sx(s))
                        .padding(top = 24f.sx(s)),
            ) {
                Icon(
                    painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    tint = OtvColors.White50,
                    modifier = Modifier.size(46f.sx(s)),
                )
                Spacer(Modifier.width(24f.sx(s)))
                SystemEditText(
                    value = query,
                    onValueChange = { query = it },
                    hint = "搜索影片、演员",
                    handle = searchInput,
                    imeAction = EditorInfo.IME_ACTION_SEARCH,
                    onImeAction = {
                        searchInput.hideKeyboard()
                        if (results.isNotEmpty()) focusTarget(firstTabFocus)
                    },
                    onFocusChanged = { focused ->
                        if (focused) {
                            com.qiubo.optimaltv.StartupReadiness.onInitialFocus("search-input")
                        }
                    },
                    onKeyDown = { event ->
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                searchInput.hideKeyboard()
                                tabFocus.requestFocus()
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                searchInput.hideKeyboard()
                                keyDown()
                                true
                            }
                            else -> false
                        }
                    },
                    textSizeSp = 48f * s,
                    autoFocus = returnKey == null && !voiceSearchAtEntry,
                    autoFocusShowsKeyboard = returnKey == null && !voiceSearchAtEntry,
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(110f.sx(s)),
                )
            }

            // ---- 结果分类 tab（需求④：全部/电影/电视剧/动漫/短剧，聚焦即切换）----
            if (results.isNotEmpty()) {
                CompositionLocalProvider(
                    // 需求⑤：UP 回候选词/键盘，DOWN 进结果首卡（失败也消费封锁）
                    LocalNavOverride provides { dir: Int ->
                        when (dir) {
                            NAV_UP -> { tabUp(); true }
                            NAV_DOWN -> { tabDown(); true }
                            else -> false
                        }
                    },
                ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14f.sx(s)),
                    modifier = Modifier
                        .padding(top = 26f.sx(s)),
                ) {
                    RESULT_CATS.forEachIndexed { ci, catName ->
                        val count = if (catName == "全部") results.size else results.count { catMap[it.id] == catName }
                        val active = selectedCat == catName
                        var tabFocused by remember { mutableStateOf(false) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .then(if (ci == 0) Modifier.focusRequester(firstTabFocus) else Modifier)
                                .dpadFocusable(
                                    scaleFocused = 1.05f, focusedBg = OtvColors.White,
                                    focusedBgRadius = 26f.sx(s),
                                    onFocusedChange = {
                                        tabFocused = it
                                    },
                                    autoSelect = true,
                                ) { selectedCat = catName }
                                .background(
                                    if (tabFocused) OtvColors.White else if (active) OtvColors.White.copy(alpha = 0.22f) else Color(0x24FFFFFF),
                                    RoundedCornerShape(26f.sx(s)),
                                )
                                .padding(
                                    horizontal = 24f.sx(s),
                                    vertical = 6f.sx(s),
                                ),
                        ) {
                            Text(
                                catName,
                                color = if (tabFocused) OtvColors.Bg else if (active) OtvColors.White else OtvColors.White.copy(alpha = 0.7f),
                                fontSize = 26f.sxs(s), fontWeight = FontWeight.SemiBold,
                            )
                            if (count > 0) {
                                Spacer(Modifier.width(8f.sx(s)))
                                Text(
                                    "$count",
                                    color = if (tabFocused) OtvColors.Bg.copy(alpha = 0.6f) else OtvColors.White50,
                                    fontSize = 20f.sxs(s),
                                )
                            }
                        }
                    }
                }
                } // CompositionLocalProvider(分类 tab 区)
            }

            // ---- 搜索结果（需求④：候选词下方区域，竖版海报卡 7 列）----
            if (shown.isEmpty()) {
                if (query.isNotBlank()) Text(
                    when {
                        results.isEmpty() && remoteLoading -> "搜索中…"
                        results.isEmpty() -> "没有匹配的影片"
                        else -> "该分类暂无结果"
                    },
                    color = OtvColors.White50, fontSize = 26f.sxs(s),
                    modifier = Modifier.padding(top = 34f.sx(s)),
                )
            } else {
                val resultScroll = remember(resultsGridState) { lazyGridNavContainer(resultsGridState) }
                ProvideNavScrolls(vertical = resultScroll) {
                    LazyVerticalGrid(
                        state = resultsGridState,
                        columns = GridCells.Fixed(SEARCH_RESULT_COLUMNS),
                        contentPadding = PaddingValues(top = 24f.sx(s), bottom = 60f.sx(s)),
                        horizontalArrangement = Arrangement.spacedBy(20f.sx(s)),
                        verticalArrangement = Arrangement.spacedBy(36f.sx(s)),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        // key 用纯 id（results 已 distinctBy id 保证唯一）：结果集变化时
                        // 节点按 id 复用，避免全量销毁重组引发焦点丢失风暴（2026-08-31）
                        items(shown.size, key = { i -> shown[i].id }) { i ->
                            CompositionLocalProvider(
                                LocalNavOverride provides { direction ->
                                    when (direction) {
                                        NAV_UP -> {
                                            val targetIndex = previousSearchResultIndex(i)
                                            if (targetIndex == null) {
                                                // Only the actual first result row can return to input.
                                                searchInput.requestFocus()
                                            } else {
                                                focusResultAt(targetIndex)
                                            }
                                            true
                                        }
                                        NAV_DOWN -> {
                                            // Don't rely on geometric search plus a one-step lazy scroll:
                                            // the next row may exist only as a clipped sliver.
                                            nextSearchResultIndex(i, shown.size)?.let(::focusResultAt)
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            ) {
                                ResultCard(
                                    nav, shown[i], s, cardReg,
                                    externalFocus = if (i == 0) firstResultFocus else null,
                                    onResultFocusChanged = { focused ->
                                        if (focused) {
                                            focusedResultId = shown[i].id
                                            if (BuildConfig.DEBUG) {
                                                android.util.Log.d(
                                                    "SearchFocusFixture",
                                                    "focused index=$i id=${shown[i].id} count=${shown.size}",
                                                )
                                            }
                                        }
                                        else if (focusedResultId == shown[i].id) focusedResultId = null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 竖版结果卡（与片库同款竖版海报；片名在卡下，右下角 remark 角标同片库样式）
 *  需求②：角标字号加大黑底压扁（同 AllScreen.VcardCard） */
@Composable
private fun ResultCard(
    nav: NavController,
    item: VodItem,
    s: Float,
    cardReg: FocusRegistry? = null,
    externalFocus: FocusRequester? = null,
    onResultFocusChanged: (Boolean) -> Unit = {},
) {
    Column(Modifier.width(232f.sx(s))) {
        Box(
            Modifier
                .dpadFocusable(
                    scaleFocused = CARD_FOCUS_SCALE,
                    allowFocusScaleOnConstrained = true,
                    onFocusedChange = {
                        onResultFocusChanged(it)
                        if (it) ReturnFocus.mark("search", item.id)
                    },
                    externalFocusRequester = externalFocus ?: cardReg?.fr(item.id),
                ) {
                    nav.navigate("detail/" + URLEncoder.encode(item.id, "UTF-8"))
                }
                .fillMaxWidth()
                .aspectRatio(232f / 352f)
                .clip(RoundedCornerShape(12f.sx(s))),
        ) {
            RemotePoster(
                url = item.posterUrl,
                title = item.title,
                seed = item.id.hashCode(),
                modifier = Modifier.fillMaxSize(),
            )
            if (item.remark.isNotBlank()) {
                Text(
                    item.remark,
                    color = OtvColors.White, fontSize = 20f.sxs(s), lineHeight = 20f.sxs(s), maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6f.sx(s))
                        .background(Color(0xCC0E0E0F), RoundedCornerShape(4f.sx(s)))
                        .padding(horizontal = 7f.sx(s), vertical = 1f.sx(s)),
                )
            }
        }
        Spacer(Modifier.height(10f.sx(s)))
        Text(
            item.title, color = OtvColors.White, fontSize = 24f.sxs(s), fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

