package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.data.model.OfficialSkipPreset
import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveSkipSegmentPolicyTest {
    private val preset =
        OfficialSkipPreset(
            introEndSeconds = 90,
            outroFromEndSeconds = 120,
            sampleCount = 8,
            reportCount = 5,
        )

    @Test
    fun disabledUsesManualValuesEvenWhenPresetExists() {
        assertEquals(15, introSeconds(enabled = false, preset = preset, manual = 15))
        assertEquals(30, outroSeconds(enabled = false, preset = preset, manual = 30))
    }

    @Test
    fun enabledUsesPresetValuesIndependently() {
        assertEquals(90, introSeconds(enabled = true, preset = preset, manual = 15))
        assertEquals(120, outroSeconds(enabled = true, preset = preset, manual = 30))
    }

    @Test
    fun enabledFallsBackPerMissingPresetValue() {
        val introOnly = preset.copy(outroFromEndSeconds = 0)

        assertEquals(90, introSeconds(enabled = true, preset = introOnly, manual = 15))
        assertEquals(30, outroSeconds(enabled = true, preset = introOnly, manual = 30))
    }

    @Test
    fun enabledWithoutPresetUsesManualValues() {
        assertEquals(15, introSeconds(enabled = true, preset = null, manual = 15))
        assertEquals(30, outroSeconds(enabled = true, preset = null, manual = 30))
    }

    private fun introSeconds(
        enabled: Boolean,
        preset: OfficialSkipPreset?,
        manual: Int,
    ): Int =
        EffectiveSkipSegmentPolicy.introSeconds(
            smartSkipEnabled = enabled,
            preset = preset,
            manualSeconds = manual,
        )

    private fun outroSeconds(
        enabled: Boolean,
        preset: OfficialSkipPreset?,
        manual: Int,
    ): Int =
        EffectiveSkipSegmentPolicy.outroSeconds(
            smartSkipEnabled = enabled,
            preset = preset,
            manualSeconds = manual,
        )
}
