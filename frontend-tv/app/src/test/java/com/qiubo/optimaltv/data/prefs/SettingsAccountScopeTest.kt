package com.qiubo.optimaltv.data.prefs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAccountScopeTest {
    @Test
    fun titleLineAndSourceMemoryAreAccountScoped() {
        assertTrue(isAccountScopedSettingsKey("line:dongpian:variant"))
        assertTrue(isAccountScopedSettingsKey("vod_source:dongpian:variant"))
    }

    @Test
    fun devicePlaybackAndDanmakuPreferencesSurviveAccountSwitch() {
        assertFalse(isAccountScopedSettingsKey("speed"))
        assertFalse(isAccountScopedSettingsKey("danmaku_enabled"))
        assertFalse(isAccountScopedSettingsKey("iptv_last_url"))
        assertFalse(isAccountScopedSettingsKey("all_last_cat"))
    }
}
