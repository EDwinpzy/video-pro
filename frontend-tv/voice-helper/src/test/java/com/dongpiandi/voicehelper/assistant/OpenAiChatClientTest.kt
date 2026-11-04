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
    fun successfulFirstModelSendsExpectedRequestAndParsesStringContent() = runBlocking {
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
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("group/free", body.getString("model"))
        assertFalse(body.getBoolean("stream"))
        assertEquals(OpenAiChatClient.TEMPERATURE, body.getDouble("temperature"), 0.0)
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("回答简洁。", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("今天天气怎么样？", messages.getJSONObject(1).getString("content"))
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
                assertEquals(
                    "group/free",
                    JSONObject(first!!.body.readUtf8()).getString("model"),
                )
                assertEquals(
                    "auto/deepseek-v4.1-flash",
                    JSONObject(second!!.body.readUtf8()).getString("model"),
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
    fun emptyContentRetriesTheSecondModel() = runBlocking {
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
    fun invalidJsonShapeRetriesTheSecondModel() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[]}"""),
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
    fun arrayContentIsJoinedWithoutExposingRawJson() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": [
                              {"type": "text", "text": "你"},
                              {"type": "text", "text": "好"}
                            ]
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val result = newClient().complete("question")

        assertEquals("你好", result.answer)
        assertEquals(OpenAiChatClient.DEFAULT_MODELS[0], result.model)
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
    ): OpenAiChatClient =
        OpenAiChatClient(
            configProvider = { config },
            httpClient = httpClient,
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

    private fun successResponse(contentJson: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "choices": [
                    {
                      "message": {
                        "content": $contentJson
                      }
                    }
                  ]
                }
                """.trimIndent(),
            )

    private fun requireRequest() =
        checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)) {
            "No request reached MockWebServer"
        }
}
