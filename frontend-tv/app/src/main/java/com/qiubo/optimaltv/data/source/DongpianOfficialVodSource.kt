package com.qiubo.optimaltv.data.source

import android.content.Context
import com.qiubo.optimaltv.OtvLog
import com.qiubo.optimaltv.auth.DongpianAuthClient
import com.qiubo.optimaltv.auth.DongpianAuthException
import com.qiubo.optimaltv.data.model.Category
import com.qiubo.optimaltv.data.model.Episode
import com.qiubo.optimaltv.data.model.LineInfo
import com.qiubo.optimaltv.data.model.OfficialPlaybackBinding
import com.qiubo.optimaltv.data.model.OfficialPlaybackLine
import com.qiubo.optimaltv.data.model.VodItem
import com.qiubo.optimaltv.data.model.firstUsablePosterUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Official Dongpiandi catalogue adapter for the migrated My TV screens. */
class DongpianOfficialVodSource(
    private val context: Context,
    private val playbackApi: (Context, String, String, String?) -> JSONObject =
        DongpianAuthClient::apiJson,
) {
    data class HomeSection(
        val title: String,
        val items: List<VodItem>,
    )

    data class Home(
        val sections: List<HomeSection>,
        val carousel: List<VodItem>,
    )

    data class Detail(
        val item: VodItem,
        val episodes: List<Episode>,
        val episodeTokens: List<String>,
        val episodeSequenceIndices: List<Int?>,
        val episodeCount: Int,
        val contentKind: String,
    )

    data class Playback(
        val lines: List<LineInfo>,
        val rawLineCount: Int,
        val duplicateLineCount: Int,
    )

    private data class DetailEpisode(
        val episode: Episode,
        val token: String,
        val sequenceIndex: Int?,
    )

    private val officialResolvedLines =
        java.util.concurrent.ConcurrentHashMap<String, OfficialPlaybackLine>()
    private val officialLatestBindings =
        java.util.concurrent.ConcurrentHashMap<String, OfficialPlaybackBinding>()
    private val recommendationSlots = Semaphore(4)

    fun isSignedIn(): Boolean = DongpianAuthClient.isSignedIn(context)

    suspend fun snapshot(): SourceSnapshot = withContext(Dispatchers.IO) {
        OtvLog.i("official snapshot start")
        // The first three catalogue pages are independent. Fetch them in
        // parallel so cold start pays one network round trip instead of 2-3.
        val pages = coroutineScope {
            (1..3).map { page ->
                async {
                    DongpianAuthClient.apiJson(
                        context,
                        "/v1/browse/catalog?page=$page&limit=100",
                    )
                }
            }.awaitAll()
        }
        val items = pages.flatMap { json ->
            parseCards(json.optJSONArray("cards") ?: JSONArray())
        }
        val distinct = items.distinctBy(VodItem::id)
        SourceSnapshot(
            sourceId = "dongpian",
            sourceName = "懂片帝官方",
            categories = CATEGORY_LABELS.map { (key, label) ->
                Category("douban:$key", label)
            },
            items = distinct,
        ).also { OtvLog.i("official snapshot ok items=${it.items.size}") }
    }

    suspend fun home(complete: Boolean = false): Home = withContext(Dispatchers.IO) {
        OtvLog.i("official home start")
        val path = if (complete) "/v1/feed/home?scope=public&mode=page&sections=20&cards=12" else "/v1/feed/home"
        val json = DongpianAuthClient.apiJson(context, path)
        val sections =
            json.optJSONArray("sections").orEmpty().mapNotNull { section ->
                val obj = section as? JSONObject ?: return@mapNotNull null
                val items = parseCards(obj.optJSONArray("cards") ?: JSONArray())
                if (items.isEmpty()) null
                else HomeSection(obj.optString("title", "推荐"), items)
            }
        Home(
            sections = sections,
            carousel = sections.flatMap { it.items }.distinctBy(VodItem::id).take(8),
        ).also { OtvLog.i("official home ok sections=${it.sections.size}") }
    }

    suspend fun totalRanking(category: String = ""): List<VodItem> {
        val kind = when (category) {
            "tv" -> "series"
            "short" -> "short_drama"
            else -> category
        }
        val filter = if (kind.isBlank()) "" else "&kind=${URLEncoder.encode(kind, "UTF-8")}"
        return browseAll("sort=trending&window=total$filter")
    }

    suspend fun shortDramaRecommendations(): List<HomeSection> = coroutineScope {
        listOf("最近热门" to "sort=trending&window=day", "最近更新" to "intent=latest_catalog").map { (title, query) ->
            async { HomeSection(title, browseAll("kind=short_drama&$query", maxPages = if (title == "最近更新") 1 else Int.MAX_VALUE)) }
        }.awaitAll()
    }

    suspend fun recommendationSections(
        category: String,
        requireComplete: Boolean = false,
    ): List<HomeSection> = coroutineScope {
        val lists = when (category) {
            "movie" -> listOf(
                "高分电影" to "movie_high_score", "电影热播" to "movie_hot",
                "院线热映" to "movie_nowplaying", "即将上映" to "movie_upcoming", "冷门好片" to "movie_hidden_gems",
            )
            "tv" -> listOf(
                "国产新剧" to "tv_domestic", "美剧续看" to "tv_american",
                "韩剧在追" to "tv_korean", "日剧上新" to "tv_japanese",
            )
            else -> emptyList()
        }
        lists.map { (title, key) ->
            async {
                val items = recommendationSlots.withPermit {
                    try {
                        val availability = if (key == "movie_nowplaying" || key == "movie_upcoming") "&include_unavailable=true" else ""
                        browseAll("hot_list_key=$key$availability")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (requireComplete) throw failure
                        OtvLog.w("推荐栏目加载失败: $title ${failure.javaClass.simpleName}")
                        emptyList()
                    }
                }
                HomeSection(title, items)
            }
        }.awaitAll()
    }

    private suspend fun browseAll(query: String, maxPages: Int = Int.MAX_VALUE): List<VodItem> = withContext(Dispatchers.IO) {
        val items = linkedMapOf<String, VodItem>()
        var page = 1
        while (true) {
            val json = playbackApi(
                context,
                "/v1/browse/catalog?$query&page=$page&limit=50",
                "GET",
                null,
            )
            val cards = parseCards(json.optJSONArray("cards") ?: JSONArray())
            val previousCount = items.size
            cards.forEach { items.putIfAbsent(it.id, it) }
            val pagination = json.optJSONObject("pagination")
            if (items.size == previousCount || pagination?.optBoolean("has_more") != true || page >= maxPages) break
            val next = pagination.optInt("next_page", page + 1)
            if (next <= page) break
            page = next
        }
        items.values.toList()
    }

    suspend fun search(
        query: String,
        page: Int = 1,
        limit: Int = 60,
    ): List<VodItem> = withContext(Dispatchers.IO) {
        OtvLog.i("official search start")
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val json = DongpianAuthClient.apiJson(
            context,
            "/v1/browse/catalog?q=$encoded&page=$page&limit=$limit",
        )
        parseCards(json.optJSONArray("cards") ?: JSONArray()).also {
            OtvLog.i("official search ok items=${it.size} ids=${it.take(3).joinToString { item -> item.id }}")
        }
    }

    suspend fun detail(variantId: String): Detail = withContext(Dispatchers.IO) {
        OtvLog.i("official detail start id=$variantId")
        val id = variantId.removePrefix("dongpian:")
        val encoded = URLEncoder.encode(id, "UTF-8").replace("+", "%20")
        val json = DongpianAuthClient.apiJson(context, "/v1/catalog/$encoded")
        val episodesJson = json.optJSONArray("episodes") ?: JSONArray()
        val parsed =
            episodesJson.orEmpty().mapNotNull { raw ->
                val ep = raw as? JSONObject ?: return@mapNotNull null
                val token = ep.optString("token").trim()
                if (token.isBlank()) return@mapNotNull null
                val number =
                    ep.optInt(
                        "number",
                        ep.optInt("episode_number", 0),
                    ).takeIf { it > 0 }
                val title =
                    ep.optString("display_name")
                        .ifBlank { ep.optString("title") }
                        .ifBlank { number?.let { "第${it}集" } ?: "选集" }
                DetailEpisode(
                    // The token is both the official episode identity and the danmaku source key.
                    // Keep it on the Episode so refreshed/select-episode paths cannot drop it.
                    episode = Episode(parsedIndex(episodesJson, ep), title, token),
                    token = token,
                    sequenceIndex =
                        if (ep.has("sequence_index") && !ep.isNull("sequence_index")) {
                            ep.optInt("sequence_index", -1).takeIf { it >= 0 }
                        } else {
                            null
                        },
                )
            }
        val item = parseCard(json)?.copy(
            desc = json.optString("description"),
            detailRef = "dongpian:$id",
        ) ?: VodItem(
            id = "dongpian:$id",
            sourceId = "dongpian",
            title = json.optString("title"),
            categoryId = categoryId(json.optString("content_kind")),
            detailRef = "dongpian:$id",
        )
        Detail(
            item = item,
            episodes = parsed.map { it.episode },
            episodeTokens = parsed.map { it.token },
            episodeSequenceIndices = parsed.map { it.sequenceIndex },
            episodeCount =
                json.optInt("episode_count", 0)
                    .takeIf { it > 0 }
                    ?: parsed.size,
            contentKind = json.optString("content_kind"),
        ).also { OtvLog.i("official detail ok episodes=${it.episodes.size}") }
    }

    suspend fun playback(
        token: String,
        sequenceIndex: Int?,
        episodeCount: Int?,
        contentKind: String,
    ): Playback = withContext(Dispatchers.IO) {
        OtvLog.i("official playback start")
        val json =
            playbackRequest(
                DongpianPlaybackProtocol.initialResolvePath(
                    episodeToken = token,
                    sequenceIndex = sequenceIndex,
                    episodeCount = episodeCount,
                    contentKind = contentKind,
                ),
            )
        val payload = json.optJSONObject("data") ?: json
        val rawLines =
            DongpianPlaybackProtocol.parseLines(payload.optJSONArray("line_options"))
        val lines = DongpianPlaybackProtocol.deduplicate(rawLines)
        val responseSequenceIndex =
            payload.optJSONObject("current_episode")
                ?.takeIf { it.has("sequence_index") && !it.isNull("sequence_index") }
                ?.optInt("sequence_index", -1)
                ?.takeIf { it >= 0 }
                ?: sequenceIndex
        val responseEpisodeCount =
            payload.optInt("episode_count", 0).takeIf { it > 0 } ?: episodeCount
        val responseContentKind =
            payload.optString("content_kind").ifBlank { contentKind }
        val options =
            lines.mapIndexed { index, line ->
                val binding =
                    OfficialPlaybackBinding(
                        episodeToken = token,
                        sequenceIndex = responseSequenceIndex,
                        episodeCount = responseEpisodeCount,
                        contentKind = responseContentKind,
                        line = line,
                    )
                LineInfo(
                    vodId = "",
                    label =
                        line.displayLabel
                            .ifBlank { line.label }
                            .ifBlank { line.providerName }
                            .ifBlank { line.providerId }
                            .ifBlank { "懂片帝线路 ${index + 1}" },
                    url = line.url,
                    official = binding,
                )
            }
        Playback(
            lines = options,
            rawLineCount = rawLines.size,
            duplicateLineCount = rawLines.size - lines.size,
        ).also {
            OtvLog.i(
                "official playback ok raw=${it.rawLineCount} kept=${it.lines.size} " +
                    "dedup=${it.duplicateLineCount} " +
                    "providers=${it.lines.map { line -> line.official?.line?.providerDisplayName.orEmpty() }.distinct()}",
            )
        }
    }

    /**
     * Resolves one selected parse line. A ticket-expired response triggers exactly one
     * refresh through /v1/playback/resolve/{token} and one resolve-line retry.
     */
    suspend fun resolveLine(
        requestedBinding: OfficialPlaybackBinding,
        forceRefresh: Boolean = false,
    ): OfficialPlaybackLine = withContext(Dispatchers.IO) {
        if (
            DongpianPlaybackProtocol.canPlayDirectly(
                requestedBinding.line,
                forceRefresh = forceRefresh,
            )
        ) {
            return@withContext requestedBinding.line
        }
        if (!DongpianPlaybackProtocol.isParseLine(requestedBinding.line)) {
            throw DongpianAuthException(
                "当前线路没有可播放地址，请切换线路。",
                wireCode = "playback_line_url_missing",
            )
        }
        val key = cacheKey(requestedBinding)
        synchronized(officialResolvedLines) {
            if (!forceRefresh) {
                officialResolvedLines[key]?.takeIf {
                    !DongpianPlaybackProtocol.needsResolution(it)
                }?.let { cached ->
                    OtvLog.i(
                        "official resolve-line cache line=${cached.stableId} " +
                            "provider=${cached.providerDisplayName}",
                    )
                    return@withContext cached
                }
            }
        }

        val binding = officialLatestBindings[key] ?: requestedBinding
        var ticket = DongpianPlaybackProtocol.ticketOf(binding.line)
        if (ticket.isBlank()) {
            throw DongpianAuthException(
                "当前线路缺少播放凭证，请切换线路。",
                wireCode = "playback_line_ticket_missing",
            )
        }
        OtvLog.i(
            "official resolve-line start line=${binding.line.stableId} " +
                "provider=${binding.line.providerDisplayName} mode=${binding.line.resolveMode}",
        )

        val resolved =
            try {
                postResolveLine(ticket, binding.line)
            } catch (failure: DongpianAuthException) {
                if (
                    !DongpianPlaybackProtocol.isTicketExpired(
                        failure.statusCode,
                        failure.topLevelWireCode,
                    )
                ) {
                    throw failure
                }
                OtvLog.w(
                    "official resolve ticket expired line=${binding.line.stableId}; " +
                        "refreshing selection",
                )
                val refreshed = refreshBinding(binding)
                val refreshedTicket = DongpianPlaybackProtocol.ticketOf(refreshed.line)
                if (refreshedTicket.isBlank() || refreshedTicket == ticket) {
                    throw ticketRefreshExhausted()
                }
                officialLatestBindings[key] = refreshed
                ticket = refreshedTicket
                try {
                    postResolveLine(ticket, refreshed.line)
                } catch (retryFailure: DongpianAuthException) {
                    if (
                        DongpianPlaybackProtocol.isTicketExpired(
                            retryFailure.statusCode,
                            retryFailure.topLevelWireCode,
                        )
                    ) {
                        throw ticketRefreshExhausted()
                    }
                    throw retryFailure
                }
            }

        if (
            !DongpianPlaybackProtocol.isPlayableMediaLine(resolved) ||
            DongpianPlaybackProtocol.needsResolution(resolved)
        ) {
            throw DongpianAuthException(
                "播放线路返回了非视频地址，请切换线路或稍后重试。",
                wireCode = "playback_line_resolve_incomplete",
            )
        }
        officialResolvedLines[key] = resolved
        OtvLog.i(
            "official resolve-line ok line=${resolved.stableId} " +
                "provider=${resolved.providerDisplayName} host=${safeHost(resolved.url)}",
        )
        resolved
    }

    fun invalidateResolvedLine(binding: OfficialPlaybackBinding) {
        officialResolvedLines.remove(cacheKey(binding))
    }

    /** Resolved tickets and playback URLs are account-bound and must not cross sessions. */
    fun clearAccountScopedCaches() {
        officialResolvedLines.clear()
        officialLatestBindings.clear()
    }

    private fun postResolveLine(
        ticket: String,
        original: OfficialPlaybackLine,
    ): OfficialPlaybackLine {
        val json =
            playbackRequest(
                DongpianPlaybackProtocol.RESOLVE_LINE_PATH,
                method = "POST",
                body = JSONObject().put("ticket", ticket).toString(),
            )
        val payload = json.optJSONObject("data") ?: json
        val line = payload.optJSONObject("line")
            ?: throw DongpianAuthException(
                "播放线路返回异常，请切换线路。",
                wireCode = "playback_line_resolve_incomplete",
            )
        return DongpianPlaybackProtocol.mergeResolvedLine(original, line)
    }

    private fun refreshBinding(
        binding: OfficialPlaybackBinding,
    ): OfficialPlaybackBinding {
        val json =
            playbackRequest(
                DongpianPlaybackProtocol.refreshResolvePath(binding),
            )
        val payload = json.optJSONObject("data") ?: json
        val candidates =
            DongpianPlaybackProtocol.parseLines(payload.optJSONArray("line_options"))
        val line =
            DongpianPlaybackProtocol.matchingRefreshedLine(binding.line, candidates)
                ?: throw ticketRefreshExhausted()
        return binding.copy(
            sequenceIndex =
                payload.optJSONObject("current_episode")
                    ?.takeIf { it.has("sequence_index") && !it.isNull("sequence_index") }
                    ?.optInt("sequence_index", -1)
                    ?.takeIf { it >= 0 }
                    ?: binding.sequenceIndex,
            episodeCount =
                payload.optInt("episode_count", 0).takeIf { it > 0 }
                    ?: binding.episodeCount,
            contentKind =
                payload.optString("content_kind").ifBlank { binding.contentKind },
            line = line,
        ).also {
            OtvLog.i(
                "official resolve refresh ok line=${line.stableId} " +
                    "provider=${line.providerDisplayName}",
            )
        }
    }

    private fun ticketRefreshExhausted(): DongpianAuthException =
        DongpianAuthException(
            "播放凭证更新未成功，请重试或选择其他线路。",
            wireCode = "playback_resolve_ticket_refresh_exhausted",
        )

    private fun playbackRequest(
        path: String,
        method: String = "GET",
        body: String? = null,
    ): JSONObject = playbackApi(context, path, method, body)

    private fun cacheKey(binding: OfficialPlaybackBinding): String {
        val line = binding.line
        val identity =
            line.playbackSourceId.trim().ifBlank { line.id.trim() }.ifBlank {
                listOf(line.providerId, line.playFrom, line.url)
                    .filter(String::isNotBlank)
                    .joinToString(":")
            }.ifBlank { "line:${line.sourceIndex ?: 0}" }
        return "${binding.episodeToken}|$identity"
    }

    private fun safeHost(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")

    private fun parseCards(cards: JSONArray): List<VodItem> =
        cards.orEmpty().mapNotNull { raw ->
            val obj = raw as? JSONObject ?: return@mapNotNull null
            parseCard(obj)
        }.filter { it.id.removePrefix("dongpian:").isNotBlank() && it.title.isNotBlank() }
            .distinctBy(VodItem::id)

    private fun parseCard(json: JSONObject): VodItem? {
        val variant =
            json.optString("variant_id")
                .ifBlank { json.optString("selected_variant_id") }
                .ifBlank { json.optString("default_variant_id") }
                .ifBlank { json.optString("id") }
                .trim()
        val title = json.optString("title").trim()
        if (variant.isBlank() || title.isBlank()) return null
        val year = json.optInt("year", 0).takeIf { it > 0 }?.toString().orEmpty()
        val area = json.optString("area")
        val genres = strings(json.opt("genres"))
        val card = json.optJSONObject("card")
        val poster =
            firstUsablePosterUrl(
                json.optString("poster_url"),
                card?.optString("poster_url"),
                card?.optString("cover_url"),
                json.optString("carousel_url"),
                card?.optString("carousel_url"),
                json.optString("backdrop_url"),
                card?.optString("backdrop_url"),
            )
        val hero =
            firstUsablePosterUrl(
                json.optString("backdrop_url"),
                card?.optString("backdrop_url"),
                json.optString("carousel_url"),
                card?.optString("carousel_url"),
                poster,
            )
        return VodItem(
            id = "dongpian:$variant",
            sourceId = "dongpian",
            title = title,
            categoryId = categoryId(json.optString("content_kind")),
            year = year,
            area = area,
            desc = json.optString("description"),
            posterUrl = poster,
            heroUrl = hero,
            tags = genres,
            detailRef = "dongpian:$variant",
            remark = json.optString("remarks"),
            meta =
                listOf(year, area, genres.joinToString(" / "))
                    .filter(String::isNotBlank)
                    .joinToString(" / "),
            actors = strings(json.opt("actors")).joinToString(" / "),
        )
    }

    private fun categoryId(kind: String): String =
        "douban:" +
            when (kind.trim().lowercase()) {
                "tv", "series", "show", "episode" -> "tv"
                "short", "short_drama" -> "short"
                "anime", "animation" -> "anime"
                "variety" -> "variety"
                else -> "movie"
            }

    private fun strings(value: Any?): List<String> =
        when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull {
                value.optString(it).takeIf(String::isNotBlank)
            }
            is String -> listOf(value).filter(String::isNotBlank)
            else -> emptyList()
        }

    private fun JSONArray?.orEmpty(): List<Any?> =
        (0 until (this?.length() ?: 0)).map { this?.opt(it) }

    private fun parsedIndex(parent: JSONArray, target: JSONObject): Int =
        (0 until parent.length()).firstOrNull { parent.optJSONObject(it) === target } ?: 0

    private companion object {
        val CATEGORY_LABELS = listOf(
            "movie" to "电影",
            "tv" to "电视剧",
            "anime" to "国漫",
            "variety" to "综艺",
            "short" to "短剧",
        )
    }
}
