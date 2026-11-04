package com.qiubo.optimaltv.data.source

import android.content.Context
import androidx.room.withTransaction
import com.qiubo.optimaltv.OtvLog
import com.qiubo.optimaltv.auth.AccountWriteGate
import com.qiubo.optimaltv.auth.AccountSessionKey
import com.qiubo.optimaltv.auth.AccountSessionWriteGate
import com.qiubo.optimaltv.auth.DongpianAuthClient
import com.qiubo.optimaltv.auth.DongpianAuthException
import com.qiubo.optimaltv.auth.DongpianAuthSession
import com.qiubo.optimaltv.auth.DongpianAuthSessionSnapshot
import com.qiubo.optimaltv.auth.StaleAccountSessionException
import com.qiubo.optimaltv.data.db.AppDb
import com.qiubo.optimaltv.data.db.FavoriteEntity
import com.qiubo.optimaltv.data.db.HistoryEntity
import com.qiubo.optimaltv.data.db.ProgressEntity
import com.qiubo.optimaltv.data.model.VodItem
import com.qiubo.optimaltv.data.model.firstUsablePosterUrl
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class OfficialResume(
    val episodeNumber: Int?,
    val episodeToken: String?,
    val positionSeconds: Long,
    val durationSeconds: Long?,
    val episodeLabel: String?,
)

enum class OfficialLibrarySyncPhase {
    IDLE,
    SYNCING,
    READY,
    FAILED,
}

data class OfficialLibrarySyncState(
    val phase: OfficialLibrarySyncPhase = OfficialLibrarySyncPhase.IDLE,
    val favoriteCount: Int = 0,
    val historyCount: Int = 0,
    val syncedAt: Long = 0L,
    val message: String? = null,
)

data class OfficialLibrarySnapshot(
    val userId: String,
    val favorites: Int,
    val history: Int,
    val syncedAt: Long,
    val sessionRevision: Long? = null,
)

/** Mirrors the verified Dongpiandi library endpoints into the legacy Room-backed screens. */
class DongpianOfficialLibraryClient internal constructor(
    private val context: Context,
    private val currentSnapshot: () -> DongpianAuthSessionSnapshot? = {
        DongpianAuthClient.currentSessionSnapshot(context)
    },
    private val onAccountChanged: suspend () -> Unit = {},
) {
    private val scopePreferences by lazy {
        context.getSharedPreferences(LIBRARY_SCOPE_PREFS, Context.MODE_PRIVATE)
    }
    private val syncMutex = Mutex()
    private val historySyncLock = Any()
    private val accountWrites = AccountWriteGate(::currentUserId)
    private val sessionWrites =
        AccountSessionWriteGate {
            currentSnapshot()?.let {
                AccountSessionKey(it.revision, it.session.userId)
            }
        }
    private val _syncState = MutableStateFlow(OfficialLibrarySyncState())
    val syncState: StateFlow<OfficialLibrarySyncState> = _syncState.asStateFlow()

    @Volatile
    private var lastSnapshot: OfficialLibrarySnapshot? = null
    private var lastHistorySyncKey: String? = null
    private var lastHistorySyncAt: Long = 0L
    private var libraryRevisionLoaded = false

    /** Prepare only local ownership before exposing Room data; network sync is independent. */
    suspend fun prepareLocalAccount(db: AppDb, expectedRevision: Long) = syncMutex.withLock {
        val snapshot = currentSnapshot()
            ?: throw DongpianAuthException("登录已失效，请重新登录。", unauthorized = true)
        val key = AccountSessionKey(snapshot.revision, snapshot.session.userId)
        if (snapshot.revision != expectedRevision) throw StaleAccountSessionException()
        clearLocalDataIfAccountChanged(db, snapshot, key)
        val installed = sessionWrites.forCurrentSession(key) {
            withContext(Dispatchers.IO) {
                scopePreferences.edit()
                    .putString(KEY_OWNER_USER_ID, snapshot.session.userId)
                    .putLong(KEY_OWNER_REVISION, snapshot.revision)
                    .commit()
                sessionWrites.requireCurrent(key)
            }
        }
        if (installed == null) throw StaleAccountSessionException()
    }

    suspend fun syncToRoom(
        db: AppDb,
        force: Boolean = false,
    ): OfficialLibrarySnapshot =
        syncMutex.withLock {
            val sessionSnapshot =
                currentSnapshot()
                    ?: throw DongpianAuthException("登录已失效，请重新登录。", unauthorized = true)
            val session = sessionSnapshot.session
            val sessionKey = AccountSessionKey(sessionSnapshot.revision, session.userId)
            val cached = lastSnapshot
            if (
                !force &&
                cached != null &&
                cached.userId == session.userId &&
                cached.sessionRevision == sessionSnapshot.revision &&
                System.currentTimeMillis() - cached.syncedAt < SYNC_REUSE_WINDOW_MS
            ) {
                return@withLock cached
            }

            _syncState.value =
                _syncState.value.copy(
                    phase = OfficialLibrarySyncPhase.SYNCING,
                    message = null,
                )
            try {
                clearLocalDataIfAccountChanged(db, sessionSnapshot, sessionKey)
                val (favorites, history) =
                    withContext(Dispatchers.IO) {
                        coroutineScope {
                            val favoriteJob = async { favorites(expectedUserId = session.userId) }
                            val historyJob = async { history(expectedUserId = session.userId) }
                            favoriteJob.await() to historyJob.await()
                        }
                    }
                val installed =
                    sessionWrites.forCurrentSession(sessionKey) {
                        withContext(Dispatchers.IO) {
                            db.withTransaction {
                                db.vodDao().clearFavorites()
                                favorites.forEach { db.vodDao().addFavorite(it) }
                                db.vodDao().clearHistory()
                                history.forEach { db.vodDao().upsertHistory(it) }
                                sessionWrites.requireCurrent(sessionKey)
                            }
                            scopePreferences.edit()
                                .putString(KEY_OWNER_USER_ID, session.userId)
                                .putLong(KEY_OWNER_REVISION, sessionSnapshot.revision)
                                .commit()
                            sessionWrites.requireCurrent(sessionKey)
                        }
                    }
                if (installed == null) {
                    throw DongpianAuthException("账号已切换，已停止旧会话写入。", statusCode = 409)
                }
                val snapshot =
                    OfficialLibrarySnapshot(
                        userId = session.userId,
                        favorites = favorites.size,
                        history = history.size,
                        syncedAt = System.currentTimeMillis(),
                        sessionRevision = sessionSnapshot.revision,
                    )
                lastSnapshot = snapshot
                _syncState.value =
                    OfficialLibrarySyncState(
                        phase = OfficialLibrarySyncPhase.READY,
                        favoriteCount = snapshot.favorites,
                        historyCount = snapshot.history,
                        syncedAt = snapshot.syncedAt,
                    )
                OtvLog.i(
                    "official library read favorites=${snapshot.favorites} history=${snapshot.history}",
                )
                snapshot
            } catch (stale: StaleAccountSessionException) {
                throw DongpianAuthException("账号已切换，已停止旧会话写入。", statusCode = 409)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _syncState.value =
                    _syncState.value.copy(
                        phase = OfficialLibrarySyncPhase.FAILED,
                        message = failure.message ?: "收藏与历史同步失败",
                    )
                throw failure
            }
        }

    fun favorites(
        limit: Int = 100,
        expectedUserId: String,
    ): List<FavoriteEntity> {
        if (!DongpianAuthClient.isSignedIn(context)) return emptyList()
        val result = linkedMapOf<String, FavoriteEntity>()
        var cursor: String? = null
        var pages = 0
        var total = -1
        val seenCursors = hashSetOf<String>()
        while (pages < MAX_LIBRARY_PAGES) {
            val json =
                DongpianAuthClient.apiJsonForUser(
                    context,
                    officialLibraryPagePath(
                        basePath = "/v1/users/me/favorites",
                        limit = limit,
                        cursor = cursor,
                    ),
                    expectedUserId = expectedUserId,
                )
            val items = json.optJSONArray("items") ?: JSONArray()
            val meta = json.optJSONObject("meta")
            if (total < 0) total = meta?.optInt("total_count", -1) ?: -1
            pages += 1
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("variant_id").trim()
                val title = item.optString("title").trim()
                if (id.isBlank() || title.isBlank()) continue
                val entity =
                    FavoriteEntity(
                        vodId = "dongpian:$id",
                        title = title,
                        posterUrl = officialPosterUrl(item),
                        createdAt = parseTime(item.optString("updated_at")),
                    )
                if (entity.vodId !in result) result[entity.vodId] = entity
            }
            val next = json.optString("next_cursor").trim()
            if (items.length() == 0 || next.isBlank() || next == cursor || !seenCursors.add(next)) {
                break
            }
            cursor = next
        }
        OtvLog.i(
            "official favorites response items=${result.size} pages=$pages total=$total",
        )
        return result.values.toList()
    }

    fun history(
        limit: Int = 100,
        expectedUserId: String,
    ): List<HistoryEntity> {
        if (!DongpianAuthClient.isSignedIn(context)) return emptyList()
        val result = linkedMapOf<String, HistoryEntity>()
        var cursor: String? = null
        var pages = 0
        var total = -1
        val seenCursors = hashSetOf<String>()
        while (pages < MAX_LIBRARY_PAGES) {
            val json =
                DongpianAuthClient.apiJsonForUser(
                    context,
                    officialLibraryPagePath(
                        basePath = "/v1/users/me/history",
                        limit = limit,
                        cursor = cursor,
                        extraParams = mapOf("update_view" to "text"),
                    ),
                    expectedUserId = expectedUserId,
                )
            val items = json.optJSONArray("items") ?: JSONArray()
            val meta = json.optJSONObject("meta")
            if (total < 0) total = meta?.optInt("total_count", -1) ?: -1
            pages += 1
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("variant_id").trim()
                val title = item.optString("title").trim()
                if (id.isBlank() || title.isBlank()) continue
                val entity =
                    HistoryEntity(
                        vodId = "dongpian:$id",
                        title = title,
                        posterUrl = officialPosterUrl(item),
                        epIndex = ((item.optInt("episode_number", 1) - 1).coerceAtLeast(0)),
                        positionMs = item.optLong("position_seconds", 0L) * 1000L,
                        durationMs = item.optLong("duration_seconds", 0L) * 1000L,
                        updatedAt = parseTime(item.optString("updated_at")),
                    )
                if (entity.vodId !in result) result[entity.vodId] = entity
            }
            val next = json.optString("next_cursor").trim()
            if (items.length() == 0 || next.isBlank() || next == cursor || !seenCursors.add(next)) {
                break
            }
            cursor = next
        }
        val items = result.values.toList()
        OtvLog.i(
            "official history response items=${items.size} pages=$pages total=$total top=" +
                items.take(3).joinToString { item ->
                    "${item.title}#${item.epIndex + 1}@${item.positionMs / 1000L}s"
                },
        )
        return items
    }

    fun resume(item: VodItem): OfficialResume? {
        val variantId = item.detailRef.removePrefix("dongpian:")
        if (variantId.isBlank() || !DongpianAuthClient.isSignedIn(context)) return null
        val json =
            DongpianAuthClient.apiJson(
                context,
                "/v1/users/me/library/status?ids=${encode(variantId)}&view=compact",
            )
        val status = json.optJSONArray("items")?.optJSONObject(0) ?: return null
        val resume = status.optJSONObject("resume") ?: return null
        return OfficialResume(
            episodeNumber = resume.optInt("episode_number", 0).takeIf { it > 0 },
            episodeToken = resume.optString("episode_token").takeIf(String::isNotBlank),
            positionSeconds = resume.optLong("position_seconds", 0L),
            durationSeconds = resume.optLong("duration_seconds", 0L).takeIf { it > 0 },
            episodeLabel = resume.optString("episode_label").takeIf(String::isNotBlank),
        )
    }

    suspend fun seedResume(
        db: AppDb,
        item: VodItem,
        resume: OfficialResume,
    ) {
        val episodeIndex =
            item.episodes.indexOfFirst { episode ->
                resume.episodeToken != null && episode.url == resume.episodeToken
            }.takeIf { it >= 0 }
                ?: resume.episodeNumber?.minus(1)?.takeIf { it in item.episodes.indices }
                ?: return
        db.vodDao().upsertProgress(
            ProgressEntity(
                vodKey = if (item.episodes.size > 1) "${item.id}-e${episodeIndex + 1}" else item.id,
                vodId = item.id,
                epIndex = episodeIndex,
                positionMs = resume.positionSeconds * 1000L,
                durationMs = (resume.durationSeconds ?: 0L) * 1000L,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun syncHistory(
        item: VodItem,
        episodeIndex: Int,
        positionMs: Long,
        durationMs: Long,
        force: Boolean = false,
        expectedUserId: String,
    ) {
        val variantId = item.detailRef.removePrefix("dongpian:")
        if (variantId.isBlank() || !DongpianAuthClient.isSignedIn(context)) return
        val syncKey = "$variantId:$episodeIndex"
        val startedAt = System.currentTimeMillis()
        synchronized(historySyncLock) {
            if (
                !force &&
                syncKey == lastHistorySyncKey &&
                startedAt - lastHistorySyncAt < HISTORY_SYNC_INTERVAL_MS
            ) {
                return
            }
            lastHistorySyncKey = syncKey
            lastHistorySyncAt = startedAt
        }
        val payload =
            officialHistoryPayload(
                item = item,
                episodeIndex = episodeIndex,
                positionMs = positionMs,
                durationMs = durationMs,
                watchedAt = Instant.now().toString(),
            )
        OtvLog.i(
            "official history start id=$variantId ep=${episodeIndex + 1} " +
                "position=${(positionMs / 1000L).coerceAtLeast(0)}s",
        )
        try {
            sync("history_upsert", payload, variantId, expectedUserId)
            OtvLog.i("official history ok id=$variantId ep=${episodeIndex + 1}")
        } catch (cancelled: CancellationException) {
            synchronized(historySyncLock) {
                if (lastHistorySyncKey == syncKey && lastHistorySyncAt == startedAt) {
                    lastHistorySyncAt = 0L
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            synchronized(historySyncLock) {
                if (lastHistorySyncKey == syncKey && lastHistorySyncAt == startedAt) {
                    lastHistorySyncAt = 0L
                }
            }
            throw failure
        }
    }

    fun syncFavorite(
        item: VodItem,
        favorite: Boolean,
        expectedUserId: String,
    ) {
        val id = item.detailRef.removePrefix("dongpian:")
        if (id.isBlank() || !DongpianAuthClient.isSignedIn(context)) return
        OtvLog.i("official favorite start id=$id value=$favorite")
        val payload = if (favorite) officialFavoritePayload(item, id) else null
        sync(
            type = if (favorite) "favorite_upsert" else "favorite_delete",
            payload = payload,
            variantId = id,
            expectedUserId = expectedUserId,
        )
        OtvLog.i("official favorite ok id=$id value=$favorite")
    }

    fun currentUserId(): String? =
        runCatching { DongpianAuthClient.currentSession(context)?.userId }.getOrNull()

    suspend fun <T> writeForCurrentOwner(
        expectedUserId: String,
        block: suspend () -> T,
    ): T? = accountWrites.forCurrentOwner(expectedUserId, block)

    private suspend fun clearLocalDataIfAccountChanged(
        db: AppDb,
        snapshot: DongpianAuthSessionSnapshot,
        sessionKey: AccountSessionKey,
    ) {
        val previousUserId =
            scopePreferences.getString(KEY_OWNER_USER_ID, null).orEmpty().trim()
        val previousRevision = scopePreferences.getLong(KEY_OWNER_REVISION, -1L)
        // Unowned legacy rows must not be exposed as a signed-in account's cache.
        val ownerChanged = previousUserId != snapshot.session.userId
        val revisionChanged =
            libraryRevisionLoaded &&
                previousRevision >= 0L &&
                previousRevision != snapshot.revision
        libraryRevisionLoaded = true
        if (!ownerChanged && !revisionChanged) return
        val cleared =
            sessionWrites.forCurrentSession(sessionKey) {
                withContext(Dispatchers.IO) {
                    db.withTransaction {
                        db.vodDao().clearFavorites()
                        db.vodDao().clearHistory()
                        db.vodDao().clearAllProgress()
                        db.lastGoodSourceDao().clearAll()
                        sessionWrites.requireCurrent(sessionKey)
                    }
                }
            }
        if (cleared == null) {
            throw DongpianAuthException("账号已切换，已停止旧会话清理。", statusCode = 409)
        }
        lastSnapshot = null
        _syncState.value = OfficialLibrarySyncState()
        synchronized(historySyncLock) {
            lastHistorySyncKey = null
            lastHistorySyncAt = 0L
        }
        onAccountChanged()
        OtvLog.i("official library account changed; cleared account-scoped local state")
    }

    private fun sync(
        type: String,
        payload: JSONObject?,
        variantId: String,
        expectedUserId: String,
    ) {
        val opId = UUID.randomUUID().toString()
        val op =
            JSONObject()
                .put("op_id", opId)
                .put("type", type)
                .put("variant_id", variantId)
                .apply { payload?.let { put("payload", it) } }
        val body = JSONObject().put("ops", JSONArray().put(op)).toString()
        val response =
            DongpianAuthClient.apiJsonForUser(
                context,
                "/v1/users/me/library/sync",
                method = "POST",
                body = body,
                expectedUserId = expectedUserId,
            )
        val accepted = response.optJSONArray("accepted_op_ids") ?: JSONArray()
        val acceptedIds = (0 until accepted.length()).map { accepted.optString(it) }
        if (opId !in acceptedIds) {
            OtvLog.w("official sync rejected type=$type")
            error("官方进度同步未确认操作 $type")
        }
    }

    private fun parseTime(value: String): Long =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(System.currentTimeMillis())

    private fun officialPosterUrl(item: JSONObject): String {
        val card = item.optJSONObject("card")
        return firstUsablePosterUrl(
            item.optString("poster_url"),
            item.optString("cover_url"),
            item.optString("carousel_url"),
            card?.optString("poster_url"),
            card?.optString("cover_url"),
            card?.optString("carousel_url"),
            item.optString("backdrop_url"),
            card?.optString("backdrop_url"),
        )
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val LIBRARY_SCOPE_PREFS = "video_pro_library_scope"
        const val KEY_OWNER_USER_ID = "owner_user_id"
        const val KEY_OWNER_REVISION = "owner_session_revision"
        const val SYNC_REUSE_WINDOW_MS = 10_000L
        const val MAX_LIBRARY_PAGES = 20
        const val HISTORY_SYNC_INTERVAL_MS = 45_000L
    }
}

internal fun officialLibraryPagePath(
    basePath: String,
    limit: Int,
    cursor: String?,
    extraParams: Map<String, String> = emptyMap(),
): String {
    val params = linkedMapOf<String, String>()
    params["limit"] = limit.coerceIn(1, 100).toString()
    extraParams.forEach { (key, value) -> params[key] = value }
    cursor?.takeIf(String::isNotBlank)?.let { params["cursor"] = it }
    return params.entries.joinToString(
        separator = "&",
        prefix = "$basePath?",
    ) { (key, value) -> "$key=${encodeQueryValue(value)}" }
}

private fun encodeQueryValue(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal fun officialHistoryPayload(
    item: VodItem,
    episodeIndex: Int,
    positionMs: Long,
    durationMs: Long,
    watchedAt: String,
): JSONObject {
    val episode = item.episodes.getOrNull(episodeIndex)
    return JSONObject()
        .put("variant_id", item.detailRef.removePrefix("dongpian:"))
        .put("title", item.title)
        .put("episode_id", episode?.name.orEmpty())
        .put("episode_label", episode?.name.orEmpty())
        .put("episode_number", episodeIndex + 1)
        .put("episode_token", episode?.url.orEmpty())
        .put("position_seconds", (positionMs / 1000L).coerceAtLeast(0))
        .put("duration_seconds", (durationMs / 1000L).coerceAtLeast(0))
        .put("watched_at", watchedAt)
        .put("poster_url", item.posterUrl)
        .put("backdrop_url", item.heroUrl)
        .put("content_kind", officialContentKind(item.categoryId))
        .put("area", item.area)
        .put("language", "")
        .put("genres", JSONArray(item.tags))
}

internal fun officialFavoritePayload(
    item: VodItem,
    variantId: String,
): JSONObject {
    val kind = officialContentKind(item.categoryId)
    val card =
        JSONObject()
            .put("id", variantId)
            .put("variant_id", variantId)
            .put("title", item.title)
            .put("poster_url", item.posterUrl)
            .put("content_kind", kind)
            .put("remarks", item.remark)
            .put("area", item.area)
            .put("language", "")
            .put("genres", JSONArray(item.tags))
    return JSONObject()
        .put("variant_id", variantId)
        .put("title", item.title)
        .put("poster_url", item.posterUrl)
        .put("content_kind", kind)
        .apply {
            item.year.toIntOrNull()?.let { put("year", it) }
            put("card", card)
        }
}

internal fun officialContentKind(categoryId: String): String =
    when (categoryId.substringAfterLast(':')) {
        "tv" -> "series"
        "short" -> "short_drama"
        else -> categoryId.substringAfterLast(':')
    }
