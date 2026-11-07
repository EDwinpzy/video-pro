package com.dongpiandi.videopro.data

import com.dongpiandi.videopro.data.dto.DongpianBrowseResponseDto
import com.dongpiandi.videopro.data.dto.DongpianDetailResponseDto
import com.dongpiandi.videopro.data.dto.DongpianEpisodesResponseDto
import com.dongpiandi.videopro.data.dto.DongpianHomeResponseDto
import com.dongpiandi.videopro.data.dto.DongpianPlaylistPageDto
import com.dongpiandi.videopro.data.model.DongpianCatalogCard
import com.dongpiandi.videopro.data.model.DongpianCatalogPage
import com.dongpiandi.videopro.data.model.DongpianDetail
import com.dongpiandi.videopro.data.model.DongpianEpisodePage
import com.dongpiandi.videopro.data.model.DongpianHome
import com.dongpiandi.videopro.data.model.DongpianPlaylistPage
import com.dongpiandi.videopro.data.transport.DongpianApiClient
import com.dongpiandi.videopro.data.transport.DongpianHttpMethod
import com.dongpiandi.videopro.data.transport.DongpianRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import java.net.URLEncoder

interface DongpianCatalogRepository {
    suspend fun loadHome(): DongpianHome

    suspend fun browse(
        page: Int,
        limit: Int = 20,
    ): DongpianCatalogPage

    suspend fun search(
        query: String,
        page: Int,
        limit: Int = 20,
    ): DongpianCatalogPage

    suspend fun loadDetail(variantId: String): DongpianDetail

    suspend fun loadEpisodes(
        variantId: String,
        offset: Int = 0,
        limit: Int = 50,
    ): DongpianEpisodePage

    suspend fun loadPlaylists(
        cursor: String? = null,
        limit: Int = 20,
    ): DongpianPlaylistPage
}

class DongpianCatalogRepositoryImpl(
    private val apiClient: DongpianApiClient,
) : DongpianCatalogRepository {
    override suspend fun loadHome(): DongpianHome {
        val dto = apiClient.getJson<DongpianHomeResponseDto>("/v1/feed/home", "home.feed")
        return DongpianHome(
            sections =
                dto.sections.map { section ->
                    com.dongpiandi.videopro.data.model.DongpianHomeSection(
                        id = section.id,
                        type = section.type,
                        title = section.title,
                        subtitle = section.subtitle,
                        cards = section.cards.mapNotNull { it.toDomain().takeIf(DongpianCatalogCardValidity::isValid) },
                    )
                },
            nextCursor = dto.nextCursor,
        )
    }

    override suspend fun browse(
        page: Int,
        limit: Int,
    ): DongpianCatalogPage {
        require(page >= 1) { "page must be positive" }
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return catalogPage("/v1/browse/catalog?page=$page&limit=$limit")
    }

    override suspend fun search(
        query: String,
        page: Int,
        limit: Int,
    ): DongpianCatalogPage {
        val normalized = query.trim()
        if (normalized.isEmpty()) return DongpianCatalogPage(emptyList(), null, false)
        require(page >= 1) { "page must be positive" }
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return catalogPage(
            "/v1/browse/catalog?q=${encode(normalized)}&page=$page&limit=$limit",
        )
    }

    override suspend fun loadDetail(variantId: String): DongpianDetail {
        val id = variantId.trim()
        require(id.isNotEmpty()) { "variantId must not be blank" }
        val dto =
            apiClient.getJson<DongpianDetailResponseDto>(
                path = "/v1/catalog/${encodePathSegment(id)}",
                expectedObject = "catalog.detail",
            )
        return dto.toDomain()
    }

    override suspend fun loadEpisodes(
        variantId: String,
        offset: Int,
        limit: Int,
    ): DongpianEpisodePage {
        val id = variantId.trim()
        require(id.isNotEmpty()) { "variantId must not be blank" }
        require(offset >= 0) { "offset must not be negative" }
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val dto =
            apiClient.getJson<DongpianEpisodesResponseDto>(
                path = "/v1/catalog/${encodePathSegment(id)}/episodes?offset=$offset&limit=$limit",
                expectedObject = "catalog.episodes",
            )
        return DongpianEpisodePage(
            items = dto.episodes.mapNotNull { it.toDomainOrNull() },
            nextCursor = dto.episodePagination?.nextCursor,
        )
    }

    override suspend fun loadPlaylists(
        cursor: String?,
        limit: Int,
    ): DongpianPlaylistPage {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val query =
            buildString {
                append("/v1/playlists/explore?limit=")
                append(limit)
                cursor?.trim()?.takeIf(String::isNotEmpty)?.let {
                    append("&cursor=")
                    append(encode(it))
                }
            }
        val dto = apiClient.getJson<DongpianPlaylistPageDto>(query, "user_playlist.explore")
        return DongpianPlaylistPage(
            items = dto.data.map { it.toDomain() },
            nextCursor = dto.nextCursor,
            hasMore = dto.hasMore == true || dto.nextCursor != null,
        )
    }

    private suspend fun catalogPage(pathAndQuery: String): DongpianCatalogPage {
        val dto = apiClient.getJson<DongpianBrowseResponseDto>(pathAndQuery, "catalog.browse")
        val nextCursor = dto.pagination?.nextCursor ?: dto.nextCursor
        return DongpianCatalogPage(
            items = dto.cards.map { it.toDomain() }.filter(DongpianCatalogCardValidity::isValid),
            nextCursor = nextCursor,
            hasMore = dto.pagination?.hasMore ?: (nextCursor != null),
        )
    }
}

internal suspend inline fun <reified T> DongpianApiClient.getJson(
    path: String,
    expectedObject: String,
): T {
    val response = execute(DongpianRequest(DongpianHttpMethod.GET, path)).requireSuccess()
    val value =
        try {
            DongpianJson.value.decodeFromString<T>(response.body)
        } catch (failure: SerializationException) {
            throw DongpianException(
                kind = DongpianFailureKind.INVALID_RESPONSE,
                message = "Dongpian returned an invalid response.",
                cause = failure,
            )
        }
    val actualObject =
        when (value) {
            is DongpianHomeResponseDto -> value.objectName
            is DongpianBrowseResponseDto -> value.objectName
            is DongpianDetailResponseDto -> value.objectName
            is DongpianEpisodesResponseDto -> value.objectName
            is DongpianPlaylistPageDto -> value.objectName
            else -> null
        }
    if (actualObject != expectedObject) {
        throw DongpianException(
            kind = DongpianFailureKind.INVALID_RESPONSE,
            message = "Dongpian returned an unexpected response type.",
        )
    }
    return value
}

internal fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

internal fun encodePathSegment(value: String): String = encode(value).replace("+", "%20")

internal object DongpianCatalogCardValidity {
    fun isValid(card: DongpianCatalogCard): Boolean = card.id.isNotBlank() && card.title.isNotBlank()
}
