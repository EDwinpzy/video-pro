package com.dongpiandi.voicehelper

internal enum class VoiceMode {
    DICTATION,
    FACTORY_ASSISTANT,
}

internal object VoiceModePolicy {
    fun resolve(
        editorAttached: Boolean,
        inputMethodVisible: Boolean,
    ): VoiceMode =
        if (editorAttached && inputMethodVisible) {
            VoiceMode.DICTATION
        } else {
            VoiceMode.FACTORY_ASSISTANT
        }

    fun shouldDisableFactoryKeyService(
        editorAttached: Boolean,
        inputMethodVisible: Boolean,
    ): Boolean = resolve(editorAttached, inputMethodVisible) == VoiceMode.DICTATION
}
