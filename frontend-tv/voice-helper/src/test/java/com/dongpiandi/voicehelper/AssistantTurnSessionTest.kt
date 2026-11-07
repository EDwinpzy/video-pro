package com.dongpiandi.voicehelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTurnSessionTest {
    @Test
    fun partialTextIsUsedWhenFinalPacketIsEmpty() {
        val session = AssistantTurnSession()
        val token = session.begin()

        assertEquals("今天天气怎么样", session.observePartial(token, "今天天气怎么样"))
        assertTrue(session.release(token))
        assertEquals("今天天气怎么样", session.observeResult(token, ""))
        assertEquals("今天天气怎么样", session.finishReleased(token))
        assertEquals(AssistantTurnSession.State.RESPONDING, session.state)
    }

    @Test
    fun finalResultCanArriveBeforeKeyRelease() {
        val session = AssistantTurnSession()
        val token = session.begin()

        session.observeResult(token, "打开设置")

        assertEquals(AssistantTurnSession.State.LISTENING, session.state)
        assertTrue(session.release(token))
        assertEquals("打开设置", session.finishReleased(token))
    }

    @Test
    fun oldGenerationCannotFinishNewTurn() {
        val session = AssistantTurnSession()
        val oldToken = session.begin()
        session.release(oldToken)
        session.cancel()
        val newToken = session.begin()

        assertNull(session.finishReleased(oldToken))
        assertNull(session.observePartial(oldToken, "旧内容"))
        assertEquals("新内容", session.observePartial(newToken, "新内容"))
    }

    @Test
    fun timeoutUsesLatestPartialOnlyAfterRelease() {
        val session = AssistantTurnSession()
        val token = session.begin()
        session.observePartial(token, "打开")

        assertNull(session.finishTimedOut(token))
        assertTrue(session.release(token))
        assertEquals("打开", session.finishTimedOut(token))
    }

    @Test
    fun injectedTurnCanStartDirectlyInRespondingState() {
        val session = AssistantTurnSession()
        val token = session.injectForTest("今天天气怎么样") ?: error("missing token")

        assertEquals(AssistantTurnSession.State.RESPONDING, session.state)
        assertTrue(session.hasFinalResult)
        assertFalse(session.release(token))
        assertEquals("今天天气怎么样", session.latestText)
    }
}
