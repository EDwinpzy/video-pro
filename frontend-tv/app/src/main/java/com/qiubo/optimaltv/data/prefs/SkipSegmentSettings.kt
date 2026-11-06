package com.qiubo.optimaltv.data.prefs

object SkipSegmentSettings {
    const val MAX_SECONDS = 600
    const val FINE_STEP_SECONDS = 1
    const val COARSE_STEP_SECONDS = 30

    fun clamp(seconds: Int): Int = seconds.coerceIn(0, MAX_SECONDS)
}
