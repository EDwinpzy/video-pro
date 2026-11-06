package com.qiubo.optimaltv

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import com.qiubo.optimaltv.ui.components.TvInteractionMotion
import com.qiubo.optimaltv.ui.components.HOME_CONTENT_TAB_KEYS
import com.qiubo.optimaltv.ui.components.HOME_ROUTE
import com.qiubo.optimaltv.ui.components.HomeTabState
import com.qiubo.optimaltv.ui.detail.DetailScreen
import com.qiubo.optimaltv.ui.favorites.FavoritesScreen
import com.qiubo.optimaltv.ui.home.VodScreen
import com.qiubo.optimaltv.ui.live.LiveScreen
import com.qiubo.optimaltv.ui.player.PlayerScreen
import com.qiubo.optimaltv.ui.search.SearchScreen
import com.qiubo.optimaltv.ui.theme.OptimalTvTheme
import com.qiubo.optimaltv.auth.DongpianLoginGate
import com.qiubo.optimaltv.ui.components.VideoProWidescreenStartup

/** 调试直达播放页通道（v1.8 2026-08-30）：debug 构建 DebugNavReceiver 广播写入 pending，
 *  AppRoot 轮询消费——绕过模拟器丢键/焦点漂移，测试直播链路用。
 *  adb shell am broadcast -a com.qiubo.optimaltv.DEBUG_NAV --es vodId "live:45653551" */
object DebugNavBus {
    @Volatile var pendingVodId: String? = null
    @Volatile var pendingDetailId: String? = null
    @Volatile var pendingEp: Int = 0
    @Volatile var pendingResumeMs: Long = 0L
    /** 直播引擎强制（仅测试注入：am start --es otvEngine vlc） */
    @Volatile var pendingEngine: String? = null
    /** 启动落点覆盖（仅 debug：am start --es otvDebugRoute paywall|activate|favorites）；release 无写入方 */
    @Volatile var pendingRoute: String? = null
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private object TvShortBringIntoViewSpec : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
        TvInteractionMotion.focusRevealScrollSpec
}

/** 单 Activity（方案 §3.1 UI 层）：Compose NavHost 路由（Tab 对齐原版：足球/影视/我的/搜索） */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartupReadiness.bind(this)
        com.qiubo.optimaltv.OtvLog.i("activity onCreate")
        consumeDebugNav(intent)
        syncViewportMetrics()
        // TV 沉浸式：设计稿按整屏 1920×1080 布局，状态栏不隐藏会把底部内容挤出屏
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        setContent {
            val defaultBringIntoViewSpec =
                androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
            val bringIntoViewSpec =
                if (TvPerformanceProfile.isConstrained) {
                    TvShortBringIntoViewSpec
                } else {
                    defaultBringIntoViewSpec
                }
            CompositionLocalProvider(
                androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides bringIntoViewSpec,
            ) {
                OptimalTvTheme {
                    StartupPresentation()
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        syncViewportMetrics()
    }

    /** 窗口尺寸注入导航引擎（ROTATION 感知；Resources.getSystem 在横屏下会给自然方向尺寸） */
    private fun syncViewportMetrics() {
        runCatching {
            com.qiubo.optimaltv.ui.components.OtvNav.viewportHeightPx =
                windowManager.currentWindowMetrics.bounds.height().toFloat()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeDebugNav(intent)
    }

    /** 调试直达播放页：am start --es otvDebugVodId "live:<id>"（Android 15 实测
     *  shell 广播不投递 manifest receiver，改走 activity intent extra；仅测试注入用）。
     *  otvDebugQuery：注入搜索页查询词（模拟器注键丢键，搜索页测试用） */
    private fun consumeDebugNav(intent: android.content.Intent?) {
        intent?.getStringExtra("otvDebugVodId")?.takeIf { it.isNotBlank() }?.let {
            DebugNavBus.pendingVodId = it
            DebugNavBus.pendingEp = intent.getIntExtra("ep", 0)
            DebugNavBus.pendingResumeMs = intent.getLongExtra("resumeMs", 0L)
            DebugNavBus.pendingEngine = intent.getStringExtra("otvEngine")?.takeIf { e -> e.isNotBlank() }
            com.qiubo.optimaltv.OtvLog.i("debug-nav（intent extra）vodId=$it engine=${DebugNavBus.pendingEngine ?: "默认"}")
        }
        intent?.getStringExtra("otvDebugDetailId")?.takeIf { it.isNotBlank() }?.let {
            DebugNavBus.pendingDetailId = it
        }
        intent?.getStringExtra("otvDebugQuery")?.takeIf { it.isNotBlank() }?.let {
            com.qiubo.optimaltv.ui.search.SearchDebugBus.pendingQuery = it
            com.qiubo.optimaltv.OtvLog.i("debug-nav（intent extra）query=$it")
        }
        // 调试直达二级页（仅 debug；release 无写入方）：
        // adb shell am start -n <pkg>/com.qiubo.optimaltv.MainActivity --es otvDebugRoute favorites
        if (com.qiubo.optimaltv.BuildConfig.DEBUG) {
            intent?.getStringExtra("otvDebugRoute")?.takeIf { it.isNotBlank() }?.let {
                DebugNavBus.pendingRoute = it
                com.qiubo.optimaltv.OtvLog.i("debug-nav（intent extra）route=$it")
            }
            if (intent?.hasExtra("otvDebugResultCount") == true) {
                val count = intent.getIntExtra("otvDebugResultCount", -1)
                if (count in 0..60) {
                    com.qiubo.optimaltv.ui.search.SearchDebugBus.pendingFixtureCount = count
                    if (!intent.hasExtra("otvDebugQuery")) {
                        com.qiubo.optimaltv.ui.search.SearchDebugBus.pendingQuery = ""
                    }
                    com.qiubo.optimaltv.OtvLog.i("debug-nav search-focus-fixture count=$count")
                }
            }
        }
        // v1.17 调试注入激活码（模拟器键盘注键丢键，测试用；码本身仍走完整验证链路：
        // 云端查表+设备绑定+本地 ECDSA 验签，无任何安全豁免；公测版 debug 构建限定）
        if (BuildConfig.DEBUG) {
            intent?.getStringExtra("otvDebugLicense")?.takeIf { it.isNotBlank() }?.let { code ->
                com.qiubo.optimaltv.OtvLog.i("debug-nav（intent extra）license=${code.take(4)}***")
                Graph.appScope.launch {
                    val r = com.qiubo.optimaltv.license.LicenseManager.activate(code)
                    com.qiubo.optimaltv.OtvLog.i("debug license activate → $r")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 冷启动时 Android 可能因后台启动限制拒绝一次 startService；前台恢复后重试，
        // 避免内嵌后端未监听 8090 导致足球列表/直播一起不可用。
        BackendService.ensure(this)
        com.qiubo.optimaltv.lifecycle.AppLifecycleManager.onForeground()
        com.qiubo.optimaltv.OtvLog.i("activity ON_RESUME（窗口可见）")
        // v1.20 热更自动重启：后台完成下载的更新在回前台时立即生效
        com.qiubo.optimaltv.hotupdate.HotUpdateManager.onForegroundChanged(true)
        com.qiubo.optimaltv.announcement.AnnouncementManager.onForegroundChanged(true)
    }

    override fun onPause() {
        com.qiubo.optimaltv.lifecycle.AppLifecycleManager.onBackground()
        super.onPause()
        com.qiubo.optimaltv.OtvLog.i("activity ON_PAUSE")
        com.qiubo.optimaltv.announcement.AnnouncementManager.onForegroundChanged(false)
        com.qiubo.optimaltv.hotupdate.HotUpdateManager.onForegroundChanged(false)
    }
    override fun onStop() {
        super.onStop()
        com.qiubo.optimaltv.OtvLog.w("activity ON_STOP（黑屏若发生在此后，见播放器/焦点日志上下文）")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        com.qiubo.optimaltv.OtvLog.i("windowFocusChanged hasFocus=$hasFocus")
    }
}

private object StartupPlaybackState {
    @Volatile
    var introCompletedThisProcess: Boolean = false
}

@Composable
private fun StartupPresentation() {
    var introComplete by remember {
        mutableStateOf(StartupPlaybackState.introCompletedThisProcess)
    }
    var surfaceReady by remember { mutableStateOf(false) }
    val curtainVisible = !introComplete || !surfaceReady
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                curtainVisible &&
                    event.key !in listOf(Key.VolumeDown, Key.VolumeUp)
            }
            .pointerInput(curtainVisible) {
                if (!curtainVisible) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (curtainVisible) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    },
                ),
        ) {
            DongpianLoginGate(
                interactive = !curtainVisible,
                onStartupSurfaceReady = { surfaceReady = it },
            ) { session, onLogout ->
                HomeStartupGate(
                    allowContent = introComplete,
                    startupOverlayVisible = curtainVisible,
                    onStartupSurfaceReady = { surfaceReady = it },
                ) {
                    AppRoot(session, onLogout)
                }
            }
        }
        if (curtainVisible) {
            VideoProWidescreenStartup(
                animate = !introComplete,
                onFinished = {
                    StartupPlaybackState.introCompletedThisProcess = true
                    introComplete = true
                },
            )
        }
    }
}

@Composable
private fun AppRoot(
    session: com.qiubo.optimaltv.auth.DongpianAuthSession,
    onLogout: () -> Unit,
) {
    val nav = rememberNavController()
    val navigationMotion = TvPerformanceProfile.navigationMotionEnabled

    // 调试直达播放页：debug 广播 → pending 消费（详见 DebugNavBus；release 无写入方）
    LaunchedEffect(Unit) {
        while (true) {
            com.qiubo.optimaltv.voice.VoiceSearchBus.takeNavigationQuery()?.let {
                nav.navigate("search") { launchSingleTop = true }
            }
            DebugNavBus.pendingDetailId?.let { id ->
                DebugNavBus.pendingDetailId = null
                nav.navigate("detail/" + java.net.URLEncoder.encode(id, "UTF-8"))
            }
            DebugNavBus.pendingVodId?.let { id ->
                DebugNavBus.pendingVodId = null
                val ep = DebugNavBus.pendingEp
                val resumeMs = DebugNavBus.pendingResumeMs
                com.qiubo.optimaltv.OtvLog.i("debug-nav → player vodId=$id ep=$ep resume=$resumeMs")
                runCatching {
                    // v1.13 防护：player→player 直切（连续直达不同频道）会让旧 VM 的引擎
                    // 释放与新 destination 组合互锁（实测组合冻结、route 日志照打）——
                    // 先退回非 player 页再进新播放页，与真实遥控路径（经列表页中转）一致
                    if (nav.currentDestination?.route?.startsWith("player/") == true) {
                        nav.popBackStack()
                    }
                    nav.navigate(
                        "player/" + java.net.URLEncoder.encode(id, "UTF-8") +
                            "/" + ep + "?resumeMs=" + resumeMs.coerceAtLeast(0L),
                    )
                }
            }
            kotlinx.coroutines.delay(200)
        }
    }

    // 需求 足球#3：路由变化落日志（黑屏发生在哪一页）
    androidx.compose.runtime.DisposableEffect(nav) {
        val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, dest, _ ->
            com.qiubo.optimaltv.OtvLog.i("route → ${dest.route}")
        }
        nav.addOnDestinationChangedListener(listener)
        onDispose { nav.removeOnDestinationChangedListener(listener) }
    }

    // 目录刷新入口：冷启动拉取全部源；返回首页不重复刷
    LaunchedEffect(Unit) {
        Graph.repo.refresh()
    }

    // 调试直达页（2026-09-19）：debug 构建下可用 adb 直接落在指定二级页，
    // 便于对会员页/激活页/我的页做版式截图与回归（release 无写入方，恒为 null）。
    // adb shell am start -n <pkg>/com.qiubo.optimaltv.MainActivity --es otvDebugRoute favorites
    val debugRoute = if (com.qiubo.optimaltv.BuildConfig.DEBUG) {
        when (val requested = DebugNavBus.pendingRoute) {
            "favorites" -> "me"
            "movie", "series", "anime", "short", "variety", "search", "me" -> requested
            else -> null
        }
    } else null
    val debugHomeTab = debugRoute?.takeIf { it in HOME_CONTENT_TAB_KEYS }
    val startDestination = when {
        debugHomeTab != null -> HOME_ROUTE
        debugRoute != null -> debugRoute
        else -> HOME_ROUTE
    }
    androidx.compose.runtime.LaunchedEffect(debugHomeTab) {
        debugHomeTab?.let(HomeTabState::select)
    }

    // v1.20 全局浮层：运营公告（顶部半透明悬浮窗）+ 非强更提案弹窗 + 下载进度层
    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = startDestination,
            // A full-screen crossfade requires two composed TV pages and is costly
            // on projectors. Keep it on regular hardware and switch instantly on
            // constrained devices so focus moves on the first frame.
            enterTransition = {
                if (navigationMotion) {
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200))
                } else {
                    androidx.compose.animation.EnterTransition.None
                }
            },
            exitTransition = {
                if (navigationMotion) {
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(100))
                } else {
                    androidx.compose.animation.ExitTransition.None
                }
            },
            popEnterTransition = {
                if (navigationMotion) {
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200))
                } else {
                    androidx.compose.animation.EnterTransition.None
                }
            },
            popExitTransition = {
                if (navigationMotion) {
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(100))
                } else {
                    androidx.compose.animation.ExitTransition.None
                }
            },
        ) {
            composable("live") { LiveScreen(nav) }

            composable(HOME_ROUTE) { VodScreen(nav) }

        // 电视直播页（v1.13：公开 IPTV 源原生直连，紧随影视之后）
        composable("tv") { com.qiubo.optimaltv.ui.tv.TvScreen(nav) }


        composable("me") { FavoritesScreen(nav, session, onLogout) }

        composable(
            route = "detail/{vodId}",
            arguments = listOf(navArgument("vodId") { type = NavType.StringType }),
        ) { entry ->
            DetailScreen(nav, java.net.URLDecoder.decode(entry.arguments?.getString("vodId").orEmpty(), "UTF-8"))
        }

        composable(
            // v1.19 跨线路续播：resumeMs 可选参数——详情页已扫全部线路取到最近观看位置，
            // 直接带给播放器（旧版播放器只查当前线路 key，换过线的影片续播从 0 重播）
            route = "player/{vodId}/{ep}?resumeMs={resumeMs}",
            arguments = listOf(
                navArgument("vodId") { type = NavType.StringType },
                navArgument("ep") { type = NavType.IntType; defaultValue = 0 },
                navArgument("resumeMs") { type = NavType.LongType; defaultValue = 0L },
            ),
            // v1.21 光标乱跑根因修复（2026-09-06 黑盒复现）：player 路由退出 crossfade
            // 转场会卡在未完成态——popBackStack 后海报墙已可见，但 PlayerScreen 组合
            // 以 alpha≈0 残留：视频后台续播、全屏 rootFocus sink 仍持焦 → 四向全 blocked
            // 光标死锁，OK 还会戳到隐形播放器控制层。TV 播放器改 None 转场（进出/pop
            // 均瞬时）——pop 即刻销毁组合，navSink 注销、dpadFocusable 触发补焦回列表。
            enterTransition = { androidx.compose.animation.EnterTransition.None },
            exitTransition = { androidx.compose.animation.ExitTransition.None },
            popEnterTransition = { androidx.compose.animation.EnterTransition.None },
            popExitTransition = { androidx.compose.animation.ExitTransition.None },
        ) { entry ->
            PlayerScreen(
                nav,
                java.net.URLDecoder.decode(entry.arguments?.getString("vodId").orEmpty(), "UTF-8"),
                entry.arguments?.getInt("ep") ?: 0,
                entry.arguments?.getLong("resumeMs") ?: 0L,
            )
        }

        composable("search") { SearchScreen(nav) }

        }
        // 全局浮层（最顶层，非模态——公告悬浮窗不拦内容导航）
        // 后端热更已改为后台静默自动更新，不再有热更弹窗/进度层
        com.qiubo.optimaltv.ui.components.AnnouncementBanner()
    }
}
