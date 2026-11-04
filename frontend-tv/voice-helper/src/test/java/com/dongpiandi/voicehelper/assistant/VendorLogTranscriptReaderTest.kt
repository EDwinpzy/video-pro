package com.dongpiandi.voicehelper.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VendorLogTranscriptReaderTest {
    @Test
    fun keepsLatestPartialWhenFinalEventHasEmptyAsrText() {
        val lines =
            listOf(
                asrLine("今", finished = false),
                asrLine("今天天气怎么样", finished = false),
                asrLine("", finished = true),
            )

        assertEquals("今天天气怎么样", VendorLogTranscriptParser.parse(lines))
    }

    @Test
    fun prefersLatestNonEmptyResult() {
        val lines =
            listOf(
                asrLine("播放第一集", finished = false),
                asrLine("播放第二集", finished = true),
            )

        assertEquals("播放第二集", VendorLogTranscriptParser.parse(lines))
    }

    @Test
    fun ignoresMalformedUnrelatedAndChromeLines() {
        val lines =
            listOf(
                "I/VoiceService: unrelated",
                "I/VoiceService: onAsr asr is 缺少终止字段",
                asrLine("按返回键关闭", finished = false),
                asrLine("123", finished = false),
            )

        assertNull(VendorLogTranscriptParser.parse(lines))
    }

    @Test
    fun parsesTextContainingHyphens() {
        assertEquals(
            "上海-北京",
            VendorLogTranscriptParser.parseLine(
                asrLine("上海-北京", finished = false),
            ),
        )
    }

    @Test
    fun rejectsAnEmptyLineAndUnknownText() {
        assertNull(VendorLogTranscriptParser.parseLine(""))
        assertNull(VendorLogTranscriptParser.parseLine(asrLine("", finished = true)))
    }

    private fun asrLine(
        text: String,
        finished: Boolean,
    ): String =
        "01-01 12:00:00.000 123 456 I/[VoiceService\$initVoiceListener\$1]" +
            "(456): onAsr asr is $text- isFinished is $finished,mAsrText=ignored"
}
