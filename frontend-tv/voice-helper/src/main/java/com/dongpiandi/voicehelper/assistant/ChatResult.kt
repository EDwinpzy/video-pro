package com.dongpiandi.voicehelper.assistant

data class ChatResult(
    val answer: String,
    val model: String,
    val usedWebSearch: Boolean = false,
) {
    override fun toString(): String =
        "ChatResult(answer=<${answer.length} chars>, model=$model, " +
            "webSearch=$usedWebSearch)"
}
