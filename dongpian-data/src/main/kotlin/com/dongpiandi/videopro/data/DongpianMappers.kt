package com.dongpiandi.videopro.data

import com.dongpiandi.videopro.data.dto.DongpianCardDto
import com.dongpiandi.videopro.data.dto.DongpianDetailResponseDto
import com.dongpiandi.videopro.data.dto.DongpianEpisodeDto
import com.dongpiandi.videopro.data.dto.DongpianHistoryItemDto
import com.dongpiandi.videopro.data.dto.DongpianLibraryStatusItemDto
import com.dongpiandi.videopro.data.dto.DongpianPlaybackLineDto
import com.dongpiandi.videopro.data.dto.DongpianPlaylistItemDto
import com.dongpiandi.videopro.data.dto.DongpianVariantDto
import com.dongpiandi.videopro.data.model.DongpianCatalogCard
import com.dongpiandi.videopro.data.model.DongpianDetail
import com.dongpiandi.videopro.data.model.DongpianEpisode
import com.dongpiandi.videopro.data.model.DongpianHistoryItem
import com.dongpiandi.videopro.data.model.DongpianLibraryStatus
import com.dongpiandi.videopro.data.model.DongpianPlaybackLine
import com.dongpiandi.videopro.data.model.DongpianPlaylistSummary
import com.dongpiandi.videopro.data.model.DongpianResumeState
import com.dongpiandi.videopro.data.model.DongpianVariant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

internal fun DongpianCardDto.toDomain(): DongpianCatalogCard =
    DongpianCatalogCard(
        id = id,
        variantId = selectedVariantId ?: defaultVariantId ?: variantId ?: id.takeIf(String::isNotBlank),
        title = title,
        contentKind = contentKind,
        year = year,
        area = area,
        language = language,
        genres = genres.textList(),
        actors = actors.textList(),
        directors = directors.textList(),
        posterUrl = posterUrl,
        carouselUrl = carouselUrl,
        backdropUrl = backdropUrl,
        remarks = remarks,
        detailPath = detailUrl,
    )

internal fun DongpianDetailResponseDto.toDomain(): DongpianDetail {
    val resolvedVariantId = selectedVariantId ?: defaultVariantId ?: variantId ?: id
    return DongpianDetail(
        id = id,
        variantId = resolvedVariantId,
        title = title,
        contentKind = contentKind,
        year = year,
        area = area,
        language = language,
        genres = genres.textList(),
        actors = actors.textList(),
        directors = directors.textList(),
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        description = description,
        remarks = remarks,
        episodes = episodes.mapNotNull(DongpianEpisodeDto::toDomainOrNull),
        variants = variants.map(DongpianVariantDto::toDomain),
        episodeNextCursor = episodePagination?.nextCursor,
    )
}

internal fun DongpianEpisodeDto.toDomainOrNull(): DongpianEpisode? {
    val resolvedToken = token.trim()
    if (resolvedToken.isEmpty()) return null
    return DongpianEpisode(
        id = id,
        number = number ?: episodeNumber,
        title = title ?: displayName,
        token = resolvedToken,
        episodeTrack = episodeTrack,
    )
}

internal fun DongpianVariantDto.toDomain(): DongpianVariant =
    DongpianVariant(
        id = variantId ?: id,
        title = title,
        label = label ?: name,
        isDefault = isDefault == true || defaultValue == true || selected == true,
    )

internal fun DongpianPlaybackLineDto.toDomainOrNull(): DongpianPlaybackLine? {
    val usable = resolved && !resolveRequired && !resolveMode.equals("parse", ignoreCase = true)
    val uri =
        runCatching { URI(url) }
            .getOrNull()
            ?.takeIf {
                it.scheme.equals("https", ignoreCase = true) &&
                    !it.host.isNullOrBlank() &&
                    it.userInfo == null
            }
    if (!usable || uri == null) return null
    return DongpianPlaybackLine(
        id = id.ifBlank { url },
        label = displayLabel ?: label ?: providerName ?: id.ifBlank { "线路" },
        providerName = providerName,
        url = url,
        defaultPriority = defaultPriority,
    )
}

internal fun DongpianHistoryItemDto.toDomain(): DongpianHistoryItem =
    DongpianHistoryItem(
        variantId = variantId,
        title = title,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        contentKind = contentKind,
        episodeId = episodeId,
        episodeLabel = episodeLabel,
        episodeNumber = episodeNumber,
        episodeToken = episodeToken,
        positionSeconds = positionSeconds.coerceAtLeast(0),
        durationSeconds = durationSeconds?.coerceAtLeast(0),
        watchedAt = watchedAt,
        updatedAt = updatedAt,
    )

internal fun DongpianLibraryStatusItemDto.toDomain(): DongpianLibraryStatus =
    DongpianLibraryStatus(
        variantId = variantId,
        favorite = favorite,
        resume =
            resume?.let {
                DongpianResumeState(
                    variantId = variantId,
                    episodeId = it.episodeId,
                    episodeLabel = it.episodeLabel,
                    episodeNumber = it.episodeNumber,
                    episodeToken = it.episodeToken,
                    positionSeconds = it.positionSeconds.coerceAtLeast(0),
                    durationSeconds = it.durationSeconds?.coerceAtLeast(0),
                )
            },
        updatedAt = updatedAt,
    )

internal fun DongpianPlaylistItemDto.toDomain(): DongpianPlaylistSummary =
    DongpianPlaylistSummary(
        id = id,
        title = title,
        description = description,
        itemCount = itemCount,
        coverUrl = cover.coverUrl(),
    )

private fun JsonElement?.textList(): List<String> =
    when (this) {
        is JsonArray ->
            mapNotNull { element ->
                when (element) {
                    is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
                    is JsonObject ->
                        sequenceOf("name", "title", "display_name")
                            .mapNotNull { key -> (element[key] as? JsonPrimitive)?.contentOrNull }
                            .firstOrNull { it.isNotBlank() }
                    else -> null
                }
            }.distinct()
        is JsonPrimitive ->
            contentOrNull
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let(::listOf)
                .orEmpty()
        else -> emptyList()
    }

private fun JsonElement?.coverUrl(): String? =
    when (this) {
        is JsonObject ->
            sequenceOf("url", "poster_url", "backdrop_url")
                .mapNotNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
                .firstOrNull()
        is JsonPrimitive -> contentOrNull?.takeIf(String::isNotBlank)
        else -> null
    }

private val JsonPrimitive.contentOrNull: String?
    get() = runCatching { content }.getOrNull()
