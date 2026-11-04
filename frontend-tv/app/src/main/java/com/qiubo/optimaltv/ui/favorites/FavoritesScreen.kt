package com.qiubo.optimaltv.ui.favorites

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.qiubo.optimaltv.Graph
import com.qiubo.optimaltv.auth.DongpianAuthSession
import com.qiubo.optimaltv.data.model.VodItem
import com.qiubo.optimaltv.data.source.OfficialLibrarySyncPhase
import com.qiubo.optimaltv.data.source.OfficialLibrarySyncState
import com.qiubo.optimaltv.dictation.DictationSetupActivity
import com.qiubo.optimaltv.ui.components.tvFocusRegion
import com.qiubo.optimaltv.ui.components.TvRowFocusMemory
import com.qiubo.optimaltv.ui.components.FocusRegistry
import com.qiubo.optimaltv.ui.components.CARD_FOCUS_SCALE
import com.qiubo.optimaltv.ui.components.InitialFocusEffect
import com.qiubo.optimaltv.ui.components.MainTabBar
import com.qiubo.optimaltv.ui.components.OtvHint
import com.qiubo.optimaltv.ui.components.ProvideNavScrolls
import com.qiubo.optimaltv.ui.components.RemotePoster
import com.qiubo.optimaltv.ui.components.ReturnFocus
import com.qiubo.optimaltv.ui.components.dpadFocusable
import com.qiubo.optimaltv.ui.components.formatWatchHistoryLabel
import com.qiubo.optimaltv.ui.components.lazyNavContainer
import com.qiubo.optimaltv.ui.components.navigateToTab
import com.qiubo.optimaltv.ui.components.tvReducedSemantics
import com.qiubo.optimaltv.ui.theme.AccentBlue
import com.qiubo.optimaltv.ui.theme.OtvColors
import com.qiubo.optimaltv.ui.theme.rememberUiScale
import com.qiubo.optimaltv.ui.theme.sx
import com.qiubo.optimaltv.ui.theme.sxs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder

internal fun libraryContentPending(
    phase: OfficialLibrarySyncPhase,
    favoriteCount: Int,
    historyCount: Int,
    localFavoriteCount: Int,
    localHistoryCount: Int,
): Boolean =
    phase == OfficialLibrarySyncPhase.READY &&
        ((favoriteCount > 0 && localFavoriteCount == 0) ||
            (historyCount > 0 && localHistoryCount == 0))

/**
 * 我的页（2026-08-28 需求重排）：
 * 收藏行 + 「历史记录」标题 + 观看记录行；卡片样式与影视页一致（232 竖版海报卡），
 * 文字缩小一行展示；设置入口移除（需求 我的#1/#2/#3/#4）；
 * 内容整体随页面滚动（LazyColumn + 悬浮 TabBar，需求 我的#5）；
 * 内容区块少时整体上下居中（需求 影视#6）；
 * tab 栏 DOWN 定向到内容区首张卡（需求 我的#1）。
 */
@Composable
fun FavoritesScreen(
    nav: NavController,
    session: DongpianAuthSession,
    onLogout: () -> Unit,
) {
    val favoritesFlow = remember { Graph.db.vodDao().favoritesFlow() }
    val historyFlow = remember { Graph.db.vodDao().historyFlow(100) }
    val favorites by favoritesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val history by historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val catalogState by Graph.repo.state.collectAsStateWithLifecycle()
    val syncState by Graph.officialLibrary.syncState.collectAsStateWithLifecycle()
    val s = rememberUiScale()
    val context = LocalContext.current
    // 返回落焦（需求 影视#6-2）
    val returnKey = remember { ReturnFocus.take("fav") }
    val tabFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    val cardReg = remember { FocusRegistry() }
    InitialFocusEffect(tabFocus, "fav-tab", enabled = returnKey == null)
    var restoredFocus by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    // Keep the account refresh alive if navigation leaves this screen while
                    // the Room replacement is still running.
                    Graph.appScope.launch {
                        runCatching { Graph.officialLibrary.syncToRoom(Graph.db) }
                            .onFailure {
                                com.qiubo.optimaltv.OtvLog.w("官方片库同步失败: ${it.message}")
                            }
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(catalogState.catalog, history, favorites) {
        val key = returnKey ?: return@LaunchedEffect
        if (restoredFocus) return@LaunchedEffect
        if (catalogState.catalog == null && history.isEmpty()) return@LaunchedEffect
        repeat(40) {
            if (runCatching { cardReg.fr(key).requestFocus() }.isSuccess) {
                restoredFocus = true
                return@LaunchedEffect
            }
            delay(100)
        }
        runCatching { tabFocus.requestFocus() }
        restoredFocus = true
    }

    // 需求 #2：任意处按返回 → 回顶部；已在顶部且焦点在 tab 栏 → 双击才彻底退出（需求⑥）
    val favListState = rememberLazyListState()
    val backScope = androidx.compose.runtime.rememberCoroutineScope()
    var tabFocused by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) { if (hint != null) { kotlinx.coroutines.delay(2500); hint = null } }
    val exitApp = com.qiubo.optimaltv.ui.components.rememberDoubleBackExit { hint = it }
    BackHandler {
        val atTop = favListState.firstVisibleItemIndex == 0 && favListState.firstVisibleItemScrollOffset == 0
        if (atTop && tabFocused) {
            exitApp()
        } else {
            backScope.launch { favListState.scrollToItem(0) }
            runCatching { tabFocus.requestFocus() }
        }
    }

    val cat = catalogState.catalog
    // v1.19 流畅度：remember + 收藏 id 集合——旧版每次重组对全目录(数千条)×收藏做
    // 嵌套 any 扫描（全字符串比较，主线程），收藏页任何状态变化都重算
    val favItems = remember(cat, favorites) {
        val catalogById = cat?.dedupedItems.orEmpty().associateBy(VodItem::id)
        favorites.distinctBy { it.vodId }.map { favorite ->
            catalogById[favorite.vodId]
                ?: VodItem(
                    id = favorite.vodId,
                    sourceId = "dongpian",
                    title = favorite.title,
                    categoryId = "douban:movie",
                    posterUrl = favorite.posterUrl,
                    detailRef = favorite.vodId,
                )
        }
    }
    val librarySyncing =
        ((favorites.isEmpty() && history.isEmpty()) &&
            (syncState.phase == OfficialLibrarySyncPhase.IDLE ||
                syncState.phase == OfficialLibrarySyncPhase.SYNCING)) ||
            libraryContentPending(
                phase = syncState.phase,
                favoriteCount = syncState.favoriteCount,
                historyCount = syncState.historyCount,
                localFavoriteCount = favorites.size,
                localHistoryCount = history.size,
            )
    Box(Modifier.fillMaxSize().background(OtvColors.Bg).tvReducedSemantics()) {
        // 全页单 LazyColumn：所有内容随页滚动（TabBar 悬浮顶层，需求 我的#5）
        // v1.13：方向导航统一 OtvNav 引擎，容器仅声明滚动挂靠
        val favScroll = remember(favListState) { lazyNavContainer(favListState) }
        ProvideNavScrolls(vertical = favScroll) {
            LazyColumn(
                state = favListState,
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80f.sx(s)),
            ) {
                item(key = "account") {
                    AccountSyncRow(
                        session = session,
                        syncState = syncState,
                        s = s,
                        onLogout = onLogout,
                        onOpenDictation = {
                            context.startActivity(
                                Intent(context, DictationSetupActivity::class.java),
                            )
                        },
                        logoutFocusRequester = if (favItems.isEmpty() && history.isEmpty()) {
                            contentFocus
                        } else {
                            null
                        },
                    )
                }
                // 需求⑥（2026-09-11）：会员信息并入「我的收藏」标题行靠右——旧版悬浮
                // 右上与标题不同行；光标只能选中行尾「续费」两字（整行不可聚焦）
                favHisItems(
                    s = s,
                    favItems = favItems,
                    history = history,
                    contentFocus = contentFocus,
                    cardReg = cardReg,
                    nav = nav,
                    titleTop = 34f,
                    librarySyncing = librarySyncing,
                    restoreKey = returnKey,
                )
            }
        } // ProvideNavScrolls(favScroll)
        MainTabBar(
            "me", { key -> navigateToTab(nav, key) }, { nav.navigate("search") }, tabFocus,
            contentDownFocus = contentFocus,
            contentDownScrollTop = { favListState.scrollToItem(0) },
            onTabFocused = { tabFocused = it },
            autoHide = true,   // v1.16：光标下移进内容区隐藏顶栏，回顶部显示
        )
        OtvHint(hint)
    }
}

@Composable
private fun AccountSyncRow(
    session: DongpianAuthSession,
    syncState: OfficialLibrarySyncState,
    s: Float,
    onLogout: () -> Unit,
    onOpenDictation: () -> Unit,
    logoutFocusRequester: FocusRequester?,
) {
    var logoutFocused by remember { mutableStateOf(false) }
    var dictationFocused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 90f.sx(s), end = 86f.sx(s), top = 94f.sx(s)),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                session.displayName.ifBlank { "懂片帝用户" },
                color = OtvColors.White,
                fontSize = 28f.sxs(s),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (syncState.phase == OfficialLibrarySyncPhase.FAILED) {
                Spacer(Modifier.height(7f.sx(s)))
                Text(
                    "同步失败，点此重试",
                    color = Color(0xFFFF9F0A),
                    fontSize = 18f.sxs(s),
                    textDecoration = TextDecoration.Underline,
                    modifier =
                        Modifier.dpadFocusable(onClick = {
                            Graph.appScope.launch {
                                try {
                                    Graph.officialLibrary.syncToRoom(Graph.db, force = true)
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    com.qiubo.optimaltv.OtvLog.w("后台片库重试失败")
                                }
                            }
                        }),
                )
            }
        }
        Spacer(Modifier.width(28f.sx(s)))
        Text(
            "语音输入",
            color = if (dictationFocused) AccentBlue else OtvColors.White50,
            fontSize = 20f.sxs(s),
            fontWeight = FontWeight.Medium,
            textDecoration = if (dictationFocused) TextDecoration.Underline else TextDecoration.None,
            modifier =
                Modifier
                    .dpadFocusable(
                        scaleFocused = 1.04f,
                        focusedBg = null,
                        drawRing = false,
                        onFocusedChange = { dictationFocused = it },
                    ) { onOpenDictation() }
                    .padding(horizontal = 10f.sx(s), vertical = 12f.sx(s)),
        )
        Spacer(Modifier.width(28f.sx(s)))
        Text(
            "退出登录",
            color = if (logoutFocused) AccentBlue else OtvColors.White50,
            fontSize = 20f.sxs(s),
            fontWeight = FontWeight.Medium,
            textDecoration = if (logoutFocused) TextDecoration.Underline else TextDecoration.None,
            modifier =
                Modifier
                    .dpadFocusable(
                        scaleFocused = 1.04f,
                        focusedBg = null,
                        drawRing = false,
                        externalFocusRequester = logoutFocusRequester,
                        onFocusedChange = { logoutFocused = it },
                    ) { onLogout() }
                    .padding(horizontal = 10f.sx(s), vertical = 12f.sx(s)),
        )
    }
}


/** 影视页同款竖版卡（232 宽 / poster 232×352 r12 / 标题 21 单行）——需求 我的#1/#2；
 *  需求 影视#3：选中环紧贴卡片边缘。需求 影视#11：remark 徽章高度降为 2/3。 */@Composable
private fun VodStyleCard(
    title: String,
    posterUrl: String,
    remark: String,
    seed: Int,
    s: Float,
    focusRequester: FocusRequester? = null,
    cardReg: FocusRegistry? = null,
    cardKey: String? = null,
    onCardFocused: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(Modifier.width(232f.sx(s))) {
        Box(
            Modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .dpadFocusable(
                    scaleFocused = CARD_FOCUS_SCALE,
                    onFocusedChange = {
                        if (it && cardKey != null) {
                            ReturnFocus.mark("fav", cardKey)
                            onCardFocused?.invoke()
                        }
                    },
                    externalFocusRequester = cardKey?.let { cardReg?.fr(it) },
                    onClick = onClick,
                )
                .fillMaxWidth()
                .aspectRatio(232f / 352f)
                .clip(RoundedCornerShape(12f.sx(s))),
        ) {
            RemotePoster(
                url = posterUrl,
                title = title,
                seed = seed,
                modifier = Modifier.fillMaxSize(),
            )
            if (remark.isNotBlank()) {
                Text(
                    remark,
                    // 需求②：字号加大（12→16）+ 行高压到与字号同高——黑底长方形更扁
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
        // 需求 我的#2：文字缩小、一行展示
        Text(
            title, color = OtvColors.White, fontSize = 21f.sxs(s), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 历史记录卡 = 影视页同款竖版卡 + 底部进度条。
 * 需求 我的#1：「上次看到 第N集 进度」文字放右下角、带半透明深色背景；
 * 下方进度条显示观看进度（进度数据由 PlayerViewModel 实时回写）。
 * 点卡直接进播放页（对齐需求 影视#10）。
 */
@Composable
private fun HistoryVodCard(
    h: com.qiubo.optimaltv.data.db.HistoryEntity,
    title: String,
    s: Float,
    focusRequester: FocusRequester? = null,
    cardReg: FocusRegistry? = null,
    onCardFocused: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(Modifier.width(232f.sx(s))) {
        Box(
            Modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .dpadFocusable(
                    scaleFocused = CARD_FOCUS_SCALE,
                    onFocusedChange = {
                        if (it) {
                            ReturnFocus.mark("fav", "history:${h.vodId}")
                            onCardFocused?.invoke()
                        }
                    },
                    externalFocusRequester = cardReg?.fr("history:${h.vodId}"),
                    onClick = onClick,
                )
                .fillMaxWidth()
                .aspectRatio(232f / 352f)
                .clip(RoundedCornerShape(12f.sx(s))),
        ) {
            RemotePoster(
                url = h.posterUrl,
                title = title,
                seed = h.vodId.hashCode(),
                modifier = Modifier.fillMaxSize(),
            )
            // 需求 我的#1 + 2026-09-18 用户要求：与影视页「最近观看」卡片右下角角标完全一致
            //（底色 0x99666668 / 圆角 5 / 17px / 贴边 6dp，对齐 HomeScreen 同款角标）
            Text(
                formatWatchHistoryLabel(h.epIndex + 1, h.positionMs),
                color = OtvColors.White, fontSize = 17f.sxs(s), lineHeight = 20f.sxs(s),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6f.sx(s))
                    .background(Color(0x99666668), RoundedCornerShape(5f.sx(s)))
                    .padding(horizontal = 8f.sx(s), vertical = 3f.sx(s)),
            )
            // hprogress：底部 4px 进度条（观看进度）
            val frac = if (h.durationMs > 0) (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f) else 0f
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4f.sx(s))
                    .background(OtvColors.White.copy(alpha = 0.18f)),
            ) {
                if (frac > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(frac)
                            .fillMaxHeight()
                            .background(OtvColors.White.copy(alpha = 0.92f)),
                    )
                }
            }
        }
        Spacer(Modifier.height(10f.sx(s)))
        Text(
            title, color = OtvColors.White, fontSize = 21f.sxs(s), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 需求⑥（2026-09-11）：我的页会员信息——并入「我的收藏」标题行靠右的一行小字
 * （状态点+状态/套餐/到期/剩余），无背景框；**仅行尾动作可聚焦**
 * （旧版整行 dpadFocusable，光标落在整行上；现光标只套行尾动作），
 * OK 进会员页（激活/续费/兑换）。
 */
@Composable
private fun MemberInlineInfo(
    s: Float,
    renewFocus: FocusRequester? = null,
    onRedeem: () -> Unit,
) {
    val st by com.qiubo.optimaltv.license.LicenseManager.state.collectAsStateWithLifecycle()
    val d = (st as? com.qiubo.optimaltv.license.LicenseState.Activated)?.data
    val activateAction = st is com.qiubo.optimaltv.license.LicenseState.NotActivated
    val status: String
    val statusColor: Color
    val infoText: String
    when {
        st == null -> { status = "加载中…"; statusColor = OtvColors.White50; infoText = "" }
        activateAction -> { status = "尚未开通"; statusColor = OtvColors.White50; infoText = "输入卡密可激活" }
        d == null -> { status = "会员状态暂不可用"; statusColor = OtvColors.White50; infoText = "" }
        d.expiryAt != null && com.qiubo.optimaltv.license.LicenseManager.effNow() >= d.expiryAt -> {
            status = "会员已过期"; statusColor = Color(0xFFFF453A)
            infoText = com.qiubo.optimaltv.ui.paywall.planDisplayName(d.plan) + " · 续费可用"
        }
        !com.qiubo.optimaltv.license.LicenseManager.isPremium() -> {
            status = "等待联网校验"; statusColor = Color(0xFFFF9F0A); infoText = "联网后自动恢复"
        }
        d.expiryAt == null -> { status = "终身会员"; statusColor = Color(0xFF30D158); infoText = "终身 · 永久有效" }
        else -> {
            status = "会员有效"; statusColor = Color(0xFF30D158)
            infoText = com.qiubo.optimaltv.ui.paywall.planDisplayName(d.plan) +
                " · 到期 " + fmtDate(d.expiryAt) +
                " · 剩余 ${com.qiubo.optimaltv.license.LicenseManager.daysLeft()} 天"
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 86f.sx(s)),
    ) {
        Box(
            Modifier
                .size(14f.sx(s))
                .background(statusColor, androidx.compose.foundation.shape.CircleShape),
        )
        Spacer(Modifier.width(12f.sx(s)))
        Text(
            buildString {
                append(status)
                if (infoText.isNotBlank()) append(" · ").append(infoText)
            },
            color = OtvColors.White50,
            fontSize = 22f.sxs(s),
            maxLines = 1,
        )
        // 行尾带下划线的会员操作入口；未开通时可直接进入激活页。
        // 需求⑥（2026-09-11）：只有这两个字可被光标选中
        // 2026-09-19 老板定版（第二轮）：选中效果**改为细白描边框**，去掉之前那套
        // 「白底反色胶囊 + 放大 1.08 + 四周柔和外发光」——
        // 老板原话「换成一个白边的框就好了，细边的框就好了，不用搞那么夸张浮夸」。
        // 现在是：聚焦 = 1.5 设计px 白色圆角描边框 + 文字转白；未聚焦 = 半透明 hairline + 蓝字下划线。
        // 之前那版光晕（6 圈同心描边模拟衰减）整块删除，连同它的 24 设计px 画布边距常量。
        Spacer(Modifier.width(14f.sx(s)))
        var renewFocused by remember { mutableStateOf(false) }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .then(if (renewFocus != null) Modifier.focusRequester(renewFocus) else Modifier)
                .dpadFocusable(
                    scaleFocused = 1f,     // 不做放大：老板要「不夸张」，聚焦只换描边
                    focusedBg = null,      // 背景自绘（未聚焦态是半透明胶囊）
                    drawRing = false,      // 焦点环也自绘，统一到同一个 border 上
                    onFocusedChange = { renewFocused = it },
                ) { onRedeem() }
                .clip(RoundedCornerShape(50))
                .background(
                    if (renewFocused) OtvColors.White.copy(alpha = 0.06f)   // 极淡填充，只用于垫住白字
                    else Color(0x1FFFFFFF),
                )
                .border(
                    if (renewFocused) 1.5f.sx(s) else 1f.sx(s),
                    if (renewFocused) OtvColors.White else Color(0x33FFFFFF),
                    RoundedCornerShape(50),
                )
                .padding(horizontal = 20f.sx(s), vertical = 9f.sx(s)),
        ) {
            Text(
                if (activateAction) "激活" else "续费",
                color = if (renewFocused) OtvColors.White else AccentBlue,
                fontSize = 22f.sxs(s),
                fontWeight = FontWeight.SemiBold,
                // 聚焦态去掉下划线：白字 + 白框下再压一条下划线会脏
                textDecoration = if (renewFocused) TextDecoration.None else TextDecoration.Underline,
            )
        }
    }
}

/** 收藏+历史区块（我的页两种布局共用）：标题行 + 横滑卡行。titleTop=标题顶部留白（调用方按布局给）；
 *  memberRow 非空时与「我的收藏」标题同行右对齐（需求⑥：会员信息行并入标题行） */
private fun androidx.compose.foundation.lazy.LazyListScope.favHisItems(
    s: Float,
    favItems: List<VodItem>,
    history: List<com.qiubo.optimaltv.data.db.HistoryEntity>,
    contentFocus: FocusRequester,
    cardReg: FocusRegistry,
    nav: androidx.navigation.NavController,
    titleTop: Float,
    librarySyncing: Boolean,
    restoreKey: String? = null,
    memberRow: (@Composable () -> Unit)? = null,
) {
    val hasFav = favItems.isNotEmpty()
    val hasHis = history.isNotEmpty()
    item(key = "fav-title") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(start = 90f.sx(s), end = 0f.sx(s), top = titleTop.sx(s)),
        ) {
            Text(
                "我的收藏", fontSize = 30f.sxs(s), fontWeight = FontWeight.SemiBold,
                color = OtvColors.White.copy(alpha = 0.92f),
            )
            Spacer(Modifier.weight(1f))
            memberRow?.invoke()
        }
    }
    item(key = "fav-row") {
        if (favItems.isEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 90f.sx(s), top = 20f.sx(s)),
            ) {
                if (librarySyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22f.sx(s)),
                        color = OtvColors.White50,
                        strokeWidth = 2f.sx(s),
                    )
                    Spacer(Modifier.width(12f.sx(s)))
                }
                Text(
                    if (librarySyncing) "正在同步收藏…" else "暂无收藏",
                    color = OtvColors.White50,
                    fontSize = 26f.sxs(s),
                )
            }
        } else {
            val favRowState = rememberLazyListState()
            val favFocusMemory = remember {
                TvRowFocusMemory().apply {
                    restoreKey?.takeIf { it.startsWith("favorite:") }?.let(::focused)
                }
            }
            val favRowScroll = remember(favRowState) { lazyNavContainer(favRowState) }
            ProvideNavScrolls(horizontal = favRowScroll) {
                LazyRow(
                    state = favRowState,
                    horizontalArrangement = Arrangement.spacedBy(24f.sx(s)),
                    contentPadding = PaddingValues(horizontal = 86f.sx(s)),
                    modifier = Modifier
                        .padding(top = 22f.sx(s))
                        .tvFocusRegion {
                            favFocusMemory.entryKey(
                                favItems.map { "favorite:${it.id}" },
                                favRowState.layoutInfo.visibleItemsInfo.map { "favorite:${it.key}" }.toSet(),
                            )?.let(cardReg::fr)
                        },
                ) {
                    items(favItems.size, key = { i -> favItems[i].id }) { i ->
                        VodStyleCard(
                            title = favItems[i].title,
                            posterUrl = favItems[i].posterUrl,
                            remark = favItems[i].remark,
                            seed = favItems[i].id.hashCode(),
                            s = s,
                            focusRequester = if (i == 0) contentFocus else null,
                            cardReg = cardReg,
                            cardKey = "favorite:${favItems[i].id}",
                            onCardFocused = { favFocusMemory.focused("favorite:${favItems[i].id}") },
                        ) {
                            nav.navigate("detail/" + URLEncoder.encode(favItems[i].id, "UTF-8"))
                        }
                    }
                }
            }
        }
    }
    // 需求 我的#4：观看记录前加「历史记录」标题
    if (hasHis) {
        item(key = "his-title") {
            Text(
                "历史记录", fontSize = 30f.sxs(s), fontWeight = FontWeight.SemiBold,
                color = OtvColors.White.copy(alpha = 0.92f),
                modifier = Modifier.padding(start = 90f.sx(s), top = 44f.sx(s)),
            )
        }
        item(key = "his-row") {
            val hisRowState = rememberLazyListState()
            val hisFocusMemory = remember {
                TvRowFocusMemory().apply {
                    restoreKey?.takeIf { it.startsWith("history:") }?.let(::focused)
                }
            }
            val hisRowScroll = remember(hisRowState) { lazyNavContainer(hisRowState) }
            ProvideNavScrolls(horizontal = hisRowScroll) {
                LazyRow(
                    state = hisRowState,
                    horizontalArrangement = Arrangement.spacedBy(24f.sx(s)),
                    contentPadding = PaddingValues(horizontal = 86f.sx(s)),
                    modifier = Modifier
                        .padding(top = 22f.sx(s))
                        .tvFocusRegion {
                            hisFocusMemory.entryKey(
                                history.map { "history:${it.vodId}" },
                                hisRowState.layoutInfo.visibleItemsInfo.map { "history:${it.key}" }.toSet(),
                            )?.let(cardReg::fr)
                        },
                ) {
                    items(history.size, key = { i -> history[i].vodId }) { i ->
                        val h = history[i]
                        // 旧版本空片名历史自愈：懒解析详情回填标题
                        var healedTitle by remember(h.vodId) { mutableStateOf(h.title) }
                        LaunchedEffect(h.vodId) {
                            if (healedTitle.isBlank()) Graph.repo.resolveDetail(h.vodId)?.let {
                                if (it.title.isNotBlank()) healedTitle = it.title
                            }
                        }
                        HistoryVodCard(
                            h = h,
                            title = healedTitle,
                            s = s,
                            // 需求 我的#1：无收藏时 DOWN 兜底落历史首卡
                            focusRequester = if (!hasFav && i == 0) contentFocus else null,
                            cardReg = cardReg,
                            onCardFocused = { hisFocusMemory.focused("history:${h.vodId}") },
                        ) {
                            val resumeMs =
                                com.qiubo.optimaltv.ui.player.PlaybackResumePolicy.startPositionMs(
                                    0L,
                                    h.positionMs,
                                    h.durationMs,
                                )
                            nav.navigate(
                                "player/" + URLEncoder.encode(h.vodId, "UTF-8") +
                                    "/${h.epIndex}?resumeMs=$resumeMs",
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun fmtDate(ms: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(ms))
