package com.dongpiandi.videopro.data.model

data class DongpianCatalogCard(
    val id: String,
    val variantId: String?,
    val title: String,
    val contentKind: String?,
    val year: Int?,
    val area: String?,
    val language: String?,
    val genres: List<String>,
    val actors: List<String>,
    val directors: List<String>,
    val posterUrl: String?,
    val carouselUrl: String?,
    val backdropUrl: String?,
    val remarks: String?,
    val detailPath: String?,
)

data class DongpianHomeSection(
    val id: String,
    val type: String?,
    val title: String?,
    val subtitle: String?,
    val cards: List<DongpianCatalogCard>,
)

data class DongpianHome(
    val sections: List<DongpianHomeSection>,
    val nextCursor: String?,
)

data class DongpianCatalogPage(
    val items: List<DongpianCatalogCard>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

data class DongpianEpisode(
    val id: String,
    val number: Int?,
    val title: String?,
    val token: String,
    val episodeTrack: String?,
)

data class DongpianEpisodePage(
    val items: List<DongpianEpisode>,
    val nextCursor: String?,
)

data class DongpianVariant(
    val id: String,
    val title: String?,
    val label: String?,
    val isDefault: Boolean,
)

data class DongpianDetail(
    val id: String,
    val variantId: String,
    val title: String,
    val contentKind: String?,
    val year: Int?,
    val area: String?,
    val language: String?,
    val genres: List<String>,
    val actors: List<String>,
    val directors: List<String>,
    val posterUrl: String?,
    val backdropUrl: String?,
    val description: String?,
    val remarks: String?,
    val episodes: List<DongpianEpisode>,
    val variants: List<DongpianVariant>,
    val episodeNextCursor: String?,
)

data class DongpianPlaybackLine(
    val id: String,
    val label: String,
    val providerName: String?,
    val url: String,
    val defaultPriority: Int?,
)

data class DongpianHistoryItem(
    val variantId: String,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val contentKind: String?,
    val episodeId: String?,
    val episodeLabel: String?,
    val episodeNumber: Int?,
    val episodeToken: String?,
    val positionSeconds: Long,
    val durationSeconds: Long?,
    val watchedAt: String?,
    val updatedAt: String?,
)

data class DongpianLibraryPage(
    val items: List<DongpianHistoryItem>,
    val nextCursor: String?,
    val totalCount: Int?,
)

data class DongpianResumeState(
    val variantId: String,
    val episodeId: String?,
    val episodeLabel: String?,
    val episodeNumber: Int?,
    val episodeToken: String?,
    val positionSeconds: Long,
    val durationSeconds: Long?,
)

data class DongpianLibraryStatus(
    val variantId: String,
    val favorite: Boolean,
    val resume: DongpianResumeState?,
    val updatedAt: String?,
)

data class DongpianHistoryUpsertPayload(
    val variantId: String,
    val title: String,
    val episodeId: String?,
    val episodeLabel: String?,
    val episodeNumber: Int?,
    val episodeToken: String?,
    val positionSeconds: Long,
    val durationSeconds: Long?,
    val watchedAt: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val contentKind: String?,
    val area: String?,
    val language: String?,
    val genres: List<String>,
)

sealed interface DongpianSyncOperation {
    val opId: String

    data class HistoryUpsert(
        override val opId: String,
        val payload: DongpianHistoryUpsertPayload,
    ) : DongpianSyncOperation

    data class HistoryDelete(
        override val opId: String,
        val variantId: String,
    ) : DongpianSyncOperation

    data class HistoryClear(
        override val opId: String,
    ) : DongpianSyncOperation

    data class FavoriteUpsert(
        override val opId: String,
        val variantId: String,
    ) : DongpianSyncOperation

    data class FavoriteDelete(
        override val opId: String,
        val variantId: String,
    ) : DongpianSyncOperation

    data class FavoriteClear(
        override val opId: String,
    ) : DongpianSyncOperation
}

data class DongpianRejectedOperation(
    val opId: String?,
    val code: String?,
)

data class DongpianSyncResult(
    val acceptedOpIds: List<String>,
    val rejectedOps: List<DongpianRejectedOperation>,
)

data class DongpianPlaylistSummary(
    val id: String,
    val title: String,
    val description: String?,
    val itemCount: Int?,
    val coverUrl: String?,
)

data class DongpianPlaylistPage(
    val items: List<DongpianPlaylistSummary>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

data class DongpianUser(
    val id: String,
    val displayName: String,
)
