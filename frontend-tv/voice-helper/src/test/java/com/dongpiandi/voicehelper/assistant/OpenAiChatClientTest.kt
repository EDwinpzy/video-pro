package com.dongpiandi.voicehelper.assistant

import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAiChatClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun successfulFirstModelSendsResponsesWebSearchRequest() = runBlocking {
        server.enqueue(successResponse("\"晴，适合出门。\""))

        val result =
            newClient(
                config =
                    config(
                        apiKey = "secret-token",
                        systemPrompt = "回答简洁。",
                    ),
            ).complete("今天天气怎么样？")

        assertEquals(ChatResult("晴，适合出门。", OpenAiChatClient.DEFAULT_MODELS[0]), result)
        assertEquals(listOf("group/free", "auto/deepseek-v4.1-flash"), OpenAiChatClient.DEFAULT_MODELS)
        assertEquals(1, server.requestCount)

        val request = requireRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/responses", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("group/free", body.getString("model"))
        assertTrue(body.getString("instructions").contains("回答简洁。"))
        assertTrue(body.getString("instructions").contains("web_search"))
        assertFalse(body.getBoolean("store"))
        assertFalse(body.getBoolean("stream"))
        assertFalse(body.getBoolean("parallel_tool_calls"))
        assertEquals("auto", body.getString("tool_choice"))
        assertEquals(OpenAiChatClient.MAX_SEARCH_CALLS, body.getInt("max_tool_calls"))
        assertEquals(
            OpenAiChatClient.MAX_OUTPUT_TOKENS,
            body.getInt("max_output_tokens"),
        )
        assertFalse(body.has("messages"))

        val input = body.getJSONArray("input")
        assertEquals(1, input.length())
        val userMessage = input.getJSONObject(0)
        assertEquals("user", userMessage.getString("role"))
        val content = userMessage.getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("input_text", content.getJSONObject(0).getString("type"))
        assertEquals("今天天气怎么样？", content.getJSONObject(0).getString("text"))

        val tools = body.getJSONArray("tools")
        assertEquals(1, tools.length())
        assertEquals("web_search", tools.getJSONObject(0).getString("type"))
        assertTrue(tools.getJSONObject(0).getBoolean("external_web_access"))
        assertEquals("low", tools.getJSONObject(0).getString("search_context_size"))
    }

    @Test
    fun fallbackStatusesRetryTheSecondModel() {
        val statuses =
            listOf(
                400,
                404,
                408,
                409,
                422,
                425,
                429,
                500,
                503,
            )
        statuses.forEach { status ->
            val isolated = MockWebServer()
            isolated.start()
            try {
                isolated.enqueue(MockResponse().setResponseCode(status).setBody("private detail"))
                isolated.enqueue(successResponse("\"fallback answer\""))

                val result =
                    runBlocking {
                        newClient(
                            config = config(serverUrl = isolated.url("/").toString()),
                        ).complete("question")
                    }

                assertEquals("fallback answer", result.answer)
                assertEquals(OpenAiChatClient.DEFAULT_MODELS[1], result.model)
                assertEquals(2, isolated.requestCount)
                val first = isolated.takeRequest(2, TimeUnit.SECONDS)
                val second = isolated.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull(first)
                assertNotNull(second)
                assertEquals("/v1/responses", first!!.path)
                assertEquals("/v1/responses", second!!.path)
                assertEquals(
                    "group/free",
                    JSONObject(first.body.readUtf8()).getString("model"),
                )
                assertEquals(
                    "auto/deepseek-v4.1-flash",
                    JSONObject(second.body.readUtf8()).getString("model"),
                )
            } finally {
                isolated.shutdown()
            }
        }
    }

    @Test
    fun networkFailureRetriesTheSecondModel() = runBlocking {
        server.enqueue(
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
        )
        server.enqueue(successResponse("\"network fallback\""))

        val result = newClient().complete("question")

        assertEquals("network fallback", result.answer)
        assertEquals(OpenAiChatClient.DEFAULT_MODELS[1], result.model)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun timeoutFailureRetriesTheSecondModel() = runBlocking {
        val attempts = AtomicInteger()
        val timeout =
            Interceptor { chain ->
                if (attempts.incrementAndGet() == 1) {
                    throw SocketTimeoutException("private timeout detail")
                }
                chain.proceed(chain.request())
            }
        val httpClient =
            OkHttpClient.Builder()
                .addInterceptor(timeout)
                .retryOnConnectionFailure(false)
                .build()
        server.enqueue(successResponse("\"timeout fallback\""))

        val result = newClient(httpClient = httpClient).complete("question")

        assertEquals("timeout fallback", result.answer)
        assertEquals(OpenAiChatClient.DEFAULT_MODELS[1], result.model)
        assertEquals(2, attempts.get())
        assertEquals(1, server.requestCount)
        assertFalse(result.answer.contains("private timeout detail"))
    }

    @Test
    fun slowFirstAttemptFallsBackWithoutWaitingForTheGlobalCallTimeout() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(successResponse("\"fast fallback\""))

        val startedAt = System.nanoTime()
        val result =
            newClient(attemptTimeoutsMs = listOf(150L, 2_000L))
                .complete("question")
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertEquals("fast fallback", result.answer)
        assertEquals(OpenAiChatClient.DEFAULT_MODELS[1], result.model)
        assertEquals(2, server.requestCount)
        assertTrue("fallback took ${elapsedMs}ms", elapsedMs < 2_000L)
    }

    @Test
    fun emptyMessageRetriesTheSecondModel() = runBlocking {
        server.enqueue(successResponse("\"   \""))
        server.enqueue(successResponse("\"second answer\""))

        val result = newClient().complete("question")

        assertEquals("second answer", result.answer)
        assertEquals(OpenAiChatClient.DEFAULT_MODELS[1], result.model)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun invalidJsonRetriesTheSecondModel() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{not-json"),
        )
        server.enqueue(successResponse("\"second answer\""))

        val result = newClient().complete("question")

        assertEquals("second answer", result.answer)
        assertEquals(OpenAiChatClient.DEFAULT_MODELS[1], result.model)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun invalidResponsesShapeRetriesTheSecondModel() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"completed","output":[]}"""),
        )
        server.enqueue(successResponse("\"second answer\""))

        val result = newClient().complete("question")

        assertEquals("second answer", result.answer)
        assertEquals(OpenAiChatClient.DEFAULT_MODELS[1], result.model)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun authenticationFailuresStopAfterTheFirstRequest() = runBlocking {
        listOf(401, 403).forEach { status ->
            val isolated = MockWebServer()
            isolated.start()
            try {
                isolated.enqueue(
                    MockResponse()
                        .setResponseCode(status)
                        .setBody("private authentication response"),
                )
                isolated.enqueue(successResponse("\"must not run\""))

                val result =
                    newClient(
                        config = config(serverUrl = isolated.url("/").toString()),
                    ).complete("question")

                assertEquals(OpenAiChatClient.SAFE_AUTH_MESSAGE, result.answer)
                assertEquals("", result.model)
                assertEquals(1, isolated.requestCount)
                assertFalse(result.answer.contains("private authentication response"))
            } finally {
                isolated.shutdown()
            }
        }
    }

    @Test
    fun bothModelsFailingReturnsOnlyTheSafeSummary() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("private response one"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("private response two"))

        val result = newClient().complete("question")

        assertEquals(OpenAiChatClient.SAFE_UNAVAILABLE_MESSAGE, result.answer)
        assertEquals("", result.model)
        assertEquals(2, server.requestCount)
        assertFalse(result.answer.contains("private response"))
    }

    @Test
    fun messagePartsAreJoinedWithoutExposingReasoningItems() = runBlocking {
        val output =
            JSONArray()
                .put(
                    JSONObject()
                        .put("type", "reasoning")
                        .put("summary", "private reasoning"),
                )
                .put(
                    JSONObject()
                        .put("type", "web_search_call")
                        .put("status", "completed"),
                )
                .put(
                    JSONObject()
                        .put("type", "message")
                        .put(
                            "content",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("type", "output_text")
                                        .put("text", "你"),
                                )
                                .put(
                                    JSONObject()
                                        .put("type", "output_text")
                                        .put("text", "好"),
                                ),
                        ),
                )
        server.enqueue(
            jsonResponse(
                JSONObject()
                    .put("status", "completed")
                    .put("output", output),
            ),
        )

        val result = newClient().complete("question")

        assertEquals("你好", result.answer)
        assertEquals(OpenAiChatClient.DEFAULT_MODELS[0], result.model)
        assertTrue(result.usedWebSearch)
        assertFalse(result.answer.contains("private reasoning"))
    }

    @Test
    fun topLevelOutputTextIsAccepted() = runBlocking {
        server.enqueue(
            jsonResponse(
                JSONObject()
                    .put("status", "completed")
                    .put("output_text", "直接回答"),
            ),
        )

        val result = newClient().complete("question")

        assertEquals("直接回答", result.answer)
        assertEquals(OpenAiChatClient.DEFAULT_MODELS[0], result.model)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun emptyApiKeyOmitsAuthorizationHeader() = runBlocking {
        server.enqueue(successResponse("\"keyless answer\""))

        val result = newClient(config = config(apiKey = "")).complete("question")

        assertEquals("keyless answer", result.answer)
        assertNull(requireRequest().getHeader("Authorization"))
    }

    @Test
    fun invalidLocalConfigurationMakesNoRequest() = runBlocking {
        val result =
            newClient(
                config =
                    AssistantConfig(
                        baseUrl = "ftp://private.example.invalid/path",
                        apiKey = "secret-token",
                        systemPrompt = null,
                    ),
            ).complete("question")

        assertEquals(OpenAiChatClient.SAFE_CONFIGURATION_MESSAGE, result.answer)
        assertEquals("", result.model)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun invalidApiKeyFailsLocallyWithoutEchoingTheKey() = runBlocking {
        val privateKey = "private\u0000key"
        val result = newClient(config = config(apiKey = privateKey)).complete("question")

        assertEquals(OpenAiChatClient.SAFE_CONFIGURATION_MESSAGE, result.answer)
        assertFalse(result.answer.contains("private"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun configurationDescriptionRedactsPrivateValues() {
        val description =
            AssistantConfig(
                baseUrl = "https://private.example.invalid/secret-path",
                apiKey = "private-api-key",
                systemPrompt = "private system prompt",
            ).toString()

        assertFalse(description.contains("private.example.invalid"))
        assertFalse(description.contains("private-api-key"))
        assertFalse(description.contains("private system prompt"))
        assertTrue(description.contains("<redacted>"))
    }

    @Test
    fun resultDescriptionOmitsTheAnswerText() {
        val description =
            ChatResult(
                answer = "private model response",
                model = OpenAiChatClient.DEFAULT_MODELS[0],
            ).toString()

        assertFalse(description.contains("private model response"))
        assertTrue(description.contains("group/free"))
    }

    @Test
    fun cancellationPropagatesAndCancelsTheActiveOkHttpCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val activeCall = AtomicReference<Call>()
        val listener =
            object : EventListener() {
                override fun callStart(call: Call) {
                    activeCall.set(call)
                }
            }
        val client =
            newClient(
                httpClient =
                    OkHttpClient.Builder()
                        .eventListener(listener)
                        .build(),
            )
        val request =
            async(start = CoroutineStart.UNDISPATCHED) {
                client.complete("question")
            }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        request.cancel()
        try {
            request.await()
            fail("CancellationException was not propagated")
        } catch (_: CancellationException) {
            // Expected.
        }

        val call = activeCall.get()
        assertNotNull(call)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!call.isCanceled() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(call.isCanceled())
        assertEquals(1, server.requestCount)
    }

    private fun newClient(
        config: AssistantConfig = config(),
        httpClient: OkHttpClient =
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .build(),
        attemptTimeoutsMs: List<Long> = OpenAiChatClient.DEFAULT_ATTEMPT_TIMEOUTS_MS,
    ): OpenAiChatClient =
        OpenAiChatClient(
            configProvider = { config },
            httpClient = httpClient,
            attemptTimeoutsMs = attemptTimeoutsMs,
        )

    private fun config(
        serverUrl: String = server.url("/").toString(),
        apiKey: String = "test-key",
        systemPrompt: String? = null,
    ): AssistantConfig =
        AssistantConfig(
            baseUrl = serverUrl,
            apiKey = apiKey,
            systemPrompt = systemPrompt,
        )

    private fun successResponse(textJson: String): MockResponse {
        val output =
            JSONArray().put(
                JSONObject()
                    .put("type", "message")
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "output_text")
                                .put("text", JSONObject("""{"text":$textJson}""").getString("text")),
                        ),
                    ),
            )
        return jsonResponse(
            JSONObject()
                .put("status", "completed")
                .put("output", output),
        )
    }

    private fun jsonResponse(body: JSONObject): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body.toString())

    private fun requireRequest() =
        checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)) {
            "No request reached MockWebServer"
        }
}
