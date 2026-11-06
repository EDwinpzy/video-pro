package com.qiubo.optimaltv.data.prefs

enum class SkipSegmentsMode(
    val label: String,
    val skipIntro: Boolean,
    val skipOutro: Boolean,
) {
    OFF("关", false, false),
    INTRO("片头", true, false),
    OUTRO("片尾", false, true),
    BOTH("片头+片尾", true, true),
    ;

    companion object {
        val menuEntries = listOf(OFF, INTRO, OUTRO, BOTH)

        fun of(ordinal: Int) = entries.getOrElse(ordinal) { OFF }
    }
}
