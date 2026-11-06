package com.qiubo.optimaltv.lifecycle

internal object DoubleBackExitPolicy {
    const val WINDOW_MS = 2_500L

    enum class Action { PROMPT, EXIT }

    fun action(lastBackAtMs: Long, nowMs: Long): Action =
        if (
            lastBackAtMs > 0L &&
            nowMs >= lastBackAtMs &&
            nowMs - lastBackAtMs <= WINDOW_MS
        ) {
            Action.EXIT
        } else {
            Action.PROMPT
        }
}
