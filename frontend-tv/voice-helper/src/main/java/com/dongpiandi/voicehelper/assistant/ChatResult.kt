package com.dongpiandi.voicehelper.assistant

data class ChatResult(
    val answer: String,
    val model: String,
) {
    override fun toString(): String =
        "ChatResult(answer=<${answer.length} chars>, model=$model)"
}
