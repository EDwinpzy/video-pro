package com.dongpiandi.videopro.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class DongpianCardDto(
    val id: String = "",
    @SerialName("variant_id") val variantId: String? = null,
    @SerialName("selected_variant_id") val selectedVariantId: String? = null,
    @SerialName("default_variant_id") val defaultVariantId: String? = null,
    val title: String = "",
    @SerialName("content_kind") val contentKind: String? = null,
    val year: Int? = null,
    val area: String? = null,
    val language: String? = null,
    val genres: JsonElement? = null,
    val actors: JsonElement? = null,
    val directors: JsonElement? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("carousel_url") val carouselUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    val remarks: String? = null,
    @SerialName("detail_url") val detailUrl: String? = null,
)

@Serializable
internal data class DongpianHomeSectionDto(
    val id: String = "",
    val type: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val cards: List<DongpianCardDto> = emptyList(),
)

@Serializable
internal data class DongpianHomeResponseDto(
    @SerialName("object") val objectName: String? = null,
    val sections: List<DongpianHomeSectionDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
internal data class DongpianPaginationDto(
    val page: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    @SerialName("total_count") val totalCount: Int? = null,
    @SerialName("has_more") val hasMore: Boolean? = null,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
internal data class DongpianBrowseResponseDto(
    @SerialName("object") val objectName: String? = null,
    val cards: List<DongpianCardDto> = emptyList(),
    val pagination: DongpianPaginationDto? = null,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
internal data class DongpianEpisodeDto(
    val id: String = "",
    val number: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    val title: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val token: String = "",
    @SerialName("episode_track") val episodeTrack: String? = null,
)

@Serializable
internal data class DongpianVariantDto(
    val id: String = "",
    @SerialName("variant_id") val variantId: String? = null,
    val title: String? = null,
    val label: String? = null,
    val name: String? = null,
    val selected: Boolean? = null,
    @SerialName("is_default") val isDefault: Boolean? = null,
    @SerialName("default") val defaultValue: Boolean? = null,
)

@Serializable
internal data class DongpianEpisodePaginationDto(
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean? = null,
    val offset: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
)

@Serializable
internal data class DongpianDetailResponseDto(
    @SerialName("object") val objectName: String? = null,
    val id: String = "",
    @SerialName("variant_id") val variantId: String? = null,
    @SerialName("selected_variant_id") val selectedVariantId: String? = null,
    @SerialName("default_variant_id") val defaultVariantId: String? = null,
    val title: String = "",
    @SerialName("content_kind") val contentKind: String? = null,
    val year: Int? = null,
    val area: String? = null,
    val language: String? = null,
    val genres: JsonElement? = null,
    val actors: JsonElement? = null,
    val directors: JsonElement? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    val description: String? = null,
    val remarks: String? = null,
    val episodes: List<DongpianEpisodeDto> = emptyList(),
    val variants: List<DongpianVariantDto> = emptyList(),
    @SerialName("episode_pagination") val episodePagination: DongpianEpisodePaginationDto? = null,
)

@Serializable
internal data class DongpianEpisodesResponseDto(
    @SerialName("object") val objectName: String? = null,
    val episodes: List<DongpianEpisodeDto> = emptyList(),
    @SerialName("episode_pagination") val episodePagination: DongpianEpisodePaginationDto? = null,
)

@Serializable
internal data class DongpianPlaybackLineDto(
    val id: String = "",
    val label: String? = null,
    @SerialName("display_label") val displayLabel: String? = null,
    @SerialName("provider_name") val providerName: String? = null,
    val url: String = "",
    val resolved: Boolean = false,
    @SerialName("resolve_required") val resolveRequired: Boolean = true,
    @SerialName("resolve_mode") val resolveMode: String? = null,
    @SerialName("default_priority") val defaultPriority: Int? = null,
)

@Serializable
internal data class DongpianPlaybackResponseDto(
    @SerialName("object") val objectName: String? = null,
    @SerialName("line_options") val lineOptions: List<DongpianPlaybackLineDto> = emptyList(),
)

@Serializable
internal data class DongpianHistoryItemDto(
    val id: String? = null,
    @SerialName("variant_id") val variantId: String = "",
    val title: String = "",
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    @SerialName("content_kind") val contentKind: String? = null,
    @SerialName("episode_id") val episodeId: String? = null,
    @SerialName("episode_label") val episodeLabel: String? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("episode_token") val episodeToken: String? = null,
    @SerialName("position_seconds") val positionSeconds: Long = 0,
    @SerialName("duration_seconds") val durationSeconds: Long? = null,
    @SerialName("watched_at") val watchedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
internal data class DongpianLibraryMetaDto(
    val limit: Int? = null,
    @SerialName("returned_count") val returnedCount: Int? = null,
    @SerialName("total_count") val totalCount: Int? = null,
)

@Serializable
internal data class DongpianLibraryResponseDto(
    @SerialName("object") val objectName: String? = null,
    val items: List<DongpianHistoryItemDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    val meta: DongpianLibraryMetaDto? = null,
)

@Serializable
internal data class DongpianResumeDto(
    @SerialName("episode_id") val episodeId: String? = null,
    @SerialName("episode_label") val episodeLabel: String? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("episode_token") val episodeToken: String? = null,
    @SerialName("position_seconds") val positionSeconds: Long = 0,
    @SerialName("duration_seconds") val durationSeconds: Long? = null,
)

@Serializable
internal data class DongpianLibraryStatusItemDto(
    @SerialName("variant_id") val variantId: String = "",
    val favorite: Boolean = false,
    val resume: DongpianResumeDto? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
internal data class DongpianLibraryStatusResponseDto(
    @SerialName("object") val objectName: String? = null,
    val items: List<DongpianLibraryStatusItemDto> = emptyList(),
)

@Serializable
internal data class DongpianHistoryUpsertPayloadDto(
    @SerialName("variant_id") val variantId: String,
    val title: String,
    @SerialName("episode_id") val episodeId: String? = null,
    @SerialName("episode_label") val episodeLabel: String? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("episode_token") val episodeToken: String? = null,
    @SerialName("position_seconds") val positionSeconds: Long,
    @SerialName("duration_seconds") val durationSeconds: Long? = null,
    @SerialName("watched_at") val watchedAt: String,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    @SerialName("content_kind") val contentKind: String? = null,
    val area: String? = null,
    val language: String? = null,
    val genres: List<String> = emptyList(),
)

@Serializable
internal data class DongpianSyncOperationDto(
    @SerialName("op_id") val opId: String,
    val type: String,
    val payload: DongpianHistoryUpsertPayloadDto? = null,
    @SerialName("variant_id") val variantId: String? = null,
)

@Serializable
internal data class DongpianSyncRequestDto(
    val ops: List<DongpianSyncOperationDto>,
)

@Serializable
internal data class DongpianRejectedOperationDto(
    @SerialName("op_id") val opId: String? = null,
    val code: String? = null,
    val message: String? = null,
)

@Serializable
internal data class DongpianSyncResponseDto(
    @SerialName("object") val objectName: String? = null,
    val state: String? = null,
    val queued: Int? = null,
    @SerialName("accepted_op_ids") val acceptedOpIds: List<String> = emptyList(),
    @SerialName("rejected_ops") val rejectedOps: List<DongpianRejectedOperationDto> = emptyList(),
)

@Serializable
internal data class DongpianUserDto(
    val id: String = "",
    @SerialName("display_name") val displayName: String? = null,
    val name: String? = null,
    val username: String? = null,
)

@Serializable
internal data class DongpianLoginRequestDto(
    val account: String,
    val password: String,
)

@Serializable
internal data class DongpianPlaylistCoverDto(
    val url: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
)

@Serializable
internal data class DongpianPlaylistItemDto(
    val id: String = "",
    val title: String = "",
    val description: String? = null,
    @SerialName("item_count") val itemCount: Int? = null,
    val cover: JsonElement? = null,
)

@Serializable
internal data class DongpianPlaylistPageDto(
    @SerialName("object") val objectName: String? = null,
    val data: List<DongpianPlaylistItemDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean? = null,
)
