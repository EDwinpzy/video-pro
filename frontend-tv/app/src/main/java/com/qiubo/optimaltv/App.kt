package com.qiubo.optimaltv

import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Scale
import com.qiubo.optimaltv.data.db.AppDb
import com.qiubo.optimaltv.data.model.isUsablePosterUrl
import com.qiubo.optimaltv.data.net.SharedHttpClient
import com.qiubo.optimaltv.data.prefs.SettingsStore
import com.qiubo.optimaltv.data.repo.VodRepository
import com.qiubo.optimaltv.data.repo.heroRefs
import com.qiubo.optimaltv.data.source.DongpianOfficialLibraryClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.roundToInt

@androidx.compose.runtime.Immutable
internal data class TvImageSize(val widthPx: Int, val heightPx: Int)

/**
 * Shared request geometry for the visible TV artwork and its warm-up.
 *
 * The UI lays out from the 1920px design coordinate system. Hero art only occupies the
 * 500px visible band, while poster cards decode at their responsive display size.
 */
internal object TvImageRequestSpec {
    private const val DESIGN_WIDTH_PX = 1920f
    private const val HERO_HEIGHT_DESIGN_PX = 500f
    private const val POSTER_WIDTH_DESIGN_PX = 232f
    private const val POSTER_HEIGHT_DESIGN_PX = 352f
    fun heroSize(displayWidthPx: Int): TvImageSize {
        val width = displayWidthPx.coerceAtLeast(1)
        val height =
            (HERO_HEIGHT_DESIGN_PX * width / DESIGN_WIDTH_PX)
                .roundToInt()
                .coerceAtLeast(1)
        return TvImageSize(width, height)
    }

    fun posterSize(
        displayWidthPx: Int,
        decodeScale: Float = TvPerformanceProfile.posterDecodeScale,
    ): TvImageSize {
        val responsiveScale = displayWidthPx.coerceAtLeast(1) / DESIGN_WIDTH_PX
        val widthPx =
            (POSTER_WIDTH_DESIGN_PX * responsiveScale * decodeScale)
                .roundToInt()
                .coerceAtLeast(1)
        val heightPx =
            (POSTER_HEIGHT_DESIGN_PX * responsiveScale * decodeScale)
                .roundToInt()
                .coerceAtLeast(1)
        return TvImageSize(
            widthPx = widthPx,
            heightPx = heightPx,
        )
    }

    fun heroRequest(context: Context, url: String, size: TvImageSize): ImageRequest.Builder =
        applyHero(ImageRequest.Builder(context).data(url), size)

    fun applyHero(builder: ImageRequest.Builder, size: TvImageSize): ImageRequest.Builder =
        builder
            .size(size.widthPx, size.heightPx)
            .scale(Scale.FILL)
            // The request is capped to the visible 500px hero band, not the
            // original ultra-wide source. Poster hardware bitmaps are already
            // verified on S621, so reuse the same GPU path for the hero unless
            // a device has a broken WebP decoder.
            .allowHardware(TvPerformanceProfile.isConstrained)

    fun posterRequest(context: Context, url: String, size: TvImageSize): ImageRequest.Builder =
        applyPoster(ImageRequest.Builder(context).data(url), size)

    fun applyPoster(builder: ImageRequest.Builder, size: TvImageSize): ImageRequest.Builder =
        builder
            .size(size.widthPx, size.heightPx)
            .scale(Scale.FILL)
            // Card textures dominate the draw pass on the low-tier projector. Upload each
            // poster once and let the GPU reuse it while focus moves across rows.
            .allowHardware(true)
}

internal data class TvImagePreload(val url: String, val hero: Boolean)

/**
 * Keeps the first visible hero and poster row ahead of background category art.
 * A duplicate stays at its highest-priority position and is promoted to hero when needed.
 */
internal fun orderedTvImagePreloads(
    heroUrls: List<String>,
    cardUrls: List<String>,
    limit: Int,
): List<TvImagePreload> {
    if (limit <= 0) return emptyList()
    val ordered = linkedMapOf<String, TvImagePreload>()
    val usableHeroes =
        heroUrls
            .map(String::trim)
            .filter(::isUsablePosterUrl)

    fun add(url: String, hero: Boolean) {
        val normalized = url.trim()
        if (!isUsablePosterUrl(normalized)) return
        val existing = ordered[normalized]
        if (existing == null) {
            ordered[normalized] = TvImagePreload(normalized, hero)
        } else if (hero && !existing.hero) {
            ordered[normalized] = existing.copy(hero = true)
        }
    }

    usableHeroes.firstOrNull()?.let { add(it, hero = true) }
    cardUrls.forEach { add(it, hero = false) }
    usableHeroes.drop(1).forEach { add(it, hero = true) }
    return ordered.values.take(limit)
}

/** 轻量服务定位器：单进程单实例（方案 §5 单 Activity 单进程） */
object Graph {
    lateinit var appContext: Context
        private set
    lateinit var db: AppDb
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var repo: VodRepository
        private set
    lateinit var officialLibrary: DongpianOfficialLibraryClient
        private set

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        db = AppDb.get(appContext)
        settings = SettingsStore(appContext)
        repo = VodRepository(settings, SharedHttpClient.default(), appContext)
        officialLibrary =
            DongpianOfficialLibraryClient(appContext) {
                repo.clearAccountScopedCaches()
                settings.clearAccountScopedState()
            }
    }

    /** 应用级协程作用域（v1.17：偏好写入等跨页面生命周期任务，页面退出不丢写） */
    val appScope: kotlinx.coroutines.CoroutineScope by lazy {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    }

    /** 图片双缓存（需求 影视#1：海报墙加载提速）——
     *  内存 20% + 磁盘 256MB：海报墙反复滚动/回页全部命中本地。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun imageLoader(): ImageLoader = ImageLoader.Builder(appContext)
        .okHttpClient {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                // Keep mobile-class TVs at one small wave. Thirty-two concurrent
                // decodes starve the UI thread on a projector; faster hardware can
                // still fill an entire poster row in one pass.
                .dispatcher(okhttp3.Dispatcher().apply {
                    if (TvPerformanceProfile.isConstrained) {
                        maxRequests = 24
                        maxRequestsPerHost = 10
                    } else {
                        maxRequests = 128
                        maxRequestsPerHost = 32
                    }
                })
                .build()
        }
        .decoderDispatcher(
            if (TvPerformanceProfile.isConstrained) {
                Dispatchers.IO.limitedParallelism(2)
            } else {
                Dispatchers.IO
            },
        )
        .memoryCache {
            MemoryCache.Builder(appContext)
                .maxSizePercent(if (TvPerformanceProfile.isConstrained) 0.25 else 0.20)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(appContext.cacheDir.resolve("image_cache"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        // v1.10（2026-08-31 低端机优化）：硬件位图默认放开（海报渲染走 GPU 纹理，
        // 滚动/切页不再逐帧 CPU 拷贝）——仅 hero 超宽大图（3360×1080，MuMu 硬件位图
        // 解码失败=图空白，v1.4 实测）按请求 allowHardware(false)，见两处 HeroBand。
        // Many simultaneous image fades keep the projector compositor busy for
        // much longer than the actual decode. Focus rings still provide feedback;
        // image appearance should be immediate on constrained GPUs.
        .crossfade(!TvPerformanceProfile.isConstrained)
        .build()
}

/**
 * 启动门闸（2026-09-19 用户拍板「方案 A+B」：首屏快优先）：
 *
 * - 门闸只等目录快照可读；没有快照时最多等 [FAST_GATE_MS]，不因网络慢而卡住登录页。
 * - home、hero 简介/海报、影视三栏、全部封面和详情/片源预热都在后台补；
 *   预热结果仍落 detailCache/detailInfoCache，用户浏览几秒后点开大概率已命中。
 * - First paint waits only for a readable catalogue snapshot. Remote home, copy,
 *   artwork and source warming continue without covering the UI.
 */
object AppStartup {
    private val _ready = kotlinx.coroutines.flow.MutableStateFlow(false)
    val ready: kotlinx.coroutines.flow.StateFlow<Boolean> = _ready.asStateFlow()

    private val _status = kotlinx.coroutines.flow.MutableStateFlow("正在准备…")

    /**
     * 启动屏文案（2026-09-17 用户需求：全量预热期间人会盯着启动屏看，需要知道不是在卡死）。
     * 只作提示，不参与门控判定。
     */
    val status: kotlinx.coroutines.flow.StateFlow<String> = _status.asStateFlow()

    /** 目录快照预算：本地资源秒出，5s 是弱机/首装硬上限 */
    private const val FAST_GATE_MS = 5_000L

    /**
     * Show the app shell as soon as local screen data is usable. Home copy, hero
     * art and source warming continue updating in the background behind the
     * already-composed screen; waiting for all of them made cold start 26-35s
     * on the projector.
     */
    private val FIRST_PAINT_BUDGET_MS = TvPerformanceProfile.startupFirstPaintBudgetMs

    /** hero 海报等待预算：门闸要「首屏有图才放行」，但弱网不能让启动屏无限等。 */
    private const val COVER_WAIT_MS = 20_000L

    /** 后台片源预热的 hero 条数：与 hero 简介预载同批（首屏最大、最可能被点到的候选） */
    private const val SOURCE_HERO_MAX = 6

    /** 后台片源预热的额外候选条数（首屏卡片行：最近观看 / 电影三栏首条） */
    private const val SOURCE_EXTRA_MAX = 6

    /** 后台片源预热并发；额外候选再压低一档避免与用户的点击抢上游 */
    private const val SOURCE_CONCURRENCY = 3
    private const val SOURCE_EXTRA_CONCURRENCY = 2
    private val coverPreloadGate =
        Semaphore(TvPerformanceProfile.coverPreloadConcurrency)

    fun begin() {
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val t0 = System.currentTimeMillis()
        _status.value = "正在准备…"
        // 后台全量预热（方案 A：不挡门闸）：目录刷新 / 影视三栏+全量封面 /
        // 首屏片源匹配。各自失败记日志，进页后按需兜底。
        scope.launch {
            launch {
                runCatching { Graph.repo.refresh() }
                    .onFailure { OtvLog.w("后台预热：目录刷新失败（后台重试）: ${it.message}") }
            }
            launch {
                delay(TvPerformanceProfile.backgroundWarmupDelayMs)
                runCatching { preloadFirstScreen() }
                    .onFailure { OtvLog.w("首屏补全失败（进页后按需拉取）: ${it.message}") }
            }
            launch {
                if (TvPerformanceProfile.isConstrained) StartupReadiness.awaitHomeFocus()
                delay(TvPerformanceProfile.backgroundWarmupDelayMs)
                runCatching { preloadDeep() }
                    .onFailure { OtvLog.w("后台深预热失败（进页后按需拉取）: ${it.message}") }
            }
        }
        scope.launch {
            try {
                val settled = kotlinx.coroutines.withTimeoutOrNull(FIRST_PAINT_BUDGET_MS) {
                    _status.value = "正在连接服务器…"
                    // 目录：磁盘快照恢复即就绪；首装无快照不死等网络。
                    val cat = launch {
                        kotlinx.coroutines.withTimeoutOrNull(FAST_GATE_MS - 500) {
                            Graph.repo.awaitCatalogAvailable()
                        }
                    }
                    _status.value = "正在准备首屏…"
                    // Do not block first paint on remote detail/copy/image requests.
                    cat.join()
                    _status.value = "即将进入…"
                    true
                }
                if (settled == null) {
                    OtvLog.w("startup first-paint budget（${FIRST_PAINT_BUDGET_MS}ms）到点 → 先放行，剩余转后台")
                }
            } catch (e: Exception) {
                OtvLog.e("startup gate 异常（兜底放行）: ${e.message}")
            } finally {
                OtvLog.i("startup gate 放行 ${System.currentTimeMillis() - t0}ms" +
                    "（catalog=${Graph.repo.state.value.catalog != null}）")
                _ready.value = true
            }
        }
        // v1.19 硬保险：主线程 Handler 看门狗——门闸协程级超时（withTimeout）依赖
        // IO 线程池可调度；若任何同步阻塞把 IO 池占满（实测：Semaphore.acquire 死锁
        // 曾让兜底失效、启动屏永久冻结），协程超时永远恢复不了。本看门狗跑在
        // 主线程 Looper 上，不受影响：任何情况下启动屏最多「预算上限 + 2s」必放行。
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!_ready.value) {
                OtvLog.e("startup gate 看门狗触发：${FIRST_PAINT_BUDGET_MS + 2_000}ms 仍未放行 → 强制放行（协程链疑似饿死）")
                _ready.value = true
            }
        }, FIRST_PAINT_BUDGET_MS + 2_000)
    }

    /**
     * 首屏预载（方案 B 分档：门闸只等目录快照）：home 首抓 → hero 简介并行预取 →
     * hero 海报真实加载。三栏/全量封面/片源匹配在 [preloadDeep] 后台跑。
     */
    private suspend fun preloadFirstScreen() {
        val t0 = System.currentTimeMillis()
        Graph.repo.refreshHome()
        Graph.repo.awaitHome()
        val heroIds = heroIds()
        // hero 简介（豆瓣 defer 快路径）按 UI 同一套 hero 口径（heroRefs）预取前 6 条，
        // 结果落 VodRepository.briefCache，进页同步命中直接出简介。
        kotlinx.coroutines.coroutineScope {
            heroIds.map { id -> launch { runCatching { Graph.repo.vodBrief(id) } } }.joinAll()
        }
        val home = Graph.repo.homeFlow.value
        val heroUrls =
            heroRefs(Graph.repo.state.value.catalog, home)
                .map { it.bg }
                .filter(::isUsablePosterUrl)
        val visibleHistoryUrls = visibleHistoryPosterUrls()
        kotlinx.coroutines.coroutineScope {
            launch {
                val queued = preloadCovers(heroUrls, visibleHistoryUrls)
                OtvLog.i("首屏封面预热窗口：$queued 张（当前 hero 与可见历史优先）")
            }
            heroIds.map { id -> launch { runCatching { Graph.repo.vodBrief(id) } } }.joinAll()
        }
        OtvLog.i("首屏补全完成：hero ${heroIds.size} 条简介（${System.currentTimeMillis() - t0}ms）")
    }

    private suspend fun visibleHistoryPosterUrls(limit: Int = 8): List<String> =
        try {
            Graph.db.vodDao().historyFlow(limit).first()
                .map { it.posterUrl }
                .filter(::isUsablePosterUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }

    /** 门闸与后台片源预热共用同一批 hero id（与 UI 同一套 heroRefs 口径） */
    private fun heroIds(): List<String> = runCatching {
        heroRefs(Graph.repo.state.value.catalog, Graph.repo.homeFlow.value).map { it.id }.distinct()
    }.getOrDefault(emptyList()).take(SOURCE_HERO_MAX)

    /**
     * 后台深预热（方案 A：不挡门闸）：全部分类三栏 + 全量封面 + 首屏片源匹配。
     * 片源预热结果落 detailCache/detailInfoCache：用户浏览几秒后点开详情页大概率
     * 已命中「立即播放」，未命中也有「正在查找片源」等待态兜底。
     */
    private suspend fun preloadDeep() {
        val t0 = System.currentTimeMillis()
        Graph.repo.awaitCatalogAvailable()
        val cats = Graph.repo.state.value.catalog?.categories ?: return
        cats.forEach { Graph.repo.libBlocks(it.id) }       // 全部分类三块并行拉取（幂等）
        val heroIds = heroIds()
        kotlinx.coroutines.coroutineScope {
            val blocksReady = cats.map { async { Graph.repo.awaitLibBlocksContent(it.id) } }.awaitAll()
            if (blocksReady.any { !it }) {
                OtvLog.w("后台深预热：${blocksReady.count { !it }}/${blocksReady.size} 个分类三栏仍为空（后台阶梯补抓）")
            }
        }
        val blocks = Graph.repo.libBlocksFlow.value
        val home = Graph.repo.homeFlow.value
        // URL 收集优先用刷新完成后的最新目录（refresh 与预载并行，通常此时已完成）
        val catsForUrls = Graph.repo.state.value.catalog?.categories ?: cats
        val heroUrls =
            runCatching {
                heroRefs(Graph.repo.state.value.catalog, home).map { it.bg }
            }.getOrDefault(emptyList())
        val cardUrls = mutableListOf<String>()
        // History is visible above the category rows and must not wait behind every
        // background category snapshot.
        cardUrls += visibleHistoryPosterUrls()
        home.sections.forEach { sec -> sec.items.forEach { if (it.posterUrl.isNotBlank()) cardUrls += it.posterUrl } }
        Graph.repo.state.value.catalog?.dedupedItems?.filter { it.posterUrl.isNotBlank() }?.take(6)
            ?.forEach { cardUrls += it.posterUrl }
        // 每个分类 tab 页（含「全部」兜底网格）：三块卡片封面；无三块的分类取目录前 18 张
        (catsForUrls.map { it.id } + "").forEach { cid ->
            val secs = com.qiubo.optimaltv.data.repo.VodRepository.categoryCid(cid)?.let { blocks[it] }.orEmpty()
            if (secs.isNotEmpty()) {
                secs.forEach { sec -> sec.items.forEach { if (it.posterUrl.isNotBlank()) cardUrls += it.posterUrl } }
            } else {
                Graph.repo.byCategory(cid).take(18).forEach {
                    if (it.posterUrl.isNotBlank()) cardUrls += it.posterUrl
                }
            }
        }
        // 封面预载与首屏片源预热并行：前者打图床、后者打影视源，互不抢上游
        kotlinx.coroutines.coroutineScope {
            val srcJob = launch { preloadFirstScreenSources(heroIds) }
            val n = preloadCovers(heroUrls, cardUrls)
            OtvLog.i("后台深预热完成：${catsForUrls.size} 分类 / $n 张封面（${System.currentTimeMillis() - t0}ms）")
            srcJob.join()
        }
    }

    /**
     * 首屏片源预热（方案 A 后全程后台，不再进启动门闸）：
     * hero 前 [SOURCE_HERO_MAX] 条 + 首屏卡片行候选（最近观看 / 电影三栏首条），
     * 多源搜索 + 线路探测结果落 VodRepository 的 detailCache/detailInfoCache。
     */
    private suspend fun preloadFirstScreenSources(heroIds: List<String>) {
        if (heroIds.isEmpty()) return
        val t0 = System.currentTimeMillis()
        runCatching { Graph.repo.preloadDetailSources(heroIds, batch = SOURCE_CONCURRENCY) }
            .onSuccess { OtvLog.i("首屏片源预热（后台）：hero $it/${heroIds.size} 条线路就绪（${System.currentTimeMillis() - t0}ms）") }
            .onFailure { OtvLog.w("首屏片源预热失败（点开时按需拉取）: ${it.message}") }
        // 首屏卡片行：最近观看 + 电影三栏每栏首条
        val extras = runCatching { firstScreenExtraIds() }.getOrDefault(emptyList())
            .filterNot { it in heroIds }.take(SOURCE_EXTRA_MAX)
        if (extras.isNotEmpty()) {
            val n = runCatching { Graph.repo.preloadDetailSources(extras, batch = SOURCE_EXTRA_CONCURRENCY) }.getOrDefault(0)
            OtvLog.i("首屏片源预热（后台）：卡片行 $n/${extras.size} 条线路就绪")
        }
    }

    /** 放行后继续预热的「首屏卡片行」候选：最近观看前 3 + 电影三栏每栏首条（去重后取前几条） */
    private suspend fun firstScreenExtraIds(): List<String> {
        val ids = mutableListOf<String>()
        runCatching {
            Graph.db.vodDao().historyFlow(3).first()
                .forEach { if (it.vodId.isNotBlank()) ids += it.vodId }
        }
        runCatching {
            Graph.repo.libBlocksFlow.value[1].orEmpty().forEach { sec ->
                sec.items.firstOrNull()?.id?.takeIf { it.isNotBlank() }?.let { ids += it }
            }
        }
        return ids.distinct()
    }

    /**
     * Coil 封面预热：走 App 的单例 ImageLoader（带内存+磁盘缓存），返回预载发起张数。
     * v1.19 流畅度：卡片按响应式显示尺寸的 2x 解码，避免旧版默认按整屏解码造成约 4 倍
     * 像素量。hero 与页面使用同一 1920 设计宽、500 设计高的请求规格，而不是整屏高度；
     * 两条路径统一 Scale.FILL，并保留投影仪兼容的软件位图策略。
     *
     * 预热列表受 [TvPerformanceProfile.coverPreloadLimit] 限制，并通过共享信号量分波执行。
     * 单纯依赖 OkHttp 限流无法约束 Coil 磁盘缓存初始化、软件解码和位图分配，
     * 受限投影仪会因此出现数百毫秒的锁等待和一次 20 MB 以上的 NativeAlloc GC。
     */
    private suspend fun preloadCovers(heroUrls: List<String>, cardUrls: List<String>): Int {
        val cappedImages =
            orderedTvImagePreloads(
                heroUrls = heroUrls,
                cardUrls = cardUrls,
                limit = TvPerformanceProfile.coverPreloadLimit,
            )
        if (cappedImages.isEmpty()) return 0

        val loader = coil.Coil.imageLoader(Graph.appContext)
        val display = Graph.appContext.resources.displayMetrics
        val heroSize = TvImageRequestSpec.heroSize(display.widthPixels)
        val posterSize = TvImageRequestSpec.posterSize(display.widthPixels)
        kotlinx.coroutines.coroutineScope {
            val jobs =
                cappedImages.map { image ->
                    launch {
                        val req =
                            if (image.hero) {
                                TvImageRequestSpec.heroRequest(Graph.appContext, image.url, heroSize).build()
                            } else {
                                TvImageRequestSpec.posterRequest(Graph.appContext, image.url, posterSize).build()
                            }
                        try {
                            coverPreloadGate.withPermit {
                                loader.execute(req)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // A failed artwork request must not prevent later requests in the wave.
                        }
                    }
                }
            val finished =
                kotlinx.coroutines.withTimeoutOrNull(COVER_WAIT_MS) {
                    jobs.joinAll()
                    true
                }
            if (finished == null) {
                val remaining = jobs.count { it.isActive }
                jobs.forEach { it.cancel() }
                OtvLog.i("封面预载到窗口上限（${COVER_WAIT_MS}ms）：取消 $remaining 张未完成预热")
            }
        }
        return cappedImages.size
    }
}

class App : android.app.Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        OtvLog.init(this)
        OtvLog.i("app onCreate — process start")
        com.qiubo.optimaltv.lifecycle.AppLifecycleManager.init(this)
        Graph.init(this)
        com.qiubo.optimaltv.ui.components.OtvNav.debugLogging =
            BuildConfig.DEBUG && !TvPerformanceProfile.isConstrained
        // Account-scoped HomeStartupGate loads the current five home feeds before entry.
        // Do not start the legacy warmup: it targets removed tabs and has a timed bypass.
    }

    override fun newImageLoader(): ImageLoader = Graph.imageLoader()
}
