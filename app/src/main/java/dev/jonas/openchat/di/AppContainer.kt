package dev.jonas.openchat.di

import android.content.Context
import dev.jonas.openchat.BuildConfig
import dev.jonas.openchat.core.tool.LocalToolExecutor
import dev.jonas.openchat.core.tool.WebFetchClient
import dev.jonas.openchat.core.tool.WebSearchClient
import dev.jonas.openchat.data.ChatRepository
import dev.jonas.openchat.data.attachment.AttachmentStore
import dev.jonas.openchat.data.db.AppDatabase
import dev.jonas.openchat.data.prefs.SettingsStore
import dev.jonas.openchat.data.security.SecretStore
import dev.jonas.openchat.network.InferenceClient
import dev.jonas.openchat.network.ModelService
import kotlinx.serialization.json.Json

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    val secretStore = SecretStore()

    val settingsStore = SettingsStore(
        context = appContext,
        secrets = secretStore,
        defaultEndpoint = BuildConfig.DEFAULT_ENDPOINT,
        defaultModel = BuildConfig.DEFAULT_MODEL,
    )

    private val database = AppDatabase.create(appContext)
    val repository = ChatRepository(database.chatDao(), json)

    val inferenceClient = InferenceClient(
        client = InferenceClient.defaultClient(),
        userAgent = BuildConfig.USER_AGENT,
    )

    val modelService = ModelService(
        client = ModelService.defaultClient(),
        userAgent = BuildConfig.USER_AGENT,
        json = json,
    )

    val attachmentStore = AttachmentStore(appContext)

    val toolExecutor = LocalToolExecutor(
        searchClient = WebSearchClient(WebSearchClient.defaultClient(), json),
        fetchClient = WebFetchClient(WebFetchClient.defaultClient()),
    )
}
