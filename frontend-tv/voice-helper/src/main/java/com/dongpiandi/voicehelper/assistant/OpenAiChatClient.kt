package com.dongpiandi.voicehelper.assistant

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
    private val attemptTimeoutsMs: List<Long> = DEFAULT_ATTEMPT_TIMEOUTS_MS,
) {
    constructor(
        configStore: AssistantConfigStore,
        httpClient: OkHttpClient = OkHttpClient(),
    ) : this({ configStore.get() }, httpClient, DEFAULT_ATTEMPT_TIMEOUTS_MS)

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
                ChatEndpointPolicy.responsesUrl(config.baseUrl)
            } catch (_: AssistantConfigurationException) {
                return ChatResult(SAFE_CONFIGURATION_MESSAGE, "")
            }

        for ((index, model) in DEFAULT_MODELS.withIndex()) {
            currentCoroutineContext().ensureActive()
            try {
                val timeoutMs =
                    attemptTimeoutsMs.getOrNull(index)
                        ?: attemptTimeoutsMs.lastOrNull()
                        ?: CALL_TIMEOUT_SECONDS * 1_000
                val attempt =
                    withTimeoutOrNull(timeoutMs) {
                        executeAttempt(endpoint, config, model, userPrompt)
                    } ?: continue
                return ChatResult(attempt.answer, model, attempt.usedWebSearch)
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
    ): AttemptAnswer {
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
        val payload =
            JSONObject()
                .put("model", model)
                .put("instructions", buildInstructions(config.systemPrompt))
                .put("input", buildInput(userPrompt))
                .put("tools", buildWebSearchTools())
                .put("tool_choice", "auto")
                .put("parallel_tool_calls", false)
                .put("max_tool_calls", MAX_SEARCH_CALLS)
                .put("store", false)
                .put("stream", false)
                .put("max_output_tokens", MAX_OUTPUT_TOKENS)
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

    private fun buildInstructions(systemPrompt: String?): String {
        val configuredPrompt = systemPrompt?.trim().orEmpty()
        return if (configuredPrompt.isEmpty()) {
            DEFAULT_INSTRUCTIONS
        } else {
            "$configuredPrompt\n\n$DEFAULT_INSTRUCTIONS"
        }
    }

    private fun buildInput(userPrompt: String): JSONArray =
        JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put(
                    "content",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "input_text")
                            .put("text", userPrompt),
                    ),
                ),
        )

    private fun buildWebSearchTools(): JSONArray =
        JSONArray().put(
            JSONObject()
                .put("type", "web_search")
                .put("external_web_access", true)
                .put("search_context_size", "low"),
        )

    private fun readAnswer(response: Response): AttemptAnswer {
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

    private fun parseAnswer(body: String): AttemptAnswer {
        val root =
            try {
                JSONObject(body)
            } catch (_: JSONException) {
                throw RetryableChatAttemptException()
            }

        val output = root.optJSONArray("output")
        val answer = StringBuilder()
        var usedWebSearch = false
        if (output != null) {
            for (index in 0 until output.length()) {
                val item = output.optJSONObject(index) ?: continue
                when (item.optString("type")) {
                    "web_search_call" -> usedWebSearch = true
                    "message" -> appendOutputContent(answer, item.opt("content"))
                }
            }
        }

        val text = answer.toString().trim()
        if (text.isNotEmpty()) return AttemptAnswer(text, usedWebSearch)

        root.optString("output_text")
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let { return AttemptAnswer(it, usedWebSearch) }
        throw RetryableChatAttemptException()
    }

    private fun appendOutputContent(
        answer: StringBuilder,
        content: Any?,
    ) {
        when (content) {
            is String -> answer.append(content)
            is JSONObject -> appendOutputPart(answer, content)
            is JSONArray -> {
                for (index in 0 until content.length()) {
                    when (val part = content.opt(index)) {
                        is String -> answer.append(part)
                        is JSONObject -> appendOutputPart(answer, part)
                    }
                }
            }
        }
    }

    private fun appendOutputPart(
        answer: StringBuilder,
        part: JSONObject,
    ) {
        val type = part.optString("type")
        if (type != "output_text" && type != "text") return
        answer.append(part.optString("text"))
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

    private fun CancellableContinuation<AttemptAnswer>.resumeFailure(error: Throwable) {
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
        const val READ_TIMEOUT_SECONDS = 45L
        const val CALL_TIMEOUT_SECONDS = 50L
        val DEFAULT_ATTEMPT_TIMEOUTS_MS: List<Long> = listOf(10_000L, 30_000L)
        const val MAX_OUTPUT_TOKENS = 4_000
        const val MAX_SEARCH_CALLS = 1

        const val SAFE_EMPTY_PROMPT_MESSAGE = "没有识别到要询问的内容。"
        const val SAFE_CONFIGURATION_MESSAGE = "语音助手尚未配置完整。"
        const val SAFE_AUTH_MESSAGE = "语音助手认证失败，请检查接口密钥。"
        const val SAFE_UNAVAILABLE_MESSAGE = "语音助手暂时无法回答，请稍后再试。"

        private const val DEFAULT_INSTRUCTIONS =
            "请使用与用户相同的语言，给出适合电视语音播报的简洁回答。" +
                "遇到新闻、天气、价格、版本、日程或其他可能变化的信息时，" +
                "使用一次 web_search 获取最新资料后直接回答，不要重复搜索。" +
                "不要输出 Markdown 表格、代码块或长网址；引用来源时只保留来源名称。"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private class AuthenticationRejectedException : Exception()

private class RetryableChatAttemptException : Exception()

private class NonRetryableChatAttemptException : Exception()

private data class AttemptAnswer(
    val answer: String,
    val usedWebSearch: Boolean,
)
