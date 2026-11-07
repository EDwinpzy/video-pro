package dev.jellystack.design.tv

import dev.jellystack.core.preferences.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TvMessageStringsTest {
    @Test
    fun systemLanguageUsesSimplifiedChineseByDefault() {
        val strings = TvStrings.current(AppLanguage.SYSTEM)

        assertEquals("首页", strings.home)
        assertEquals("片库", strings.library)
        assertEquals("片单", strings.discover)
        assertEquals("我的", strings.settings)
        assertEquals("zh_CN", strings.locale.toString())
    }

    @Test
    fun countsOfOneUseTheSingularInEveryLanguage() {
        listOf(AppLanguage.ENGLISH, AppLanguage.GERMAN).forEach { language ->
            val messages = TvStrings.current(language).messages

            assertNotEquals(messages.playingIn(2).replace("2", "1"), messages.playingIn(1), "$language countdown")
            assertNotEquals(
                messages.pinAttemptsRemaining(2).replace("2", "1"),
                messages.pinAttemptsRemaining(1),
                "$language PIN attempts",
            )
        }
    }
}
