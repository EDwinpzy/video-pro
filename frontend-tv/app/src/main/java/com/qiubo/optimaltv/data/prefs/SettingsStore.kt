package com.qiubo.optimaltv.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 画幅模式（技术方案 §3.4 画面比例切换） */
enum class AspectMode(val label: String, val ratio: Float?) {
    FIT("原始", null),
    ZOOM("裁切填充", null),
    R16_9("16:9", 16f / 9f),
    R4_3("4:3", 4f / 3f),
    ;
    companion object { fun of(i: Int) = entries.getOrElse(i) { FIT } }
}

enum class DanmakuDisplayArea(
    val label: String,
    val heightFraction: Float,
    val maxLanes: Int,
) {
    HALF("半屏", 0.5f, 3),
    FULL("全屏", 1f, 6),
    QUARTER("1/4屏", 0.25f, 2),
    ;
    companion object {
        /** Keep enum ordinals stable for persisted settings while presenting area from small to large. */
        val menuEntries = listOf(QUARTER, HALF, FULL)

        fun of(i: Int) = entries.getOrElse(i) { FULL }
    }
}

data class AppSettings(
    val engineId: String = "media3",
    val aspectOrdinal: Int = 0,
    val speed: Float = 1.0f,
    val danmakuEnabled: Boolean = true,
    val danmakuDisplayAreaOrdinal: Int = DanmakuDisplayArea.FULL.ordinal,
    val danmakuFontScale: Float = 1.0f,
    val danmakuOpacity: Float = 1.0f,
    val danmakuScrollSpeed: Float = 1.0f,
    val skipIntroSeconds: Int = 0,
    val skipOutroSeconds: Int = 0,
    /** Prefer official community intro/outro values, falling back to the manual seconds. */
    val smartSkipEnabled: Boolean = true,
)

class SettingsStore(private val context: Context) {

    private object K {
        val ENGINE = stringPreferencesKey("engine_id")
        val ASPECT = intPreferencesKey("aspect_mode")
        val SPEED = floatPreferencesKey("speed")
        val DANMAKU_ENABLED = booleanPreferencesKey("danmaku_enabled")
        val DANMAKU_DISPLAY_AREA = intPreferencesKey("danmaku_display_area")
        val DANMAKU_FONT_SCALE = floatPreferencesKey("danmaku_font_scale")
        val DANMAKU_OPACITY = floatPreferencesKey("danmaku_opacity")
        val DANMAKU_SCROLL_SPEED = floatPreferencesKey("danmaku_scroll_speed")
        val SKIP_INTRO_SECONDS = intPreferencesKey("skip_intro_seconds")
        val SKIP_OUTRO_SECONDS = intPreferencesKey("skip_outro_seconds")
        val SMART_SKIP_ENABLED = booleanPreferencesKey("smart_skip_enabled")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            engineId = p[K.ENGINE] ?: "media3",
            aspectOrdinal = p[K.ASPECT] ?: 0,
            speed = p[K.SPEED] ?: 1.0f,
            danmakuEnabled = p[K.DANMAKU_ENABLED] ?: true,
            danmakuDisplayAreaOrdinal =
                DanmakuDisplayArea.of(p[K.DANMAKU_DISPLAY_AREA] ?: DanmakuDisplayArea.FULL.ordinal).ordinal,
            danmakuFontScale = (p[K.DANMAKU_FONT_SCALE] ?: 1.0f).coerceIn(0.75f, 1.25f),
            danmakuOpacity = (p[K.DANMAKU_OPACITY] ?: 1.0f).coerceIn(0.3f, 1.0f),
            danmakuScrollSpeed = (p[K.DANMAKU_SCROLL_SPEED] ?: 1.0f).coerceIn(0.5f, 2.0f),
            skipIntroSeconds = SkipSegmentSettings.clamp(p[K.SKIP_INTRO_SECONDS] ?: 0),
            skipOutroSeconds = SkipSegmentSettings.clamp(p[K.SKIP_OUTRO_SECONDS] ?: 0),
            smartSkipEnabled = p[K.SMART_SKIP_ENABLED] ?: true,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setEngine(id: String) = context.dataStore.edit { it[K.ENGINE] = id }
    suspend fun setAspect(ord: Int) = context.dataStore.edit { it[K.ASPECT] = ord }
    suspend fun setSpeed(v: Float) = context.dataStore.edit { it[K.SPEED] = v }
    suspend fun setDanmakuEnabled(enabled: Boolean) =
        context.dataStore.edit { it[K.DANMAKU_ENABLED] = enabled }
    suspend fun setDanmakuDisplayArea(ordinal: Int) =
        context.dataStore.edit { it[K.DANMAKU_DISPLAY_AREA] = DanmakuDisplayArea.of(ordinal).ordinal }
    suspend fun setDanmakuFontScale(scale: Float) =
        context.dataStore.edit { it[K.DANMAKU_FONT_SCALE] = scale.coerceIn(0.75f, 1.25f) }
    suspend fun setDanmakuOpacity(opacity: Float) =
        context.dataStore.edit { it[K.DANMAKU_OPACITY] = opacity.coerceIn(0.3f, 1.0f) }
    suspend fun setDanmakuScrollSpeed(speed: Float) =
        context.dataStore.edit { it[K.DANMAKU_SCROLL_SPEED] = speed.coerceIn(0.5f, 2.0f) }
    suspend fun setSkipIntroSeconds(seconds: Int) =
        context.dataStore.edit { it[K.SKIP_INTRO_SECONDS] = SkipSegmentSettings.clamp(seconds) }
    suspend fun setSkipOutroSeconds(seconds: Int) =
        context.dataStore.edit { it[K.SKIP_OUTRO_SECONDS] = SkipSegmentSettings.clamp(seconds) }
    suspend fun setSmartSkipEnabled(enabled: Boolean) =
        context.dataStore.edit { it[K.SMART_SKIP_ENABLED] = enabled }

    // ---- 线路记忆（技术方案 §3.2-3：每部影片上次成功的线路，下次直选）----
    private fun lineKey(vodId: String) = stringPreferencesKey("line:$vodId")
    private fun vodSourceKey(vodId: String) = stringPreferencesKey("vod_source:$vodId")

    /** 记录格式 "engineId|lineIndex" */
    suspend fun rememberLine(vodId: String, engineId: String, lineIndex: Int) {
        context.dataStore.edit { it[lineKey(vodId)] = "$engineId|$lineIndex" }
    }

    suspend fun lastLine(vodId: String): Pair<String, Int>? =
        context.dataStore.data.first()[lineKey(vodId)]?.split("|")?.let {
            if (it.size == 2) it[0] to (it[1].toIntOrNull() ?: 0) else null
        }

    /** Stable source identity selected for a title, independent of the current line ordering. */
    suspend fun lastVodSource(vodId: String): String? =
        context.dataStore.data.first()[vodSourceKey(vodId)]?.takeIf(String::isNotBlank)

    /** Persist an explicit picker choice immediately, even if that source has not started yet. */
    suspend fun rememberVodSource(vodId: String, sourceIdentity: String) {
        if (sourceIdentity.isBlank()) return
        context.dataStore.edit { it[vodSourceKey(vodId)] = sourceIdentity }
    }

    /** Seed a default after the first successful play without replacing an existing user choice. */
    suspend fun rememberVodSourceIfMissing(vodId: String, sourceIdentity: String) {
        if (sourceIdentity.isBlank()) return
        context.dataStore.edit { preferences ->
            if (preferences[vodSourceKey(vodId)].isNullOrBlank()) {
                preferences[vodSourceKey(vodId)] = sourceIdentity
            }
        }
    }

    /** Clear only account-sensitive title choices; device-level playback preferences stay. */
    suspend fun clearAccountScopedState() {
        context.dataStore.edit { preferences ->
            preferences.asMap().keys
                .filter { isAccountScopedSettingsKey(it.name) }
                .forEach(preferences::removeAccountScopedKey)
        }
    }

    // ---- 全部影视页：上次选中类别（v1.16 需求：进页强制选中上次类别，不回「全部」）----
    private val ALL_LAST_CAT = stringPreferencesKey("all_last_cat")

    suspend fun allLastCat(): String? = context.dataStore.data.first()[ALL_LAST_CAT]

    suspend fun rememberAllLastCat(cat: String) {
        context.dataStore.edit { it[ALL_LAST_CAT] = cat }
    }

    // ---- 影视首页：上次选中分类 chip（v1.17 需求：进页恢复上次分类，不回「电影」）----
    private val VOD_LAST_CAT = stringPreferencesKey("vod_last_cat")

    suspend fun vodLastCat(): String? = context.dataStore.data.first()[VOD_LAST_CAT]

    suspend fun rememberVodLastCat(cat: String) {
        context.dataStore.edit { it[VOD_LAST_CAT] = cat }
    }

}

internal fun isAccountScopedSettingsKey(name: String): Boolean =
    name.startsWith("line:") || name.startsWith("vod_source:")

@Suppress("UNCHECKED_CAST")
private fun MutablePreferences.removeAccountScopedKey(key: Preferences.Key<*>) {
    remove(key as Preferences.Key<Any>)
}
