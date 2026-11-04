package com.qiubo.optimaltv.dictation

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SystemDictationHostEditorTest {
    @Before
    fun setUp() {
        SystemDictationHostEditorRegistry.clearForTest()
    }

    @After
    fun tearDown() {
        SystemDictationHostEditorRegistry.clearForTest()
    }

    @Test
    fun `commit only reaches the active host editor`() {
        val received = mutableListOf<String>()
        val host =
            SystemDictationHostEditorRegistry.register { text ->
                received += text
                true
            }

        assertFalse(SystemDictationHostEditorRegistry.commit(host.token, "未激活"))
        host.setActive(true)
        assertTrue(SystemDictationHostEditorRegistry.commit(host.token, "天气"))

        host.close()

        assertFalse(SystemDictationHostEditorRegistry.commit(host.token, "关闭后"))
        assertTrue(received == listOf("天气"))
    }

    @Test
    fun `replaced host cannot receive the current session`() {
        val received = mutableListOf<String>()
        val first =
            SystemDictationHostEditorRegistry.register { text ->
                received += "first:$text"
                true
            }
        first.setActive(true)

        val second =
            SystemDictationHostEditorRegistry.register { text ->
                received += "second:$text"
                true
            }
        second.setActive(true)

        assertFalse(SystemDictationHostEditorRegistry.commit(first.token, "旧"))
        assertTrue(SystemDictationHostEditorRegistry.commit(second.token, "新"))
        assertTrue(received == listOf("second:新"))
    }
}
