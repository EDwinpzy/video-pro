package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.data.model.OfficialSkipPreset

internal object EffectiveSkipSegmentPolicy {
    fun introSeconds(
        smartSkipEnabled: Boolean,
        preset: OfficialSkipPreset?,
        manualSeconds: Int,
    ): Int =
        if (!smartSkipEnabled) {
            manualSeconds
        } else {
            preset?.introEndSeconds?.takeIf { it > 0 } ?: manualSeconds
        }

    fun outroSeconds(
        smartSkipEnabled: Boolean,
        preset: OfficialSkipPreset?,
        manualSeconds: Int,
    ): Int =
        if (!smartSkipEnabled) {
            manualSeconds
        } else {
            preset?.outroFromEndSeconds?.takeIf { it > 0 } ?: manualSeconds
        }
}
