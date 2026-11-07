package app.jellystack.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemDictationSessionTest {
    @Test
    fun repeatedDownKeepsOneSession() {
        val session = SystemDictationSession()
        val token = assertNotNull(session.begin(7))

        assertNull(session.begin(7))
        assertEquals(SystemDictationSession.State.LISTENING, session.state)
        assertEquals(token, session.generation)
    }

    @Test
    fun resultWaitsForRelease() {
        val session = SystemDictationSession()
        val token = assertNotNull(session.begin(7))
        session.observe(token, "今天天气怎么样")

        assertNull(session.finish(token, null, 7))
        assertTrue(session.release())
        assertEquals("今天天气怎么样", session.finish(token, null, 7))
    }

    @Test
    fun finalTextReplacesPartialText() {
        val session = SystemDictationSession()
        val token = assertNotNull(session.begin(4))
        session.observe(token, "今天天汽")
        assertTrue(session.release())

        assertEquals("今天天气怎么样", session.finish(token, "今天天气怎么样", 4))
    }

    @Test
    fun emptyFinalFallsBackToLatestPartial() {
        val session = SystemDictationSession()
        val token = assertNotNull(session.begin(4))
        session.observe(token, "打开片库")
        assertTrue(session.release())

        assertEquals("打开片库", session.finish(token, "", 4))
    }

    @Test
    fun changedEditorDiscardsTranscript() {
        val session = SystemDictationSession()
        val token = assertNotNull(session.begin(4))
        session.observe(token, "不该写入新输入框")
        assertTrue(session.release())

        assertNull(session.finish(token, null, 5))
        assertEquals(SystemDictationSession.State.IDLE, session.state)
    }

    @Test
    fun commitHappensOnlyOnce() {
        val session = SystemDictationSession()
        val token = assertNotNull(session.begin(4))
        session.observe(token, "只写一次")
        assertTrue(session.release())

        assertEquals("只写一次", session.finish(token, null, 4))
        assertNull(session.finish(token, null, 4))
    }

    @Test
    fun cancelInvalidatesLateCallbacks() {
        val session = SystemDictationSession()
        val token = assertNotNull(session.begin(4))
        session.cancel()
        session.observe(token, "迟到的结果")

        assertNull(session.finish(token, "迟到的结果", 4))
        assertEquals(SystemDictationSession.State.IDLE, session.state)
    }

    @Test
    fun invalidEditorNeverStarts() {
        val session = SystemDictationSession()

        assertNull(session.begin(0))
        assertEquals(SystemDictationSession.State.IDLE, session.state)
    }
}
