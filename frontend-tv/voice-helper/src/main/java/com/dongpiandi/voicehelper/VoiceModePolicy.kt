package com.dongpiandi.voicehelper

internal enum class VoiceMode {
    DICTATION,
    ASSISTANT,
}

internal object VoiceModePolicy {
    fun resolve(editorAttached: Boolean): VoiceMode =
        if (editorAttached) {
            VoiceMode.DICTATION
        } else {
            VoiceMode.ASSISTANT
        }
}
