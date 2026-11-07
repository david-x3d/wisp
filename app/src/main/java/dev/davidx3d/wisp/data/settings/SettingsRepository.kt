package dev.davidx3d.wisp.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val secrets = SecretStore()
    private val store get() = context.dataStore

    val settings: Flow<AppSettings> = store.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun apiKey(provider: Provider): String? {
        val stored = store.data.first()[keyFor(provider)] ?: return null
        return secrets.decrypt(stored)?.takeIf { it.isNotBlank() }
    }

    suspend fun setApiKey(provider: Provider, key: String) {
        store.edit {
            val trimmed = key.trim()
            if (trimmed.isEmpty()) it.remove(keyFor(provider)) else it[keyFor(provider)] = secrets.encrypt(trimmed)
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val old = prefs.toSettings()
            val new = transform(old)
            prefs[K.provider] = new.provider.name
            prefs[K.xaiModel] = new.xaiModel
            prefs[K.openAiModel] = new.openAiModel
            prefs[K.xaiVoice] = new.xaiVoice
            prefs[K.openAiVoice] = new.openAiVoice
            prefs[K.language] = new.language
            prefs[K.systemPrompt] = new.systemPrompt
            prefs[K.turnMode] = new.turnMode.name
            prefs[K.vadSensitivity] = new.vadSensitivity
            prefs[K.xaiReasoning] = new.xaiReasoning
            prefs[K.webSearch] = new.webSearch
            prefs[K.disabledTools] = new.disabledTools
            prefs[K.confirmActions] = new.confirmActions
            prefs[K.rootEnabled] = new.rootEnabled
            prefs[K.systemControl] = new.systemControlEnabled
            prefs[K.speaker] = new.speakerByDefault
        }
    }

    suspend fun addUsage(seconds: Long, costMicroUsd: Long) {
        store.edit {
            it[K.totalSeconds] = (it[K.totalSeconds] ?: 0) + seconds
            it[K.totalCost] = (it[K.totalCost] ?: 0) + costMicroUsd
        }
    }

    suspend fun resetUsage() {
        store.edit {
            it[K.totalSeconds] = 0
            it[K.totalCost] = 0
        }
    }

    private fun keyFor(provider: Provider) = if (provider == Provider.XAI) K.xaiKey else K.openAiKey

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            provider = this[K.provider]?.let { runCatching { Provider.valueOf(it) }.getOrNull() } ?: d.provider,
            xaiModel = this[K.xaiModel] ?: d.xaiModel,
            openAiModel = this[K.openAiModel] ?: d.openAiModel,
            xaiVoice = this[K.xaiVoice] ?: d.xaiVoice,
            openAiVoice = this[K.openAiVoice] ?: d.openAiVoice,
            language = this[K.language] ?: d.language,
            systemPrompt = this[K.systemPrompt] ?: d.systemPrompt,
            turnMode = this[K.turnMode]?.let { runCatching { TurnMode.valueOf(it) }.getOrNull() } ?: d.turnMode,
            vadSensitivity = this[K.vadSensitivity] ?: d.vadSensitivity,
            xaiReasoning = this[K.xaiReasoning] ?: d.xaiReasoning,
            webSearch = this[K.webSearch] ?: d.webSearch,
            disabledTools = this[K.disabledTools] ?: d.disabledTools,
            confirmActions = this[K.confirmActions] ?: d.confirmActions,
            rootEnabled = this[K.rootEnabled] ?: d.rootEnabled,
            systemControlEnabled = this[K.systemControl] ?: d.systemControlEnabled,
            speakerByDefault = this[K.speaker] ?: d.speakerByDefault,
            totalSeconds = this[K.totalSeconds] ?: 0,
            totalCostMicroUsd = this[K.totalCost] ?: 0,
            hasXaiKey = this[K.xaiKey] != null,
            hasOpenAiKey = this[K.openAiKey] != null,
        )
    }

    private object K {
        val provider = stringPreferencesKey("provider")
        val xaiModel = stringPreferencesKey("xai_model")
        val openAiModel = stringPreferencesKey("openai_model")
        val xaiVoice = stringPreferencesKey("xai_voice")
        val openAiVoice = stringPreferencesKey("openai_voice")
        val language = stringPreferencesKey("language")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val turnMode = stringPreferencesKey("turn_mode")
        val vadSensitivity = floatPreferencesKey("vad_sensitivity")
        val xaiReasoning = booleanPreferencesKey("xai_reasoning")
        val webSearch = booleanPreferencesKey("web_search")
        val disabledTools = stringSetPreferencesKey("disabled_tools")
        val confirmActions = booleanPreferencesKey("confirm_actions")
        val rootEnabled = booleanPreferencesKey("root_enabled")
        val systemControl = booleanPreferencesKey("system_control")
        val speaker = booleanPreferencesKey("speaker")
        val totalSeconds = longPreferencesKey("total_seconds")
        val totalCost = longPreferencesKey("total_cost_micro_usd")
        val xaiKey = stringPreferencesKey("xai_key_enc")
        val openAiKey = stringPreferencesKey("openai_key_enc")
    }
}
