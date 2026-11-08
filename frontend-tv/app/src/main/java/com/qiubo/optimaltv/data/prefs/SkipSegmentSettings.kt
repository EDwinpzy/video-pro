package com.qiubo.optimaltv.data.prefs

object SkipSegmentSettings {
    const val MAX_SECONDS = 600

    fun clamp(seconds: Int): Int = seconds.coerceIn(0, MAX_SECONDS)

    /** Keep manual input numeric and bounded to the three digits needed for [MAX_SECONDS]. */
    fun normalizeDraft(draft: String): String = draft.filter(Char::isDigit).take(3)

    fun secondsFromDraft(draft: String): Int =
        draft.toIntOrNull()?.coerceIn(0, MAX_SECONDS) ?: 0
}
