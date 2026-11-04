package com.dongpiandi.voicehelper.assistant

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class AssistantConfigurationException internal constructor(
    message: String,
) : Exception(message)

object ChatEndpointPolicy {
    private const val CHAT_COMPLETIONS_PATH = "/v1/chat/completions"
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
        val normalized = normalizeBaseUrl(rawBaseUrl)
        val base = normalized.toHttpUrlOrNull()
            ?: throw AssistantConfigurationException(INVALID_CONFIGURATION)
        val path = base.encodedPath.trimEnd('/')
        val endpointPath =
            when {
                path.endsWith(CHAT_COMPLETIONS_PATH) -> path
                path.endsWith("/v1") -> "$path/chat/completions"
                else -> "$path$CHAT_COMPLETIONS_PATH"
            }
        return base.newBuilder()
            .encodedPath(endpointPath)
            .build()
            .toString()
    }

    fun isValid(rawBaseUrl: String): Boolean =
        try {
            normalizeBaseUrl(rawBaseUrl)
            true
        } catch (_: AssistantConfigurationException) {
            false
        }
}
