package dev.jonas.openchat.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.jonas.openchat.core.model.AppSettings
import dev.jonas.openchat.core.model.SearchBackend
import dev.jonas.openchat.core.model.ThemeMode
import dev.jonas.openchat.data.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore(name = "openchat_settings")

class SettingsStore(
    private val context: Context,
    private val secrets: SecretStore,
    private val defaultEndpoint: String,
    private val defaultModel: String,
) {
    private object Keys {
        val endpoint = stringPreferencesKey("endpoint")
        val apiKey = stringPreferencesKey("api_key_cipher")
        val modelId = stringPreferencesKey("model_id")
        val modelName = stringPreferencesKey("model_name")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val temperature = stringPreferencesKey("temperature")
        val maxTokens = stringPreferencesKey("max_tokens")
        val webTools = booleanPreferencesKey("web_tools")
        val searchBackend = stringPreferencesKey("search_backend")
        val searchKey = stringPreferencesKey("search_key_cipher")
        val theme = stringPreferencesKey("theme")
        val sendOnEnter = booleanPreferencesKey("send_on_enter")
        val maxToolRounds = stringPreferencesKey("max_tool_rounds")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs -> prefs.toSettings() }
        .flowOn(Dispatchers.IO)

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        endpointUrl = this[Keys.endpoint]?.takeIf { it.isNotBlank() } ?: defaultEndpoint,
        apiKey = secrets.decrypt(this[Keys.apiKey]).orEmpty(),
        modelId = this[Keys.modelId]?.takeIf { it.isNotBlank() } ?: defaultModel,
        modelName = this[Keys.modelName].orEmpty(),
        systemPrompt = this[Keys.systemPrompt].orEmpty(),
        temperature = this[Keys.temperature]?.toDoubleOrNull(),
        maxTokens = this[Keys.maxTokens]?.toIntOrNull()?.takeIf { it > 0 },
        webToolsEnabled = this[Keys.webTools] ?: false,
        searchBackend = this[Keys.searchBackend]?.let {
            runCatching { SearchBackend.valueOf(it) }.getOrNull()
        } ?: SearchBackend.DUCKDUCKGO,
        searchApiKey = secrets.decrypt(this[Keys.searchKey]).orEmpty(),
        themeMode = this[Keys.theme]?.let {
            runCatching { ThemeMode.valueOf(it) }.getOrNull()
        } ?: ThemeMode.SYSTEM,
        sendOnEnter = this[Keys.sendOnEnter] ?: true,
        maxToolRounds = this[Keys.maxToolRounds]?.toIntOrNull()?.coerceIn(0, 8) ?: 4,
    )

    suspend fun setEndpoint(value: String) = edit { it[Keys.endpoint] = value.trim() }
    suspend fun setApiKey(value: String) = edit {
        it[Keys.apiKey] = if (value.isBlank()) "" else secrets.encrypt(value.trim())
    }
    suspend fun setModel(id: String, name: String = "") = edit {
        it[Keys.modelId] = id.trim()
        it[Keys.modelName] = name.trim()
    }
    suspend fun setSystemPrompt(value: String) = edit { it[Keys.systemPrompt] = value }
    suspend fun setTemperature(value: Double?) = edit {
        if (value == null) it.remove(Keys.temperature) else it[Keys.temperature] = value.toString()
    }
    suspend fun setMaxTokens(value: Int?) = edit {
        if (value == null || value <= 0) it.remove(Keys.maxTokens) else it[Keys.maxTokens] = value.toString()
    }
    suspend fun setWebTools(enabled: Boolean) = edit { it[Keys.webTools] = enabled }
    suspend fun setSearchBackend(value: SearchBackend) = edit {
        it[Keys.searchBackend] = value.name
    }
    suspend fun setSearchApiKey(value: String) = edit {
        it[Keys.searchKey] = if (value.isBlank()) "" else secrets.encrypt(value.trim())
    }
    suspend fun setTheme(value: ThemeMode) = edit { it[Keys.theme] = value.name }
    suspend fun setSendOnEnter(value: Boolean) = edit { it[Keys.sendOnEnter] = value }
    suspend fun setMaxToolRounds(value: Int) = edit {
        it[Keys.maxToolRounds] = value.coerceIn(0, 8).toString()
    }

    /** Applies the settings screen in one transaction so the UI never sees half-saved state. */
    suspend fun updateAll(settings: AppSettings) = edit { prefs ->
        prefs[Keys.endpoint] = settings.endpointUrl.trim()
        prefs[Keys.apiKey] = if (settings.apiKey.isBlank()) "" else secrets.encrypt(settings.apiKey.trim())
        prefs[Keys.modelId] = settings.modelId.trim()
        prefs[Keys.modelName] = settings.modelName.trim()
        prefs[Keys.systemPrompt] = settings.systemPrompt
        if (settings.temperature == null) prefs.remove(Keys.temperature)
        else prefs[Keys.temperature] = settings.temperature.toString()
        if (settings.maxTokens == null || settings.maxTokens <= 0) prefs.remove(Keys.maxTokens)
        else prefs[Keys.maxTokens] = settings.maxTokens.toString()
        prefs[Keys.webTools] = settings.webToolsEnabled
        prefs[Keys.searchBackend] = settings.searchBackend.name
        prefs[Keys.searchKey] = if (settings.searchApiKey.isBlank()) "" else secrets.encrypt(settings.searchApiKey.trim())
        prefs[Keys.theme] = settings.themeMode.name
        prefs[Keys.sendOnEnter] = settings.sendOnEnter
        prefs[Keys.maxToolRounds] = settings.maxToolRounds.coerceIn(0, 8).toString()
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }
}
