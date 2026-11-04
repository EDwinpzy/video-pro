package com.dongpiandi.voicehelper.assistant

import java.util.Locale

/**
 * Pure selection rules for text exposed by the closed-source vendor voice window.
 *
 * Candidate order is oldest to newest. Event text is considered before a window snapshot because
 * it describes the latest content change.
 */
object VendorTranscriptPolicy {
    const val MAX_TRANSCRIPT_CHARS = 500

    fun select(candidates: Iterable<CharSequence?>): String? {
        var selected: String? = null
        for (candidate in candidates) {
            val normalized = normalize(candidate) ?: continue
            if (isPlausibleNormalized(normalized)) selected = normalized
        }
        return selected
    }

    fun select(
        eventTexts: Iterable<CharSequence?>,
        nodeTexts: Iterable<CharSequence?>,
    ): String? = select(eventTexts) ?: select(nodeTexts)

    fun isPlausible(candidate: CharSequence?): Boolean {
        val normalized = normalize(candidate) ?: return false
        return isPlausibleNormalized(normalized)
    }

    private fun normalize(candidate: CharSequence?): String? {
        if (candidate == null) return null
        val normalized = StringBuilder(minOf(candidate.length, MAX_TRANSCRIPT_CHARS + 1))
        var pendingSpace = false
        for (character in candidate) {
            when {
                character in ZERO_WIDTH_CHARACTERS -> Unit
                character.isWhitespace() || character.isISOControl() -> {
                    pendingSpace = normalized.isNotEmpty()
                }
                else -> {
                    if (pendingSpace) normalized.append(' ')
                    pendingSpace = false
                    normalized.append(character)
                    if (normalized.length > MAX_TRANSCRIPT_CHARS) return null
                }
            }
        }
        return normalized.toString().takeIf { it.isNotEmpty() }
    }

    private fun isPlausibleNormalized(text: String): Boolean {
        if (text.length > MAX_TRANSCRIPT_CHARS) return false
        if (!text.any { it.isLetter() }) return false

        val lower = text.lowercase(Locale.ROOT)
        val label = lower.trim { it.isWhitespace() || it in LABEL_PUNCTUATION }
        if (label in CHROME_LABELS) return false
        if (CHROME_FRAGMENTS.any { lower.contains(it) }) return false
        return true
    }

    private val ZERO_WIDTH_CHARACTERS =
        setOf('\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF')

    private val LABEL_PUNCTUATION =
        setOf(
            '。',
            '，',
            '.',
            ',',
            '!',
            '！',
            '?',
            '？',
            ':',
            '：',
            ';',
            '；',
            '…',
            '"',
            '\'',
            '“',
            '”',
            '‘',
            '’',
            '「',
            '」',
            '『',
            '』',
        )

    private val CHROME_FRAGMENTS =
        listOf(
            "tigris 语音助手",
            "tigris语音助手",
            "jmgo 语音助手",
            "jmgo语音助手",
            "没有识别到语音",
            "按返回键关闭",
            "按返回键退出",
            "按下返回键关闭",
        )

    private val CHROME_LABELS =
        setOf(
            "语音助手",
            "语音识别",
            "语音输入",
            "正在听",
            "正在聆听",
            "正在识别",
            "正在处理",
            "请说话",
            "请说",
            "按住说话",
            "松开结束",
            "点击说话",
            "重新说话",
            "重试",
            "取消",
            "关闭",
            "返回",
            "完成",
            "发送",
            "识别结果",
            "voice assistant",
            "voice recognition",
            "listening",
            "recognizing",
            "speak now",
            "hold to talk",
            "release to finish",
            "cancel",
            "close",
            "back",
            "done",
            "send",
            "retry",
            "no speech recognized",
        )
}
