package com.qiubo.optimaltv.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Test

class DoubleBackExitPolicyTest {

    @Test
    fun firstBackPromptsInsteadOfExiting() {
        assertEquals(
            DoubleBackExitPolicy.Action.PROMPT,
            DoubleBackExitPolicy.action(lastBackAtMs = 0L, nowMs = 1_000L),
        )
    }

    @Test
    fun secondBackWithinWindowExits() {
        assertEquals(
            DoubleBackExitPolicy.Action.EXIT,
            DoubleBackExitPolicy.action(lastBackAtMs = 1_000L, nowMs = 3_500L),
        )
    }

    @Test
    fun secondBackAfterWindowPromptsAgain() {
        assertEquals(
            DoubleBackExitPolicy.Action.PROMPT,
            DoubleBackExitPolicy.action(lastBackAtMs = 1_000L, nowMs = 3_501L),
        )
    }

    @Test
    fun clockGoingBackwardsPromptsAgain() {
        assertEquals(
            DoubleBackExitPolicy.Action.PROMPT,
            DoubleBackExitPolicy.action(lastBackAtMs = 2_000L, nowMs = 1_999L),
        )
    }
}
