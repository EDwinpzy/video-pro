package com.qiubo.optimaltv.data.repo

import com.qiubo.optimaltv.auth.DongpianAuthClient
import com.qiubo.optimaltv.auth.DongpianAuthSession
import com.qiubo.optimaltv.data.model.Category
import com.qiubo.optimaltv.data.model.ChannelSection
import com.qiubo.optimaltv.data.model.CarouselItem
import com.qiubo.optimaltv.data.model.DetailInfo
import com.qiubo.optimaltv.data.model.Episode
import com.qiubo.optimaltv.data.model.LineDetail
import com.qiubo.optimaltv.data.model.LineInfo
import com.qiubo.optimaltv.data.model.OfficialSkipPreset
import com.qiubo.optimaltv.data.model.ResolvedPlaybackUrl
import com.qiubo.optimaltv.data.model.ShowPage
import com.qiubo.optimaltv.data.model.VodItem
import com.qiubo.optimaltv.data.prefs.SettingsStore
import com.qiubo.optimaltv.data.source.DongpianOfficialVodSource
import com.qiubo.optimaltv.data.source.SourceSnapshot
import com.qiubo.optimaltv.data.source.SourceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

@androidx.compose.runtime.Immutable
data class Aggregated(
    val categories: List<Category>,
    val dedupedItems: List<VodItem>,
    val groups: Map<String, List<VodItem>>,
)

data class CatalogState(
    val loading: Boolean = false,
    val catalog: Aggregated? = null,
    val sourceStates: List<SourceState> = emptyList(),
)

/** A hero item selected from the official home snapshot. */
data class HeroRef(
    val id: String,
    val title: String,
    val bg: String,
    val tag: String,
)

fun heroRefs(catalog: Aggregated?, home: VodRepository.HomeState): List<HeroRef> {
    val fromCarousel =
        home.carousel.map {
            HeroRef(it.vid, it.title, it.backdrop, "")
        }
    if (fromCarousel.isNotEmpty()) return fromCarousel
    val fromHome =
        home.sections
            .flatMap { section ->
                section.items.map {
                    HeroRef(it.id, it.title, it.heroUrl.ifBlank { it.posterUrl }, section.name)
                }
            }
            .distinctBy(HeroRef::id)
    if (fromHome.isNotEmpty()) return fromHome.take(8)
    return catalog?.dedupedItems
        ?.filter { it.posterUrl.isNotBlank() }
        ?.take(6)
        ?.map { HeroRef(it.id, it.title, it.heroUrl.ifBlank { it.posterUrl }, "") }
        .orEmpty()
}

data class DoubanInfo(
    val found: Boolean,
    val rating: String,
    val desc: String,
    val poster: String,
)

internal object CatalogOriginPolicy {
    const val SNAPSHOT_VERSION = 4
    val PERSISTED_ACCEPTED_VERSIONS = setOf(SNAPSHOT_VERSION)

    fun allows(sourceId: String, itemId: String): Boolean =
        sourceId == "dongpian" && itemId.startsWith("dongpian:")
}

internal object CatalogSnapshotPolicy {
    const val MAX_BYTES = 8L * 1024 * 1024
    const val MAX_ITEMS = 120

    fun isReadableSize(size: Long): Boolean = size in 1..MAX_BYTES
    fun <T> boundedItems(items: List<T>): List<T> = items.take(MAX_ITEMS)
}

internal data class CatalogSnapshot(
    val categories: List<Category>,
    val items: List<VodItem>,
)

internal fun decodeSnapshot(text: String, acceptedVersions: Set<Int>): CatalogSnapshot? {
    val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
    if (root.optInt("v") !in acceptedVersions) return null
    val catArr = root.optJSONArray("categories") ?: return null
    val itemArr = root.optJSONArray("items") ?: return null
    val cats = (0 until catArr.length()).mapNotNull { index ->
        val obj = catArr.optJSONObject(index) ?: return@mapNotNull null
        Category(obj.optString("id"), obj.optString("name"))
            .takeIf { it.id.isNotBlank() }
    }
    val items = (0 until itemArr.length()).mapNotNull { index ->
        val obj = itemArr.optJSONObject(index) ?: return@mapNotNull null
        VodItem(
            id = obj.optString("id"),
            sourceId = obj.optString("s"),
            title = obj.optString("t"),
            categoryId = obj.optString("c"),
            year = obj.optString("y"),
            area = obj.optString("ar"),
            rating = obj.optDouble("r", 0.0),
            posterUrl = obj.optString("p"),
            heroUrl = obj.optString("h"),
            remark = obj.optString("rm"),
            detailRef = obj.optString("d"),
            mytvId = obj.optString("mytv_id").ifBlank { obj.optString("m") },
            searchTerms =
                (0 until (obj.optJSONArray("q")?.length() ?: 0)).mapNotNull {
                    obj.optJSONArray("q")?.optString(it)?.takeIf(String::isNotBlank)
                },
        ).takeIf { it.id.isNotBlank() && it.title.isNotBlank() }
    }
    if (cats.isEmpty() || items.isEmpty()) return null
    if (items.any { !CatalogOriginPolicy.allows(it.sourceId, it.id) }) return null
    return CatalogSnapshot(cats, items)
}

internal class ContentSessionSnapshot internal constructor(
    val userId: String,
    private val cookie: String,
) {
    override fun equals(other: Any?): Boolean =
        other is ContentSessionSnapshot &&
            userId == other.userId &&
            cookie == other.cookie

    override fun hashCode(): Int = 31 * userId.hashCode() + cookie.hashCode()
}

internal fun contentSessionSnapshot(session: DongpianAuthSession?): ContentSessionSnapshot? =
    session?.let { ContentSessionSnapshot(it.userId, it.cookie) }

internal object SourceResolveCascade {
    enum class Tier(val wire: String) {
        LOCAL_DETAIL("local_detail"),
        NONE("none"),
    }
}

internal object PlayableLineIdentity {
    fun id(line: LineInfo): String =
        line.officialStableLineId.takeIf(String::isNotBlank)
            ?: line.playRef.split('|').getOrNull(1)?.takeIf(String::isNotBlank)
            ?: line.vodId
}

/**
 * Official Dongpiandi catalogue and playback repository.
 *
 * The previous source adapter had local, demo, football-live and IPTV fallbacks.
 * Those paths are intentionally not reachable here: every catalogue entry is a
 * `dongpian:` item and every playback request is resolved by the official
 * account-bound API. The caches and bounded request paths are kept because they
 * are part of the TV performance work.
 */
class VodRepository(
    private val settings: SettingsStore,
    private val okHttp: OkHttpClient,
    private val appContext: android.content.Context,
) {
    private val official = DongpianOfficialVodSource(appContext)

    private val _state = MutableStateFlow(CatalogState(loading = true))
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    private val _home = MutableStateFlow(HomeState())
    val homeFlow: StateFlow<HomeState> = _home.asStateFlow()

    private val _sections = MutableStateFlow<Map<Int, List<ChannelSection>>>(emptyMap())
    val sectionsFlow: StateFlow<Map<Int, List<ChannelSection>>> = _sections.asStateFlow()

    private val _homeRecommendations =
        MutableStateFlow<Map<Int, List<ChannelSection>>>(emptyMap())
    val homeRecommendationsFlow: StateFlow<Map<Int, List<ChannelSection>>> =
        _homeRecommendations.asStateFlow()

    private val _libBlocks = MutableStateFlow<Map<Int, List<ChannelSection>>>(emptyMap())
    val libBlocksFlow: StateFlow<Map<Int, List<ChannelSection>>> = _libBlocks.asStateFlow()

    private val _hotSettled = MutableStateFlow<Set<Int>>(emptySet())
    val hotSettledFlow: StateFlow<Set<Int>> = _hotSettled.asStateFlow()

    private data class HomeRecommendationsCacheKey(
        val category: String,
        val session: ContentSessionSnapshot?,
    )

    private val completeHomeRecommendations =
        java.util.concurrent.ConcurrentHashMap<HomeRecommendationsCacheKey, List<ChannelSection>>()

    private val sectionFetchedAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val sectionRequestLock = Any()
    private data class ChannelRequestKey(val cid: Int, val session: ContentSessionSnapshot?)
    private val sectionRequests = mutableMapOf<ChannelRequestKey, Deferred<Unit>>()

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Any()
    private var refreshJob: Deferred<Unit>? = null
    private var refreshJobForce = false
    private var catalogRefreshJob: kotlinx.coroutines.Job? = null
    private var catalogRetryJob: kotlinx.coroutines.Job? = null
    private var catalogRetry = 0
    private var homeJob: kotlinx.coroutines.Job? = null

    private val libJobs =
        java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.Job>()
    private val libRetryJobs =
        java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.Job>()
    private val libDone = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val libFailAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val libRetries = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    private val snapshotFile: java.io.File
        get() = java.io.File(appContext.filesDir, "catalog_snapshot.json")

    private val detailCache = java.util.concurrent.ConcurrentHashMap<String, VodItem>()
    private val detailCacheFreshAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val detailInfoCache =
        java.util.concurrent.ConcurrentHashMap<String, DetailInfo>()
    private val officialDetailCache =
        java.util.concurrent.ConcurrentHashMap<String, DongpianOfficialVodSource.Detail>()
    private val detailRequests = SharedRequests<String, DetailInfo?>(repoScope)
    private val detailRequestGenerations =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val briefCache =
        java.util.concurrent.ConcurrentHashMap<String, Triple<String, String, Double>>()

    init {
        repoScope.launch {
            runCatching { restoreSnapshot() }
                .onFailure { android.util.Log.w("OTV", "目录快照恢复失败: ${it.message}") }
        }
    }

    data class HomeState(
        val loading: Boolean = false,
        val sections: List<ChannelSection> = emptyList(),
        val carousel: List<CarouselItem> = emptyList(),
    )

    suspend fun awaitCatalogAvailable() {
        while (_state.value.catalog == null) delay(150)
    }

    suspend fun refresh(force: Boolean = false) {
        val sharedRefresh =
            synchronized(refreshLock) {
                val current = refreshJob
                if (current != null && !current.isCompleted) {
                    if (!force || refreshJobForce) {
                        current
                    } else {
                        repoScope.async(start = CoroutineStart.LAZY) {
                            runCatching { current.await() }
                            refreshOnce(force = true)
                        }.also {
                            refreshJob = it
                            refreshJobForce = true
                        }
                    }
                } else {
                    repoScope.async(start = CoroutineStart.LAZY) { refreshOnce(force) }
                        .also {
                            refreshJob = it
                            refreshJobForce = force
                        }
                }
            }
        sharedRefresh.start()
        try {
            sharedRefresh.await()
        } finally {
            if (sharedRefresh.isCompleted) {
                synchronized(refreshLock) {
                    if (refreshJob === sharedRefresh) {
                        refreshJob = null
                        refreshJobForce = false
                    }
                }
            }
        }
    }

    private suspend fun refreshOnce(force: Boolean) {
        _state.value = _state.value.copy(loading = true)
        try {
            if (!official.isSignedIn()) {
                _state.value = CatalogState(loading = false)
                return
            }
            val snapshot =
                try {
                    official.snapshot()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    _state.value =
                        _state.value.copy(
                            loading = false,
                            sourceStates =
                                listOf(
                                    SourceState(
                                        "dongpian",
                                        "懂片帝官方",
                                        false,
                                        failure.message ?: "加载失败",
                                    ),
                                ),
                        )
                    scheduleCatalogRetry()
                    return
                }
            val grouped = snapshot.items.groupBy { it.normTitle }
            _state.value =
                CatalogState(
                    loading = false,
                    catalog =
                        Aggregated(
                            categories = snapshot.categories,
                            dedupedItems = grouped.values.map { it.first() },
                            groups = grouped,
                        ),
                    sourceStates =
                        listOf(
                            SourceState(
                                snapshot.sourceId,
                                snapshot.sourceName,
                                true,
                                "${snapshot.items.size} 部",
                            ),
                        ),
                )
            catalogRetry = 0
            writeSnapshot(snapshot.categories, snapshot.items)
        } catch (failure: Exception) {
            _state.value = _state.value.copy(loading = false)
            throw failure
        }
    }

    private fun scheduleCatalogRetry() {
        if (catalogRetry >= 4) return
        if (catalogRetryJob?.isActive == true) return
        val wait = longArrayOf(5_000L, 10_000L, 20_000L, 30_000L)[catalogRetry]
        catalogRetry++
        catalogRetryJob =
            repoScope.launch {
                delay(wait)
                runCatching { refresh() }
                    .onFailure { android.util.Log.w("OTV", "目录重试失败: ${it.message}") }
            }
    }

    fun ensureCatalogFresh(force: Boolean = false) {
        if (catalogRefreshJob?.isActive == true) return
        val catalog = _state.value.catalog
        if (!force && catalog != null && catalog.dedupedItems.isNotEmpty()) return
        if (_state.value.loading && !force) return
        catalogRefreshJob = repoScope.launch { runCatching { refresh(force) } }
    }

    fun itemById(vodId: String): VodItem? =
        _state.value.catalog?.groups?.values?.flatten()?.firstOrNull { it.id == vodId }

    fun siblings(normTitle: String): List<VodItem> =
        _state.value.catalog?.groups?.get(normTitle).orEmpty()

    suspend fun search(query: String): List<VodItem> {
        val normalized = query.trim()
        if (normalized.isEmpty() || !official.isSignedIn()) return emptyList()
        return try {
            official.search(normalized)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun byCategory(catId: String): List<VodItem> =
        _state.value.catalog?.dedupedItems
            ?.filter { catId.isBlank() || it.categoryId == catId }
            .orEmpty()

    suspend fun loadHomeFirstSection(
        category: String,
        revalidate: Boolean = false,
    ): List<VodItem> = coroutineScope {
        val cid = categoryCid(category) ?: return@coroutineScope emptyList()
        val expectedSession = currentContentSession()
        val existing =
            if (!revalidate) {
                _homeRecommendations.value[cid]
                    ?.firstOrNull { it.name == "大家都在看" }
                    ?.items
            } else {
                null
            }
        val ranking = existing ?: official.totalRanking(category)
        val firstRow =
            ChannelSection(
                "大家都在看",
                ranking.filter { HomeFeedLayout.matchesCategory(it, category) },
            )
        if (isCurrentContentSession(expectedSession)) {
            _homeRecommendations.update { current ->
                current + (cid to (listOf(firstRow) + current[cid].orEmpty().filterNot {
                    it.name == firstRow.name
                }))
            }
        }
        firstRow.items
    }

    suspend fun loadHomeRecommendations(
        category: String,
        requireComplete: Boolean = false,
        revalidate: Boolean = false,
    ) = coroutineScope {
        val cid = categoryCid(category) ?: return@coroutineScope
        val expectedSession = currentContentSession()
        val normalizedCategory =
            category.removePrefix("douban:").removePrefix("hhkan:")
        val cacheKey = HomeRecommendationsCacheKey(normalizedCategory, expectedSession)
        completeHomeRecommendations[cacheKey]?.takeUnless { revalidate }?.let { cached ->
            if (isCurrentContentSession(expectedSession)) {
                _homeRecommendations.update { it + (cid to cached) }
            }
            return@coroutineScope
        }
        val seededFirstRow =
            if (!revalidate) {
                _homeRecommendations.value[cid]
                    ?.firstOrNull { it.name == "大家都在看" }
                    ?.items
            } else {
                null
            }
        val rankingRequest =
            async {
                try {
                    seededFirstRow ?: official.totalRanking(category)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (requireComplete) throw failure
                    emptyList()
                }
            }
        val sectionsRequest =
            async {
                try {
                    when (category) {
                        "movie", "tv" ->
                            official.recommendationSections(category, requireComplete)
                                .map { ChannelSection(it.title, it.items) }
                        "anime" ->
                            official.animeRecommendationSections(requireComplete).map { section ->
                                ChannelSection(
                                    section.title,
                                    section.items.filter {
                                        HomeFeedLayout.matchesCategory(it, category)
                                    },
                                )
                            }
                        else -> emptyList()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (requireComplete) throw failure
                    emptyList()
                }
            }
        val shortRequest =
            async {
                if (category != "short") emptyList() else
                    try {
                        official.shortDramaRecommendations()
                            .map { ChannelSection(it.title, it.items) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (requireComplete) throw failure
                        emptyList()
                    }
            }
        val ranking = rankingRequest.await()
        val rankRow =
            ChannelSection(
                "大家都在看",
                ranking.filter { HomeFeedLayout.matchesCategory(it, category) },
            )
        if (isCurrentContentSession(expectedSession)) {
            _homeRecommendations.update { current ->
                current + (cid to (listOf(rankRow) + current[cid].orEmpty().filterNot {
                    it.name == rankRow.name
                }))
            }
        }
        val fallback = shortRequest.await()
        if (category == "short" && isCurrentContentSession(expectedSession)) {
            _homeRecommendations.update { current -> current + (cid to (listOf(rankRow) + fallback)) }
        }
        var rows = sectionsRequest.await()
        if (category == "short") {
            rows =
                listOf("最近热门", "最近更新").map { title ->
                    val original = rows.firstOrNull { it.name == title }
                    if (original?.items.orEmpty().any {
                            HomeFeedLayout.matchesCategory(it, "short")
                        }
                    ) {
                        ChannelSection(title, original?.items.orEmpty())
                    } else {
                        fallback.firstOrNull { it.name == title }
                            ?: ChannelSection(title, emptyList())
                    }
                }
        }
        val result =
            HomeFeedLayout.sections(
                category,
                ranking,
                if (category == "movie" || category == "tv") rows else emptyList(),
                if (category == "movie" || category == "tv") emptyList() else rows,
            )
        if (isCurrentContentSession(expectedSession)) {
            val content = completeHomeRecommendations[cacheKey]?.takeIf { it == result } ?: result
            _homeRecommendations.update { it + (cid to content) }
            if (requireComplete) completeHomeRecommendations[cacheKey] = content
        }
    }

    fun refreshHome(force: Boolean = false) {
        if (homeJob?.isActive == true) return
        if (!force && (_home.value.sections.isNotEmpty() || _home.value.carousel.isNotEmpty())) return
        _home.value = _home.value.copy(loading = true)
        val expectedSession = currentContentSession()
        homeJob =
            repoScope.launch {
                if (!isCurrentContentSession(expectedSession) || !official.isSignedIn()) {
                    _home.value = _home.value.copy(loading = false)
                    return@launch
                }
                val home =
                    try {
                        official.home()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                if (home == null || !isCurrentContentSession(expectedSession)) {
                    _home.value = _home.value.copy(loading = false)
                    return@launch
                }
                _home.value =
                    HomeState(
                        loading = false,
                        sections = home.sections.map { ChannelSection(it.title, it.items) },
                        carousel =
                            home.carousel.map {
                                CarouselItem(
                                    vid = it.id,
                                    title = it.title,
                                    backdrop = it.heroUrl.ifBlank { it.posterUrl },
                                    tags = it.tags,
                                )
                            },
                    )
            }
    }

    suspend fun awaitHome() {
        refreshHome()
        homeJob?.join()
    }

    suspend fun channelSections(categoryId: String, force: Boolean = false) {
        val cid = categoryCid(categoryId) ?: return
        val session = currentContentSession()
        val now = android.os.SystemClock.elapsedRealtime()
        if (
            !force &&
            now - (sectionFetchedAt[cid] ?: -60_000L) < 60_000L &&
            _sections.value[cid].orEmpty().any { it.items.isNotEmpty() }
        ) {
            return
        }
        val (requestKey, request) =
            synchronized(sectionRequestLock) {
                val requestKey = ChannelRequestKey(cid, session)
                val request =
                    sectionRequests[requestKey]?.takeUnless { it.isCompleted }
                        ?: repoScope.async(start = CoroutineStart.LAZY) {
                            loadChannelSections(categoryId, cid, force, session)
                        }.also { sectionRequests[requestKey] = it }
                requestKey to request
            }
        request.invokeOnCompletion {
            synchronized(sectionRequestLock) {
                if (sectionRequests[requestKey] === request) sectionRequests.remove(requestKey)
            }
        }
        request.start()
        request.await()
    }

    private suspend fun loadChannelSections(
        categoryId: String,
        cid: Int,
        force: Boolean,
        expectedSession: ContentSessionSnapshot?,
    ) {
        if (!isCurrentContentSession(expectedSession) || !official.isSignedIn()) return
        if (_state.value.catalog == null) refresh()
        val all =
            _state.value.catalog?.groups?.values?.flatten().orEmpty()
                .filter { it.categoryId == categoryId }
                .distinctBy(VodItem::id)
        val sections =
            listOf("热门推荐", "最近更新", "全部").mapIndexed { index, title ->
                ChannelSection(
                    title,
                    when (index) {
                        0 -> all.take(18)
                        1 -> all.drop(18).take(18).ifEmpty { all.take(18) }
                        else -> all
                    },
                )
            }.filter { it.items.isNotEmpty() }
        if (sections.isNotEmpty() && isCurrentContentSession(expectedSession)) {
            _sections.update { current -> current + (cid to sections) }
            sectionFetchedAt[cid] = android.os.SystemClock.elapsedRealtime()
        }
    }

    fun libBlocks(categoryId: String) = libBlocks(categoryId, force = false)

    fun libBlocks(categoryId: String, force: Boolean) {
        val category = categoryId.removePrefix("douban:")
        val cid = categoryNumber(category) ?: return
        if (libJobs[cid]?.isActive == true) return
        if (!force && cid in libDone) return
        val failAt = libFailAt[cid]
        if (!force && failAt != null && System.currentTimeMillis() - failAt < 60_000L) return
        val expectedSession = currentContentSession()
        libJobs[cid] =
            repoScope.launch {
                if (!isCurrentContentSession(expectedSession)) return@launch
                if (!official.isSignedIn()) {
                    markHotSettled(cid)
                    return@launch
                }
                channelSections(categoryId, force)
                if (!isCurrentContentSession(expectedSession)) return@launch
                val sections = _sections.value[cid].orEmpty()
                if (sections.any { it.items.isNotEmpty() }) {
                    _libBlocks.update { it + (cid to sections) }
                    libDone += cid
                    libFailAt.remove(cid)
                    libRetries.remove(cid)
                } else {
                    libFailAt[cid] = System.currentTimeMillis()
                    scheduleLibRetry(cid)
                }
                markHotSettled(cid)
            }
        repoScope.launch {
            delay(8_000L)
            markHotSettled(cid)
        }
    }

    private fun scheduleLibRetry(cid: Int) {
        val attempt = libRetries.getOrDefault(cid, 0)
        if (attempt >= 6 || libRetryJobs[cid]?.isActive == true) return
        val wait = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L, 25_000L, 40_000L)[attempt]
        libRetries[cid] = attempt + 1
        libRetryJobs[cid] =
            repoScope.launch {
                delay(wait)
                libBlocks(categoryKey(cid), force = true)
                libJobs[cid]?.join()
            }
    }

    fun libBlocksHaveContent(cid: Int): Boolean =
        _libBlocks.value[cid].orEmpty().any { it.items.isNotEmpty() }

    suspend fun awaitLibBlocks(categoryId: String) {
        val cid = categoryNumber(categoryId.removePrefix("douban:")) ?: return
        libBlocks(categoryId)
        libJobs[cid]?.join()
    }

    suspend fun awaitLibBlocksContent(
        categoryId: String,
        budgetMs: Long = 45_000L,
    ): Boolean {
        val cid = categoryNumber(categoryId.removePrefix("douban:")) ?: return false
        val deadline = System.currentTimeMillis() + budgetMs
        while (true) {
            libBlocks(categoryId, force = true)
            if (libBlocksHaveContent(cid)) return true
            val waitMs = (deadline - System.currentTimeMillis()).coerceAtMost(2_000L)
            if (waitMs <= 0L) return false
            val appeared =
                withTimeoutOrNull(waitMs) {
                    _libBlocks.first { libBlocksHaveContent(cid) }
                }
            if (appeared != null) return true
        }
    }

    private fun markHotSettled(cid: Int) {
        _hotSettled.update { it + cid }
    }

    suspend fun vodMeta(vodId: String): String = withContext(Dispatchers.IO) {
        val item = itemById(vodId) ?: return@withContext ""
        if (item.detailRef.isBlank()) return@withContext item.meta.ifBlank { item.year }
        fetchDetailCached(item.detailRef)?.meta?.takeIf(String::isNotBlank)
            ?: item.meta.ifBlank { item.year }
    }

    fun peekBrief(vodId: String): Triple<String, String, Double>? = briefCache[vodId]

    suspend fun vodBrief(vodId: String): Triple<String, String, Double>? =
        withContext(Dispatchers.IO) {
            briefCache[vodId]?.let { return@withContext it }
            val item = itemById(vodId)
            val ref =
                item?.detailRef?.takeIf(String::isNotBlank)
                    ?: vodId.takeIf { it.startsWith("dongpian:") }
                    ?: return@withContext null
            val meta =
                item?.let {
                    (listOf(it.year, it.area).filter(String::isNotBlank) + it.tags)
                        .joinToString(" / ")
                }.orEmpty()
            val detail = fetchDetailCached(ref) ?: return@withContext null
            Triple(
                detail.meta.ifBlank { meta },
                detail.desc,
                if ((item?.rating ?: 0.0) > 0.0) item?.rating ?: detail.rating else detail.rating,
            ).also { briefCache[vodId] = it }
        }

    private suspend fun fetchDetailCached(
        ref: String,
        forceRefresh: Boolean = false,
        refreshSources: Boolean = forceRefresh,
    ): DetailInfo? {
        if (!ref.startsWith("dongpian:")) return null
        val cached = detailInfoCache[ref]
        if (!forceRefresh && cached != null) return cached
        if (!forceRefresh) {
            return detailRequests.await(ref) {
                detailInfoCache[ref] ?: fetchDetailUncached(ref, refreshSources)
            }
        }
        return fetchDetailUncached(ref, refreshSources)
    }

    private suspend fun fetchDetailUncached(
        ref: String,
        @Suppress("UNUSED_PARAMETER") refreshSources: Boolean,
    ): DetailInfo? {
        val cached = detailInfoCache[ref]
        val generation =
            detailRequestGenerations.computeIfAbsent(ref) {
                java.util.concurrent.atomic.AtomicLong()
            }.incrementAndGet()
        val detail =
            try {
                official.detail(ref).also { officialDetailCache[ref] = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                com.qiubo.optimaltv.OtvLog.w(
                    "fetchDetail 失败 ref=$ref : ${failure.message}",
                )
                return cached
            }
        val info =
            DetailInfo(
                title = detail.item.title,
                year = detail.item.year,
                desc = detail.item.desc,
                poster = detail.item.posterUrl,
                rating = detail.item.rating,
                meta = detail.item.meta,
                actors = detail.item.actors,
                lines =
                    listOf(
                        LineDetail(
                            "懂片帝官方线路",
                            detail.episodes.mapIndexed { index, episode ->
                                Triple(
                                    episode.name,
                                    detail.episodeTokens[index],
                                    index.toString(),
                                )
                            },
                        ),
                    ),
            )
        if (detailRequestGenerations[ref]?.get() != generation) {
            return detailInfoCache[ref] ?: cached
        }
        if (info.lines.isNotEmpty()) detailInfoCache[ref] = info
        return if (info.lines.isNotEmpty() || cached == null) info else cached
    }

    fun episodesForLine(item: VodItem, line: LineInfo?): List<Episode>? {
        if (line?.official != null) {
            return officialDetailCache[item.detailRef]
                ?.episodes
                ?.takeIf { it.isNotEmpty() }
        }
        return detailInfoCache[item.detailRef]
            ?.lines
            ?.firstOrNull()
            ?.episodes
            ?.mapIndexed { index, (name, _, _) ->
                Episode(index, name.ifBlank { "第${index + 1}集" }, "")
            }
            ?.takeIf { it.isNotEmpty() }
    }

    fun episodesForSelection(item: VodItem, preferredLine: LineInfo?): List<Episode>? {
        if (preferredLine?.official != null || item.detailRef.startsWith("dongpian:")) {
            return officialDetailCache[item.detailRef]
                ?.episodes
                ?.takeIf { it.isNotEmpty() }
        }
        return episodesForLine(item, preferredLine)
    }

    fun episodeSourceIdentity(line: LineInfo?): String? =
        line?.officialStableLineId?.takeIf(String::isNotBlank)?.let { "official:$it" }

    fun defaultEpisodeSourceIdentity(item: VodItem): String? {
        val first = detailInfoCache[item.detailRef]?.lines?.firstOrNull() ?: return null
        val sourceId = first.episodes.firstOrNull()?.second?.takeIf(String::isNotBlank)
            ?: return null
        return "${first.name}|$sourceId"
    }

    suspend fun resolveDetail(
        vodId: String,
        forceRefresh: Boolean = false,
        refreshSources: Boolean = forceRefresh,
    ): VodItem? = withContext(Dispatchers.IO) {
        val cached = detailCache[vodId]
        if (!forceRefresh && cached != null) return@withContext cached
        val item =
            itemById(vodId)
                ?: if (vodId.startsWith("dongpian:")) {
                    VodItem(
                        id = vodId,
                        sourceId = "dongpian",
                        title = "",
                        categoryId = "",
                        detailRef = vodId,
                    )
                } else {
                    return@withContext null
                }
        if (item.detailRef.isBlank()) return@withContext item
        val detail =
            fetchDetailCached(item.detailRef, forceRefresh, refreshSources)
                ?: return@withContext cached ?: item
        val episodes =
            detail.lines.firstOrNull()?.episodes?.mapIndexed { index, (name, token, _) ->
                Episode(
                    index,
                    name.ifBlank { "第${index + 1}集" },
                    token,
                )
            }.orEmpty()
        item.copy(
            title = item.title.ifBlank { detail.title },
            episodes = episodes,
            year = item.year.ifBlank { detail.year },
            desc = item.desc.ifBlank { detail.desc },
            posterUrl = item.posterUrl.ifBlank { detail.poster },
            rating = if (item.rating > 0.0) item.rating else detail.rating,
            meta = item.meta.ifBlank { detail.meta },
            actors = item.actors.ifBlank { detail.actors },
        ).also {
            if (episodes.isNotEmpty()) {
                detailCache[vodId] = it
                detailCacheFreshAtMs[vodId] = android.os.SystemClock.elapsedRealtime()
            }
        }
    }

    fun cachedDetailAgeMs(vodId: String): Long? {
        if (!detailCache.containsKey(vodId)) return null
        val cachedAt = detailCacheFreshAtMs[vodId] ?: return Long.MAX_VALUE
        return (android.os.SystemClock.elapsedRealtime() - cachedAt).coerceAtLeast(0L)
    }

    /** Community intro/outro data comes from the official detail already fetched for playback. */
    fun officialSkipPreset(item: VodItem): OfficialSkipPreset? {
        val ref = item.detailRef.ifBlank { item.id }
        if (!ref.startsWith("dongpian:")) return null
        return officialDetailCache[ref]?.skipPreset
    }

    suspend fun preloadDetailSources(ids: List<String>, batch: Int = 2): Int {
        val targets = ids.filter(String::isNotBlank).distinct()
        if (targets.isEmpty()) return 0
        var ready = 0
        for (chunk in targets.chunked(batch.coerceAtLeast(1))) {
            val results =
                coroutineScope {
                    chunk.map { id ->
                        async {
                            runCatching { resolveDetail(id) }.getOrNull()
                        }
                    }.awaitAll()
                }
            ready += results.count { !it?.episodes.isNullOrEmpty() }
        }
        return ready
    }

    suspend fun resolveLines(item: VodItem, epIndex: Int): List<LineInfo> =
        withContext(Dispatchers.IO) {
            if (!item.detailRef.startsWith("dongpian:")) return@withContext emptyList()
            val detail =
                fetchDetailCached(item.detailRef) ?: return@withContext emptyList()
            val officialDetail =
                officialDetailCache[item.detailRef]
                    ?: official.detail(item.detailRef).also {
                        officialDetailCache[item.detailRef] = it
                    }
            val token =
                officialDetail.episodeTokens.getOrNull(epIndex)
                    ?: detail.lines.firstOrNull()?.episodes?.getOrNull(epIndex)?.second
                    ?: return@withContext emptyList()
            val sequenceIndex =
                officialDetail.episodeSequenceIndices.getOrNull(epIndex) ?: epIndex
            official.playback(
                token = token,
                sequenceIndex = sequenceIndex,
                episodeCount = officialDetail.episodeCount,
                contentKind = officialDetail.contentKind,
            ).lines.map { it.copy(vodId = item.id) }
        }

    internal data class SourcePick(
        val lines: List<LineInfo>,
        val preferredIndex: Int,
        val tier: SourceResolveCascade.Tier,
    ) {
        val isEmpty: Boolean
            get() = lines.isEmpty() || preferredIndex !in lines.indices
        val hasImmediate: Boolean
            get() = !isEmpty
    }

    internal suspend fun pickPlayableSources(
        item: VodItem,
        epIndex: Int,
        @Suppress("UNUSED_PARAMETER")
        lastGoodDao: com.qiubo.optimaltv.data.db.LastGoodSourceDao? = null,
    ): SourcePick = withContext(Dispatchers.IO) {
        val lines = runCatching { resolveLines(item, epIndex) }.getOrDefault(emptyList())
        if (lines.isEmpty()) return@withContext SourcePick(emptyList(), 0, SourceResolveCascade.Tier.NONE)
        val remembered = runCatching { settings.lastVodSource(item.id) }.getOrNull()
        val preferred =
            lines.indexOfFirst { PlayableLineIdentity.id(it) == remembered }
                .takeIf { it >= 0 }
                ?: 0
        SourcePick(lines, preferred, SourceResolveCascade.Tier.LOCAL_DETAIL)
    }

    internal suspend fun rememberLastGoodSource(
        @Suppress("UNUSED_PARAMETER") mytvId: Long,
        @Suppress("UNUSED_PARAMETER") epIndex: Int,
        @Suppress("UNUSED_PARAMETER") lineId: String,
        @Suppress("UNUSED_PARAMETER") dao: com.qiubo.optimaltv.data.db.LastGoodSourceDao? = null,
    ) {
        // Official lines are already remembered by stable line identity in SettingsStore.
    }

    fun lineIdForLastGood(line: LineInfo): String = PlayableLineIdentity.id(line)

    fun bustPlayCache(@Suppress("UNUSED_PARAMETER") playRef: String) {
        // Official resolveLine owns its ticket cache and invalidates it explicitly.
    }

    suspend fun resolvePlayback(
        line: LineInfo,
        @Suppress("UNUSED_PARAMETER") preferRelay: Boolean = true,
        forceRefresh: Boolean = false,
    ): ResolvedPlaybackUrl = withContext(Dispatchers.IO) {
        val binding = line.official ?: return@withContext ResolvedPlaybackUrl("")
        val officialLine = binding.line
        if (
            com.qiubo.optimaltv.data.source.DongpianPlaybackProtocol.canPlayDirectly(
                officialLine,
                forceRefresh = forceRefresh,
            )
        ) {
            return@withContext ResolvedPlaybackUrl(
                url = officialLine.url,
                headers = officialLine.headers,
                userAgent = officialLine.userAgent,
                mimeType = officialLine.mimeType,
            )
        }
        val resolved = official.resolveLine(binding, forceRefresh)
        ResolvedPlaybackUrl(
            url = resolved.url,
            headers = resolved.headers,
            userAgent = resolved.userAgent,
            mimeType = resolved.mimeType,
        )
    }

    suspend fun resolvePlayUrl(
        line: LineInfo,
        preferRelay: Boolean = true,
        forceRefresh: Boolean = false,
    ): String = resolvePlayback(line, preferRelay, forceRefresh).url

    suspend fun probeLineFirstByte(line: LineInfo): Long = withContext(Dispatchers.IO) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val playback = resolvePlayback(line, preferRelay = false, forceRefresh = true)
        if (playback.url.isBlank()) throw java.io.IOException("当前线路没有返回播放地址")
        val request =
            okhttp3.Request.Builder()
                .url(playback.url)
                .header("Range", "bytes=0-0")
                .apply {
                    playback.headers.forEach { (name, value) -> header(name, value) }
                    playback.userAgent.takeIf(String::isNotBlank)?.let { header("User-Agent", it) }
                }
                .get()
                .build()
        okHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            val firstByte = response.body?.byteStream()?.read() ?: -1
            if (firstByte < 0) throw java.io.IOException("线路没有返回媒体数据")
        }
        (android.os.SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
    }

    suspend fun showPage(
        cid: Int,
        type: String = "",
        area: String = "",
        lang: String = "",
        year: String = "",
        rating: String = "",
        by: String = "3",
        page: Int = 1,
    ): ShowPage? = withContext(Dispatchers.IO) {
        if (!official.isSignedIn()) return@withContext null
        runCatching {
            val result =
                official.browsePage(
                    category = categoryKey(cid),
                    type = type,
                    area = area,
                    year = year,
                    rating = rating,
                    sort = when (by.lowercase()) {
                        "2", "new" -> "new"
                        "1", "rating" -> "rating"
                        else -> "hot"
                    },
                    page = page,
                )
            ShowPage(result.items, result.hasMore)
        }.getOrNull()
    }

    fun clearAccountScopedCaches() {
        synchronized(refreshLock) {
            refreshJob?.cancel()
            refreshJob = null
            refreshJobForce = false
        }
        catalogRefreshJob?.cancel()
        catalogRefreshJob = null
        catalogRetryJob?.cancel()
        catalogRetryJob = null
        catalogRetry = 0
        homeJob?.cancel()
        homeJob = null
        synchronized(sectionRequestLock) {
            sectionRequests.values.toList().forEach { it.cancel() }
            sectionRequests.clear()
        }
        libJobs.values.toList().forEach { it.cancel() }
        libJobs.clear()
        libRetryJobs.values.toList().forEach { it.cancel() }
        libRetryJobs.clear()
        _state.value = CatalogState(loading = false)
        _sections.value = emptyMap()
        _homeRecommendations.value = emptyMap()
        completeHomeRecommendations.clear()
        sectionFetchedAt.clear()
        _home.value = HomeState()
        _libBlocks.value = emptyMap()
        _hotSettled.value = emptySet()
        libDone.clear()
        libFailAt.clear()
        libRetries.clear()
        detailCache.clear()
        detailCacheFreshAtMs.clear()
        detailInfoCache.clear()
        officialDetailCache.clear()
        detailRequests.clear()
        detailRequestGenerations.clear()
        briefCache.clear()
        official.clearAccountScopedCaches()
        snapshotFile.delete()
    }

    private fun readSnapshotCandidates(): List<String> =
        buildList {
            if (CatalogSnapshotPolicy.isReadableSize(snapshotFile.length())) {
                runCatching { snapshotFile.readText() }.getOrNull()?.let(::add)
            }
        }

    private suspend fun restoreSnapshot() {
        if (_state.value.catalog != null) return
        for (text in readSnapshotCandidates()) {
            val snapshot =
                runCatching {
                    decodeSnapshot(text, CatalogOriginPolicy.PERSISTED_ACCEPTED_VERSIONS)
                }.getOrNull()
                    ?: continue
            val grouped = snapshot.items.groupBy { it.normTitle }
            _state.value =
                CatalogState(
                    loading = false,
                    catalog =
                        Aggregated(
                            categories = snapshot.categories,
                            dedupedItems = grouped.values.map { it.first() },
                            groups = grouped,
                        ),
                    sourceStates =
                        listOf(
                            SourceState(
                                "dongpian",
                                "懂片帝官方（缓存）",
                                true,
                                "${snapshot.items.size} 部",
                            ),
                        ),
                )
            return
        }
    }

    private fun writeSnapshot(categories: List<Category>, items: List<VodItem>) {
        if (items.isEmpty() || items.any {
                !CatalogOriginPolicy.allows(it.sourceId, it.id)
            }
        ) {
            return
        }
        val root =
            JSONObject()
                .put("v", CatalogOriginPolicy.SNAPSHOT_VERSION)
                .put("ts", System.currentTimeMillis())
                .put(
                    "categories",
                    JSONArray().apply {
                        categories.forEach {
                            put(JSONObject().put("id", it.id).put("name", it.name))
                        }
                    },
                )
        var persisted = CatalogSnapshotPolicy.boundedItems(items)
        do {
            root.put(
                "items",
                JSONArray().apply {
                    persisted.forEach {
                        put(
                            JSONObject()
                                .put("id", it.id)
                                .put("s", it.sourceId)
                                .put("t", it.title)
                                .put("c", it.categoryId)
                                .put("y", it.year)
                                .put("ar", it.area)
                                .put("r", it.rating)
                                .put("p", it.posterUrl)
                                .put("h", it.heroUrl)
                                .put("rm", it.remark)
                                .put("d", it.detailRef)
                                .put("mytv_id", it.mytvId)
                                .put("q", JSONArray(it.searchTerms)),
                        )
                    }
                },
            )
            val encoded = root.toString()
            if (
                encoded.toByteArray(Charsets.UTF_8).size <= CatalogSnapshotPolicy.MAX_BYTES ||
                persisted.isEmpty()
            ) {
                val tmp = java.io.File(snapshotFile.parentFile, snapshotFile.name + ".tmp")
                tmp.writeText(encoded)
                if (!tmp.renameTo(snapshotFile)) {
                    snapshotFile.delete()
                    tmp.renameTo(snapshotFile)
                }
                return
            }
            persisted = persisted.dropLast((persisted.size / 4).coerceAtLeast(1))
        } while (true)
    }

    private fun currentContentSession(): ContentSessionSnapshot? =
        contentSessionSnapshot(DongpianAuthClient.currentSession(appContext))

    private fun isCurrentContentSession(expected: ContentSessionSnapshot?): Boolean =
        currentContentSession() == expected

    companion object {
        fun categoryCid(categoryId: String): Int? {
            val raw = categoryId.removePrefix("douban:").removePrefix("hhkan:")
            return categoryNumber(raw) ?: raw.toIntOrNull()
        }

        private fun categoryNumber(key: String): Int? =
            when (key) {
                "movie" -> 1
                "tv" -> 2
                "anime" -> 3
                "variety" -> 4
                "short" -> 6
                else -> null
            }

        private fun categoryKey(cid: Int): String =
            when (cid) {
                2 -> "tv"
                3 -> "anime"
                4 -> "variety"
                6 -> "short"
                else -> "movie"
            }
    }
}
