package com.dongpiandi.voicehelper.assistant

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class OpenAiChatClient(
    private val configProvider: () -> AssistantConfig,
    httpClient: OkHttpClient = OkHttpClient(),
) {
    constructor(
        configStore: AssistantConfigStore,
        httpClient: OkHttpClient = OkHttpClient(),
    ) : this({ configStore.get() }, httpClient)

    private val httpClient =
        httpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    suspend fun complete(userPrompt: String): ChatResult {
        if (userPrompt.isBlank()) {
            return ChatResult(SAFE_EMPTY_PROMPT_MESSAGE, "")
        }

        val config =
            try {
                configProvider()
            } catch (_: AssistantConfigurationException) {
                return ChatResult(SAFE_CONFIGURATION_MESSAGE, "")
            }
        if (!AssistantConfigPolicy.isApiKeyUsable(config.apiKey)) {
            return ChatResult(SAFE_CONFIGURATION_MESSAGE, "")
        }
        val endpoint =
            try {
                ChatEndpointPolicy.chatCompletionsUrl(config.baseUrl)
            } catch (_: AssistantConfigurationException) {
                return ChatResult(SAFE_CONFIGURATION_MESSAGE, "")
            }

        for (model in DEFAULT_MODELS) {
            currentCoroutineContext().ensureActive()
            try {
                val answer = executeAttempt(endpoint, config, model, userPrompt)
                return ChatResult(answer, model)
            } catch (_: AuthenticationRejectedException) {
                return ChatResult(SAFE_AUTH_MESSAGE, "")
            } catch (_: RetryableChatAttemptException) {
                continue
            } catch (_: NonRetryableChatAttemptException) {
                return ChatResult(SAFE_UNAVAILABLE_MESSAGE, "")
            } catch (_: IOException) {
                continue
            } catch (_: JSONException) {
                continue
            }
        }
        return ChatResult(SAFE_UNAVAILABLE_MESSAGE, "")
    }

    private suspend fun executeAttempt(
        endpoint: String,
        config: AssistantConfig,
        model: String,
        userPrompt: String,
    ): String {
        val request = buildRequest(endpoint, config, model, userPrompt)
        return suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeFailure(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val answer = response.use(::readAnswer)
                            if (continuation.isActive) continuation.resume(answer)
                        } catch (error: IOException) {
                            continuation.resumeFailure(error)
                        } catch (error: AuthenticationRejectedException) {
                            continuation.resumeFailure(error)
                        } catch (error: RetryableChatAttemptException) {
                            continuation.resumeFailure(error)
                        } catch (error: NonRetryableChatAttemptException) {
                            continuation.resumeFailure(error)
                        } catch (error: RuntimeException) {
                            if (error is kotlinx.coroutines.CancellationException) {
                                throw error
                            }
                            continuation.resumeFailure(RetryableChatAttemptException())
                        }
                    }
                },
            )
        }
    }

    private fun buildRequest(
        endpoint: String,
        config: AssistantConfig,
        model: String,
        userPrompt: String,
    ): Request {
        val messages = JSONArray()
        config.systemPrompt
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { prompt ->
                messages.put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", prompt),
                )
            }
        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", userPrompt),
        )
        val payload =
            JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", TEMPERATURE)
                .put("stream", false)
                .toString()
        return Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .apply {
                val apiKey = config.apiKey.trim()
                if (apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer $apiKey")
                }
            }
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun readAnswer(response: Response): String {
        when {
            response.code == 401 || response.code == 403 ->
                throw AuthenticationRejectedException()
            isFallbackStatus(response.code) -> throw RetryableChatAttemptException()
            !response.isSuccessful -> throw NonRetryableChatAttemptException()
        }
        val body = response.body?.string().orEmpty()
        if (body.isBlank()) throw RetryableChatAttemptException()
        return parseAnswer(body)
    }

    private fun parseAnswer(body: String): String {
        val answer =
            try {
                val choices = JSONObject(body).optJSONArray("choices")
                    ?: throw RetryableChatAttemptException()
                if (choices.length() == 0) throw RetryableChatAttemptException()
                val message =
                    choices.optJSONObject(0)?.optJSONObject("message")
                        ?: throw RetryableChatAttemptException()
                parseContent(message.opt("content"))
            } catch (_: JSONException) {
                throw RetryableChatAttemptException()
            }
        if (answer.isBlank()) throw RetryableChatAttemptException()
        return answer.trim()
    }

    private fun parseContent(content: Any?): String =
        when (content) {
            is String -> content
            is JSONArray -> {
                buildString {
                    for (index in 0 until content.length()) {
                        when (val part = content.opt(index)) {
                            is String -> append(part)
                            is JSONObject -> append(part.optString("text", ""))
                        }
                    }
                }
            }
            else -> throw RetryableChatAttemptException()
        }

    private fun isFallbackStatus(status: Int): Boolean =
        status == 400 ||
            status == 404 ||
            status == 408 ||
            status == 409 ||
            status == 422 ||
            status == 425 ||
            status == 429 ||
            status in 500..599

    private fun CancellableContinuation<String>.resumeFailure(error: Throwable) {
        if (isActive) resumeWithException(error)
    }

    companion object {
        @JvmField
        val DEFAULT_MODELS: List<String> =
            listOf(
                "group/free",
                "auto/deepseek-v4.1-flash",
            )

        const val CONNECT_TIMEOUT_SECONDS = 8L
        const val READ_TIMEOUT_SECONDS = 30L
        const val CALL_TIMEOUT_SECONDS = 35L
        const val TEMPERATURE = 0.3

        const val SAFE_EMPTY_PROMPT_MESSAGE = "没有识别到要询问的内容。"
        const val SAFE_CONFIGURATION_MESSAGE = "语音助手尚未配置完整。"
        const val SAFE_AUTH_MESSAGE = "语音助手认证失败，请检查接口密钥。"
        const val SAFE_UNAVAILABLE_MESSAGE = "语音助手暂时无法回答，请稍后再试。"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private class AuthenticationRejectedException : Exception()

private class RetryableChatAttemptException : Exception()

private class NonRetryableChatAttemptException : Exception()
