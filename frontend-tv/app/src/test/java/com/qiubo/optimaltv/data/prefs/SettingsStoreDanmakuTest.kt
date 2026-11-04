package com.qiubo.optimaltv.data.prefs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsStoreDanmakuTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun danmakuPresentationSettingsPersistTogether() = runBlocking {
        val store = SettingsStore(context)
        val original = store.current()
        try {
            store.setDanmakuDisplayArea(DanmakuDisplayArea.QUARTER.ordinal)
            store.setDanmakuFontScale(0.8f)
            store.setDanmakuOpacity(0.6f)
            store.setDanmakuScrollSpeed(1.5f)

            val restored = store.current()

            assertEquals(DanmakuDisplayArea.QUARTER.ordinal, restored.danmakuDisplayAreaOrdinal)
            assertEquals(0.8f, restored.danmakuFontScale, 0.001f)
            assertEquals(0.6f, restored.danmakuOpacity, 0.001f)
            assertEquals(1.5f, restored.danmakuScrollSpeed, 0.001f)
        } finally {
            store.setDanmakuDisplayArea(original.danmakuDisplayAreaOrdinal)
            store.setDanmakuFontScale(original.danmakuFontScale)
            store.setDanmakuOpacity(original.danmakuOpacity)
            store.setDanmakuScrollSpeed(original.danmakuScrollSpeed)
        }
    }
}
