package com.qiubo.optimaltv.dictation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorAiuiConfigTest {
    @Test
    fun usesExternalPcmAndSilencesAssistantPlayback() {
        val configured =
            VendorAiuiConfig.enableDictation(
                """
                {
                  "tts": {"play_mode": "sdk", "audio_focus": "1"},
                  "speech": {"data_source":"sdk", "interact_mode":"continuous", "wakeup_mode":"on"}
                }
                """.trimIndent(),
            )

        assertTrue(configured.contains(""""data_source":"user""""))
        assertTrue(configured.contains(""""play_mode": "user""""))
        assertTrue(configured.contains(""""audio_focus": "0""""))
        assertTrue(configured.contains(""""interact_mode":"oneshot""""))
        assertTrue(configured.contains(""""wakeup_mode":"off""""))
        assertFalse(configured.contains(""""play_mode": "sdk""""))
        assertFalse(configured.contains(""""data_source":"sdk""""))
    }

    @Test
    fun keepsExternalDataSourceForEveryAudioSource() {
        val configured =
            VendorAiuiConfig.enableDictation(
                """{"speech":{"data_source":"sdk","interact_mode":"oneshot"}}""",
            )

        assertTrue(configured.contains(""""data_source":"user""""))
        assertFalse(configured.contains(""""data_source":"sdk""""))
    }
}
