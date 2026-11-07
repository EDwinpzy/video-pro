package com.dongpiandi.videopro.data

import com.dongpiandi.videopro.data.dto.DongpianPlaybackResponseDto
import com.dongpiandi.videopro.data.model.DongpianPlaybackLine
import com.dongpiandi.videopro.data.transport.DongpianApiClient
import com.dongpiandi.videopro.data.transport.DongpianHttpMethod
import com.dongpiandi.videopro.data.transport.DongpianRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString

interface DongpianPlaybackRepository {
    suspend fun resolve(episodeToken: String): List<DongpianPlaybackLine>
}

class DongpianPlaybackRepositoryImpl(
    private val apiClient: DongpianApiClient,
) : DongpianPlaybackRepository {
    override suspend fun resolve(episodeToken: String): List<DongpianPlaybackLine> {
        val token = episodeToken.trim()
        if (!token.matches(TOKEN_PATTERN)) {
            throw DongpianException(
                kind = DongpianFailureKind.INVALID_INPUT,
                message = "Episode token is invalid.",
            )
        }
        val response =
            apiClient
                .execute(
                    DongpianRequest(
                        method = DongpianHttpMethod.GET,
                        pathAndQuery = "/v1/playback/resolve/${encodePathSegment(token)}",
                    ),
                ).requireSuccess()
        val dto =
            try {
                DongpianJson.value.decodeFromString<DongpianPlaybackResponseDto>(response.body)
            } catch (failure: SerializationException) {
                throw DongpianException(
                    kind = DongpianFailureKind.INVALID_RESPONSE,
                    message = "Dongpian returned an invalid playback response.",
                    cause = failure,
                )
            }
        if (dto.objectName != "playback.resolve") {
            throw DongpianException(
                kind = DongpianFailureKind.INVALID_RESPONSE,
                message = "Dongpian returned an unexpected playback response type.",
            )
        }
        return dto.lineOptions
            .mapNotNull { it.toDomainOrNull() }
            .distinctBy(DongpianPlaybackLine::url)
    }

    private companion object {
        val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{1,512}")
    }
}
