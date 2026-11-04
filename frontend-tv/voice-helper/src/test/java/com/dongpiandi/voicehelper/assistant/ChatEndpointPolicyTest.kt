package com.dongpiandi.voicehelper.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEndpointPolicyTest {
    @Test
    fun normalizesBaseUrlsWithoutChangingApiPaths() {
        assertEquals(
            "https://example.com",
            ChatEndpointPolicy.normalizeBaseUrl("  HTTPS://EXAMPLE.COM///  "),
        )
        assertEquals(
            "http://example.com/API/v1",
            ChatEndpointPolicy.normalizeBaseUrl("http://EXAMPLE.com/API/v1///"),
        )
        assertEquals(
            "https://example.com/openai/v1",
            ChatEndpointPolicy.normalizeBaseUrl("https://example.com/openai/v1/"),
        )
    }

    @Test
    fun buildsChatEndpointFromRootCustomAndVersionedBases() {
        assertEquals(
            "https://example.com/v1/chat/completions",
            ChatEndpointPolicy.chatCompletionsUrl("https://example.com/"),
        )
        assertEquals(
            "https://example.com/openai/v1/chat/completions",
            ChatEndpointPolicy.chatCompletionsUrl("https://example.com/openai"),
        )
        assertEquals(
            "https://example.com/openai/v1/chat/completions",
            ChatEndpointPolicy.chatCompletionsUrl("https://example.com/openai/v1/"),
        )
        assertEquals(
            "https://example.com/openai/v1/chat/completions",
            ChatEndpointPolicy.chatCompletionsUrl(
                "https://example.com/openai/v1/chat/completions/",
            ),
        )
    }

    @Test
    fun acceptsHttpAndHttpsOnlyWhenThereIsNoQueryFragmentOrCredentials() {
        assertTrue(ChatEndpointPolicy.isValid("http://127.0.0.1:8080"))
        assertTrue(ChatEndpointPolicy.isValid("https://example.com/api"))

        val rejected =
            listOf(
                "",
                "ftp://example.com",
                "file:///private/config",
                "https://example.com/api?token=secret",
                "https://example.com/api#secret",
                "https://user:password@example.com/api",
            )
        rejected.forEach { value ->
            assertFalse(value, ChatEndpointPolicy.isValid(value))
        }
    }

    @Test
    fun configurationErrorDoesNotEchoThePrivateUrl() {
        val privateUrl = "https://private.example.invalid/api?token=do-not-echo"
        val error =
            assertThrows(AssistantConfigurationException::class.java) {
                ChatEndpointPolicy.normalizeBaseUrl(privateUrl)
            }

        assertFalse(error.message.orEmpty().contains("private.example.invalid"))
        assertFalse(error.message.orEmpty().contains("do-not-echo"))
    }
}
