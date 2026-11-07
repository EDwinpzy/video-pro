package app.jellystack.tv

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class VendorAiuiConfigTest {
    @Test
    fun switchesToExternalAudioAndSilentTts() {
        val configured =
            VendorAiuiConfig.enableExternalAudio(
                """
                {
                  "tts": {"play_mode": "sdk", "audio_focus": "1"},
                  "speech": {"data_source":"sdk", "wakeup_mode":"off"}
                }
                """.trimIndent(),
            )

        assertContains(configured, """"data_source":"user"""")
        assertContains(configured, """"play_mode": "user"""")
        assertContains(configured, """"audio_focus": "0"""")
        assertFalse(configured.contains(""""data_source":"sdk""""))
    }
}
