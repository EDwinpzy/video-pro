package com.dongpiandi.videopro.data

import com.dongpiandi.videopro.data.dto.DongpianHistoryUpsertPayloadDto
import com.dongpiandi.videopro.data.dto.DongpianLibraryResponseDto
import com.dongpiandi.videopro.data.dto.DongpianLibraryStatusResponseDto
import com.dongpiandi.videopro.data.dto.DongpianSyncOperationDto
import com.dongpiandi.videopro.data.dto.DongpianSyncRequestDto
import com.dongpiandi.videopro.data.dto.DongpianSyncResponseDto
import com.dongpiandi.videopro.data.model.DongpianLibraryPage
import com.dongpiandi.videopro.data.model.DongpianLibraryStatus
import com.dongpiandi.videopro.data.model.DongpianRejectedOperation
import com.dongpiandi.videopro.data.model.DongpianSyncOperation
import com.dongpiandi.videopro.data.model.DongpianSyncResult
import com.dongpiandi.videopro.data.transport.DongpianApiClient
import com.dongpiandi.videopro.data.transport.DongpianHttpMethod
import com.dongpiandi.videopro.data.transport.DongpianHttpResponse
import com.dongpiandi.videopro.data.transport.DongpianRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

interface DongpianLibraryRepository {
    suspend fun history(
        limit: Int = 24,
        cursor: String? = null,
        query: String? = null,
        kind: String? = null,
        kindState: String? = null,
    ): DongpianLibraryPage

    suspend fun favorites(
        limit: Int = 24,
        cursor: String? = null,
        query: String? = null,
        kind: String? = null,
        kindState: String? = null,
    ): DongpianLibraryPage

    suspend fun resumeStatus(variantIds: List<String>): List<DongpianLibraryStatus>

    suspend fun sync(operations: List<DongpianSyncOperation>): DongpianSyncResult
}

internal class DongpianLibraryRepositoryImpl(
    private val apiClient: DongpianApiClient,
    private val sessionCookieAccess: DongpianSessionCookieAccess,
) : DongpianLibraryRepository {
    override suspend fun history(
        limit: Int,
        cursor: String?,
        query: String?,
        kind: String?,
        kindState: String?,
    ): DongpianLibraryPage =
        libraryPage(
            path =
                libraryPath(
                    endpoint = "/v1/users/me/history",
                    filters =
                        LibraryFilters(
                            cursor = cursor,
                            query = query,
                            kind = kind,
                            kindState = kindState,
                        ),
                    limit = limit,
                    includeUpdateView = true,
                ),
        )

    override suspend fun favorites(
        limit: Int,
        cursor: String?,
        query: String?,
        kind: String?,
        kindState: String?,
    ): DongpianLibraryPage =
        libraryPage(
            path =
                libraryPath(
                    endpoint = "/v1/users/me/favorites",
                    filters =
                        LibraryFilters(
                            cursor = cursor,
                            query = query,
                            kind = kind,
                            kindState = kindState,
                        ),
                    limit = limit,
                    includeUpdateView = false,
                ),
        )

    override suspend fun resumeStatus(variantIds: List<String>): List<DongpianLibraryStatus> {
        val ids = variantIds.map(String::trim).filter(String::isNotEmpty).distinct()
        if (ids.isEmpty()) return emptyList()
        val response =
            authenticatedGet(
                "/v1/users/me/library/status?ids=${encode(ids.joinToString(","))}&view=compact",
            )
        val dto = response.decode<DongpianLibraryStatusResponseDto>()
        if (dto.objectName != null && dto.objectName != "user.library.status") {
            throw invalidResponse()
        }
        return dto.items.map { it.toDomain() }
    }

    override suspend fun sync(operations: List<DongpianSyncOperation>): DongpianSyncResult {
        if (operations.isEmpty()) return DongpianSyncResult(emptyList(), emptyList())
        val request =
            DongpianSyncRequestDto(
                ops = operations.map(DongpianSyncOperation::toDto),
            )
        val cookie = requireCookie()
        val response =
            apiClient
                .execute(
                    DongpianRequest(
                        method = DongpianHttpMethod.POST,
                        pathAndQuery = "/v1/users/me/library/sync",
                        body = DongpianJson.value.encodeToString(request),
                        cookie = cookie,
                        retryOnSignatureFailure = true,
                    ),
                ).requireSuccess()
        val dto = response.decode<DongpianSyncResponseDto>()
        if (dto.objectName != null && dto.objectName != "user.library_sync") {
            throw invalidResponse()
        }
        val result =
            DongpianSyncResult(
                acceptedOpIds = dto.acceptedOpIds,
                rejectedOps =
                    dto.rejectedOps.map {
                        DongpianRejectedOperation(
                            opId = it.opId,
                            code = it.code,
                        )
                    },
            )
        val accepted = result.acceptedOpIds.toSet()
        if (result.rejectedOps.isNotEmpty() || operations.any { it.opId !in accepted }) {
            throw DongpianException(
                kind = DongpianFailureKind.SYNC_NOT_ACCEPTED,
                message = "Dongpian did not accept every library operation.",
            )
        }
        return result
    }

    private suspend fun libraryPage(path: String): DongpianLibraryPage {
        val dto = authenticatedGet(path).decode<DongpianLibraryResponseDto>()
        val validObject =
            dto.objectName == null ||
                dto.objectName == "user.watch_history.list" ||
                dto.objectName == "user.favorite.list"
        if (!validObject) throw invalidResponse()
        return DongpianLibraryPage(
            items = dto.items.map { it.toDomain() }.filter { it.variantId.isNotBlank() && it.title.isNotBlank() },
            nextCursor = dto.nextCursor,
            totalCount = dto.meta?.totalCount,
        )
    }

    private suspend fun authenticatedGet(path: String): DongpianHttpResponse =
        apiClient
            .execute(
                DongpianRequest(
                    method = DongpianHttpMethod.GET,
                    pathAndQuery = path,
                    cookie = requireCookie(),
                ),
            ).requireSuccess()

    private suspend fun requireCookie(): String =
        sessionCookieAccess.currentCookie()?.takeIf(String::isNotBlank)
            ?: throw DongpianException(
                kind = DongpianFailureKind.UNAUTHORIZED,
                message = "A Dongpian account session is required.",
            )

    private fun libraryPath(
        endpoint: String,
        filters: LibraryFilters,
        limit: Int,
        includeUpdateView: Boolean,
    ): String {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val params =
            buildList {
                add("limit=$limit")
                if (includeUpdateView) add("update_view=text")
                filters.cursor
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { add("cursor=${encode(it)}") }
                filters.query
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { add("q=${encode(it)}") }
                filters.kind
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { add("kind=${encode(it)}") }
                filters.kindState
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { add("kind_state=${encode(it)}") }
            }
        return "$endpoint?${params.joinToString("&")}"
    }
}

private data class LibraryFilters(
    val cursor: String?,
    val query: String?,
    val kind: String?,
    val kindState: String?,
)

private fun DongpianSyncOperation.toDto(): DongpianSyncOperationDto =
    when (this) {
        is DongpianSyncOperation.HistoryUpsert ->
            DongpianSyncOperationDto(
                opId = opId,
                type = "history_upsert",
                payload =
                    DongpianHistoryUpsertPayloadDto(
                        variantId = payload.variantId,
                        title = payload.title,
                        episodeId = payload.episodeId,
                        episodeLabel = payload.episodeLabel,
                        episodeNumber = payload.episodeNumber,
                        episodeToken = payload.episodeToken,
                        positionSeconds = payload.positionSeconds.coerceAtLeast(0),
                        durationSeconds = payload.durationSeconds?.coerceAtLeast(0),
                        watchedAt = payload.watchedAt,
                        posterUrl = payload.posterUrl,
                        backdropUrl = payload.backdropUrl,
                        contentKind = payload.contentKind,
                        area = payload.area,
                        language = payload.language,
                        genres = payload.genres,
                    ),
            )
        is DongpianSyncOperation.HistoryDelete ->
            DongpianSyncOperationDto(opId, "history_delete", variantId = variantId)
        is DongpianSyncOperation.HistoryClear ->
            DongpianSyncOperationDto(opId, "history_clear")
        is DongpianSyncOperation.FavoriteUpsert ->
            DongpianSyncOperationDto(opId, "favorite_upsert", variantId = variantId)
        is DongpianSyncOperation.FavoriteDelete ->
            DongpianSyncOperationDto(opId, "favorite_delete", variantId = variantId)
        is DongpianSyncOperation.FavoriteClear ->
            DongpianSyncOperationDto(opId, "favorite_clear")
    }

private inline fun <reified T> DongpianHttpResponse.decode(): T =
    try {
        DongpianJson.value.decodeFromString<T>(body)
    } catch (failure: SerializationException) {
        throw DongpianException(
            kind = DongpianFailureKind.INVALID_RESPONSE,
            message = "Dongpian returned an invalid library response.",
            cause = failure,
        )
    }

private fun invalidResponse() =
    DongpianException(
        kind = DongpianFailureKind.INVALID_RESPONSE,
        message = "Dongpian returned an unexpected library response type.",
    )
