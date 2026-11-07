package com.dongpiandi.voicehelper.assistant

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class AssistantConfigurationException internal constructor(
    message: String,
) : Exception(message)

object ChatEndpointPolicy {
    private const val VERSIONED_API_PATH = "/v1"
    private const val CHAT_COMPLETIONS_ENDPOINT = "/chat/completions"
    private const val RESPONSES_ENDPOINT = "/responses"
    private const val CHAT_COMPLETIONS_PATH = "$VERSIONED_API_PATH$CHAT_COMPLETIONS_ENDPOINT"
    private const val RESPONSES_PATH = "$VERSIONED_API_PATH$RESPONSES_ENDPOINT"
    private const val INVALID_CONFIGURATION = "语音助手配置无效。"

    fun normalizeBaseUrl(raw: String): String {
        val candidate = raw.trim()
        if (
            candidate.isEmpty() ||
            candidate.contains('?') ||
            candidate.contains('#')
        ) {
            throw AssistantConfigurationException(INVALID_CONFIGURATION)
        }

        val parsed =
            candidate.toHttpUrlOrNull()
                ?: throw AssistantConfigurationException(INVALID_CONFIGURATION)
        if (
            (parsed.scheme != "http" && parsed.scheme != "https") ||
            parsed.username.isNotEmpty() ||
            parsed.password.isNotEmpty()
        ) {
            throw AssistantConfigurationException(INVALID_CONFIGURATION)
        }

        val path = parsed.encodedPath.trimEnd('/')
        val canonical =
            parsed.newBuilder()
                .encodedPath(path.ifEmpty { "/" })
                .build()
                .toString()
        return canonical.removeSuffix("/")
    }

    fun chatCompletionsUrl(rawBaseUrl: String): String {
        return buildApiEndpoint(rawBaseUrl, CHAT_COMPLETIONS_ENDPOINT)
    }

    fun responsesUrl(rawBaseUrl: String): String {
        return buildApiEndpoint(rawBaseUrl, RESPONSES_ENDPOINT)
    }

    fun isValid(rawBaseUrl: String): Boolean =
        try {
            normalizeBaseUrl(rawBaseUrl)
            true
        } catch (_: AssistantConfigurationException) {
            false
        }

    private fun buildApiEndpoint(
        rawBaseUrl: String,
        endpointSuffix: String,
    ): String {
        val base =
            normalizeBaseUrl(rawBaseUrl).toHttpUrlOrNull()
                ?: throw AssistantConfigurationException(INVALID_CONFIGURATION)
        val path = base.encodedPath.trimEnd('/')
        val apiRoot =
            when {
                path.endsWith(CHAT_COMPLETIONS_PATH) ->
                    path.removeSuffix(CHAT_COMPLETIONS_PATH) + VERSIONED_API_PATH
                path.endsWith(RESPONSES_PATH) ->
                    path.removeSuffix(RESPONSES_PATH) + VERSIONED_API_PATH
                path.endsWith(VERSIONED_API_PATH) -> path
                else -> "$path$VERSIONED_API_PATH"
            }
        return base.newBuilder()
            .encodedPath("$apiRoot$endpointSuffix")
            .build()
            .toString()
    }
}
