package com.dongpiandi.voicehelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModePolicyTest {
    @Test
    fun editorWithVisibleInputMethodUsesDictation() {
        assertEquals(
            VoiceMode.DICTATION,
            VoiceModePolicy.resolve(editorAttached = true, inputMethodVisible = true),
        )
        assertTrue(
            VoiceModePolicy.shouldDisableFactoryKeyService(
                editorAttached = true,
                inputMethodVisible = true,
            ),
        )
    }

    @Test
    fun noEditorKeepsFactoryAssistant() {
        assertEquals(
            VoiceMode.FACTORY_ASSISTANT,
            VoiceModePolicy.resolve(editorAttached = false, inputMethodVisible = false),
        )
        assertFalse(
            VoiceModePolicy.shouldDisableFactoryKeyService(
                editorAttached = false,
                inputMethodVisible = false,
            ),
        )
    }

    @Test
    fun staleEditorWithoutVisibleInputMethodKeepsFactoryAssistant() {
        assertEquals(
            VoiceMode.FACTORY_ASSISTANT,
            VoiceModePolicy.resolve(editorAttached = true, inputMethodVisible = false),
        )
    }
}
