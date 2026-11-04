package com.dongpiandi.voicehelper.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorTranscriptPolicyTest {
    @Test
    fun prefersLatestSubstantiveCandidateOverEarlierLongerText() {
        assertEquals(
            "好",
            VendorTranscriptPolicy.select(
                listOf("这是一段较早而且很长的话", "好"),
            ),
        )
    }

    @Test
    fun eventTextWinsOverWindowSnapshot() {
        assertEquals(
            "来自最新事件",
            VendorTranscriptPolicy.select(
                eventTexts = listOf("来自最新事件"),
                nodeTexts = listOf("旧窗口文本"),
            ),
        )
    }

    @Test
    fun rejectedEventTextFallsBackToNodeSnapshot() {
        assertEquals(
            "今天天气怎么样",
            VendorTranscriptPolicy.select(
                eventTexts = listOf("按返回键关闭"),
                nodeTexts = listOf("今天天气怎么样"),
            ),
        )
    }

    @Test
    fun rejectsKnownChromeNumbersAndLabels() {
        assertNull(
            VendorTranscriptPolicy.select(
                listOf(
                    "Tigris 语音助手",
                    "没有识别到语音",
                    "按返回键关闭",
                    "12:34",
                    "123",
                    "取消",
                    "正在聆听…",
                ),
            ),
        )
    }

    @Test
    fun normalizesWhitespaceAndInvisibleCharacters() {
        assertEquals(
            "今天 天气怎么样",
            VendorTranscriptPolicy.select(listOf("  今天 \n 天气\u200B怎么样  ")),
        )
    }

    @Test
    fun rejectsOverlongContent() {
        assertNull(
            VendorTranscriptPolicy.select(
                listOf("啊".repeat(VendorTranscriptPolicy.MAX_TRANSCRIPT_CHARS + 1)),
            ),
        )
    }

    @Test
    fun doesNotRejectSubstantiveTextThatOnlyStartsWithALabel() {
        assertTrue(VendorTranscriptPolicy.isPlausible("取消预订"))
        assertEquals(
            "取消预订",
            VendorTranscriptPolicy.select(listOf("取消预订")),
        )
    }
}
