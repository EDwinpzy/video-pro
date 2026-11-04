package com.dongpiandi.voicehelper.assistant

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AssistantConfig(
    val baseUrl: String,
    val apiKey: String,
    val systemPrompt: String?,
) {
    override fun toString(): String =
        "AssistantConfig(baseUrl=<redacted>, apiKey=<redacted>, " +
            "systemPrompt=${if (systemPrompt.isNullOrBlank()) "<unset>" else "<set>"})"
}

internal object AssistantConfigPolicy {
    private const val INVALID_CONFIGURATION = "语音助手配置无效。"

    fun isApiKeyUsable(apiKey: String): Boolean {
        val trimmed = apiKey.trim()
        return trimmed.isEmpty() || trimmed.all { it in '!'..'~' }
    }

    fun requireUsableApiKey(apiKey: String) {
        if (!isApiKeyUsable(apiKey)) {
            throw AssistantConfigurationException(INVALID_CONFIGURATION)
        }
    }
}

class AssistantConfigStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    @Volatile
    private var preferences: SharedPreferences? = null

    fun get(): AssistantConfig {
        return try {
            val stored = openPreferences()
            AssistantConfig(
                baseUrl = stored.getString(KEY_BASE_URL, "").orEmpty(),
                apiKey = stored.getString(KEY_API_KEY, "").orEmpty(),
                systemPrompt = stored.getString(KEY_SYSTEM_PROMPT, null),
            )
        } catch (_: Exception) {
            throw AssistantConfigurationException(STORAGE_UNAVAILABLE)
        }
    }

    fun save(
        baseUrl: String,
        apiKey: String,
        systemPrompt: String? = null,
    ) {
        val normalizedBaseUrl = ChatEndpointPolicy.normalizeBaseUrl(baseUrl)
        AssistantConfigPolicy.requireUsableApiKey(apiKey)
        val normalizedPrompt = systemPrompt?.trim()?.takeIf(String::isNotEmpty)
        val saved =
            try {
                openPreferences()
                    .edit()
                    .putString(KEY_BASE_URL, normalizedBaseUrl)
                    .putString(KEY_API_KEY, apiKey.trim())
                    .apply {
                        if (normalizedPrompt == null) {
                            remove(KEY_SYSTEM_PROMPT)
                        } else {
                            putString(KEY_SYSTEM_PROMPT, normalizedPrompt)
                        }
                    }
                    .commit()
            } catch (_: Exception) {
                false
            }
        if (!saved) {
            throw AssistantConfigurationException(STORAGE_UNAVAILABLE)
        }
    }

    fun isConfigured(): Boolean =
        try {
            val config = get()
            ChatEndpointPolicy.isValid(config.baseUrl) &&
                AssistantConfigPolicy.isApiKeyUsable(config.apiKey)
        } catch (_: AssistantConfigurationException) {
            false
        }

    fun clear() {
        val cleared =
            try {
                openPreferences().edit().clear().commit()
            } catch (_: Exception) {
                false
            }
        if (!cleared) {
            throw AssistantConfigurationException(STORAGE_UNAVAILABLE)
        }
    }

    private fun openPreferences(): SharedPreferences {
        preferences?.let { return it }
        synchronized(this) {
            preferences?.let { return it }
            return try {
                val masterKey =
                    MasterKey.Builder(appContext)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    PREFERENCES_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                ).also { preferences = it }
            } catch (_: Exception) {
                throw AssistantConfigurationException(STORAGE_UNAVAILABLE)
            }
        }
    }

    private companion object {
        const val PREFERENCES_FILE = "assistant_secure_config"
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_SYSTEM_PROMPT = "system_prompt"
        const val STORAGE_UNAVAILABLE = "语音助手安全配置不可用。"
    }
}
