package com.dongpiandi.voicehelper

internal enum class VoiceRoute {
    DICTATION,
    ASSISTANT,
}

/**
 * Keeps one controller responsible for a filtered key sequence even if editor focus changes
 * between ACTION_DOWN and ACTION_UP.
 */
internal class VoiceKeyRouteLatch {
    var owner: VoiceRoute? = null
        private set

    fun claim(route: VoiceRoute): VoiceRoute {
        owner?.let { return it }
        owner = route
        return route
    }

    fun clear() {
        owner = null
    }
}
