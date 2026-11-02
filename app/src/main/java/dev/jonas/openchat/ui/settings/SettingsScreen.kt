package dev.jonas.openchat.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jonas.openchat.BuildConfig
import dev.jonas.openchat.core.api.EndpointResolver
import dev.jonas.openchat.core.model.AppSettings
import dev.jonas.openchat.core.model.SearchBackend
import dev.jonas.openchat.core.model.ThemeMode
import dev.jonas.openchat.ui.chat.ChatUiState
import dev.jonas.openchat.ui.chat.ChatViewModel
import dev.jonas.openchat.ui.chat.ModelPickerSheet
import dev.jonas.openchat.ui.common.FileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: ChatUiState,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = state.settings

    var endpoint by rememberSaveable { mutableStateOf(settings.endpointUrl) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var modelId by rememberSaveable { mutableStateOf(settings.modelId) }
    var modelName by rememberSaveable { mutableStateOf(settings.modelName) }
    var systemPrompt by rememberSaveable { mutableStateOf(settings.systemPrompt) }
    var useDefaultTemperature by rememberSaveable {
        mutableStateOf(settings.temperature == null)
    }
    var temperature by rememberSaveable {
        mutableStateOf((settings.temperature ?: 0.7).toFloat())
    }
    var maxTokens by rememberSaveable {
        mutableStateOf(settings.maxTokens?.toString().orEmpty())
    }
    var webTools by rememberSaveable { mutableStateOf(settings.webToolsEnabled) }
    var searchBackendName by rememberSaveable { mutableStateOf(settings.searchBackend.name) }
    var searchKey by remember { mutableStateOf(settings.searchApiKey) }
    var sendOnEnter by rememberSaveable { mutableStateOf(settings.sendOnEnter) }
    var maxRounds by rememberSaveable { mutableStateOf(settings.maxToolRounds) }
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    var showSearchKey by rememberSaveable { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showClearAll by remember { mutableStateOf(false) }
    var initialized by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.settingsLoaded) {
        if (state.settingsLoaded && !initialized) {
            endpoint = settings.endpointUrl
            apiKey = settings.apiKey
            modelId = settings.modelId
            modelName = settings.modelName
            systemPrompt = settings.systemPrompt
            useDefaultTemperature = settings.temperature == null
            temperature = (settings.temperature ?: 0.7).toFloat()
            maxTokens = settings.maxTokens?.toString().orEmpty()
            webTools = settings.webToolsEnabled
            searchBackendName = settings.searchBackend.name
            searchKey = settings.searchApiKey
            sendOnEnter = settings.sendOnEnter
            maxRounds = settings.maxToolRounds
            initialized = true
        }
    }

    val resolved = remember(endpoint, modelId) {
        EndpointResolver.resolve(endpoint, modelId)
    }
    val searchBackend = SearchBackend.valueOf(searchBackendName)

    fun apply() {
        val resolution = resolved.getOrElse {
            Toast.makeText(context, it.message ?: "Invalid endpoint", Toast.LENGTH_LONG).show()
            return
        }
        val parsedMaxTokens = maxTokens.trim().let { raw ->
            if (raw.isEmpty()) null else raw.toIntOrNull()
        }
        if (maxTokens.isNotBlank() && (parsedMaxTokens == null || parsedMaxTokens <= 0)) {
            Toast.makeText(context, "Max tokens must be a positive number", Toast.LENGTH_SHORT).show()
            return
        }
        val name = modelName.ifBlank {
            state.models.firstOrNull { it.id == modelId }?.name.orEmpty()
        }
        viewModel.saveSettings(
            settings.copy(
                endpointUrl = resolution.normalizedInput,
                apiKey = apiKey.trim(),
                modelId = modelId.trim().ifBlank { settings.modelId },
                modelName = name,
                systemPrompt = systemPrompt,
                temperature = if (useDefaultTemperature) null else temperature.toDouble(),
                maxTokens = parsedMaxTokens,
                webToolsEnabled = webTools,
                searchBackend = searchBackend,
                searchApiKey = searchKey.trim(),
                sendOnEnter = sendOnEnter,
                maxToolRounds = maxRounds,
            ),
        )
        Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
    }

    val exportJson = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val json = viewModel.exportJson()
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error("Could not write the file")
                    }
                }.onSuccess {
                    Toast.makeText(context, "Chats exported", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, it.message ?: "Export failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .imePadding()
            .navigationBarsPadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Settings") },
                actions = {
                    TextButton(onClick = { apply() }) { Text("Save") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .testTag("settings_list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                SettingsSection(
                    title = "Connection",
                    subtitle = "Point OpenChat at your OpenCode Go endpoint. Your key stays encrypted on this device.",
                ) {
                    AssistChip(
                        onClick = { endpoint = EndpointResolver.OPENCODE_GO_BASE },
                        label = { Text("Use OpenCode Go endpoint") },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Language,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Endpoint URL") },
                        placeholder = { Text("https://opencode.ai/inference/go") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        supportingText = {
                            val support = resolved.fold(
                                onSuccess = {
                                    "Detected ${it.family.label} · ${it.chatUrl}"
                                },
                                onFailure = { it.message ?: "Invalid endpoint" },
                            )
                            Text(
                                text = support,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = if (resolved.isSuccess) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        },
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API key") },
                        placeholder = { Text("oc_sk_…") },
                        singleLine = true,
                        visualTransformation = if (showApiKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    if (showApiKey) {
                                        Icons.Outlined.VisibilityOff
                                    } else {
                                        Icons.Outlined.Visibility
                                    },
                                    contentDescription = if (showApiKey) "Hide key" else "Show key",
                                )
                            }
                        },
                        supportingText = {
                            Text(
                                "Sent as a bearer token with every request. Never logged. " +
                                    "It is verified on your first message.",
                            )
                        },
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Encrypted with the Android Keystore",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SettingsSection(
                    title = "Model",
                    subtitle = "Choose a model from your endpoint or type any model id.",
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showModelPicker = true
                                viewModel.refreshModels(force = true)
                            },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = modelName.ifBlank {
                                        state.models.firstOrNull { it.id == modelId }?.name
                                            ?: modelId
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = modelId,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                Icons.Outlined.ChevronRight,
                                contentDescription = "Browse models",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = modelId,
                        onValueChange = {
                            modelId = it
                            modelName = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Model id") },
                        singleLine = true,
                        supportingText = {
                            Text("Example: deepseek-v4.1-flash or kimi-k3")
                        },
                    )
                    if (state.modelsLoading) {
                        Text(
                            text = "Loading models…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else if (state.modelError != null) {
                        Text(
                            text = state.modelError,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            item {
                SettingsSection(
                    title = "Generation",
                    subtitle = "Leave values at their defaults for the model's recommended behavior.",
                ) {
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("System prompt") },
                        placeholder = { Text("Optional instructions for every chat") },
                        minLines = 3,
                        maxLines = 8,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Use default temperature", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Otherwise send an explicit value",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = useDefaultTemperature,
                            onCheckedChange = { useDefaultTemperature = it },
                        )
                    }
                    Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..2f,
                        steps = 19,
                        enabled = !useDefaultTemperature,
                    )
                    Text(
                        text = if (useDefaultTemperature) {
                            "Temperature: provider default"
                        } else {
                            "Temperature: " + String.format(Locale.US, "%.1f", temperature)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = maxTokens,
                        onValueChange = { raw ->
                            if (raw.all { it.isDigit() }) maxTokens = raw
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Max output tokens") },
                        placeholder = { Text("Provider default") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text("Leave empty to use the provider default") },
                    )
                }
            }

            item {
                SettingsSection(
                    title = "Web tools",
                    subtitle = "Search and page fetching run locally on your phone. Results are sent back to the model.",
                ) {
                    SwitchRow(
                        title = "Web tools for new chats",
                        subtitle = "You can also toggle this per conversation from the composer.",
                        checked = webTools,
                        onCheckedChange = { webTools = it },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Text("Search backend", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchBackend.entries.forEach { backend ->
                            FilterChip(
                                selected = searchBackend == backend,
                                onClick = { searchBackendName = backend.name },
                                label = { Text(backend.label) },
                            )
                        }
                    }
                    if (searchBackend == SearchBackend.BRAVE) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = searchKey,
                            onValueChange = { searchKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Brave Search API key") },
                            singleLine = true,
                            visualTransformation = if (showSearchKey) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { showSearchKey = !showSearchKey }) {
                                    Icon(
                                        if (showSearchKey) {
                                            Icons.Outlined.VisibilityOff
                                        } else {
                                            Icons.Outlined.Visibility
                                        },
                                        contentDescription = "Toggle search key",
                                    )
                                }
                            },
                        )
                    } else {
                        Text(
                            text = "DuckDuckGo works without a key. If it is rate limited, add a Brave key.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Max tool rounds: $maxRounds",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = maxRounds.toFloat(),
                        onValueChange = { maxRounds = it.toInt() },
                        valueRange = 0f..8f,
                        steps = 7,
                    )
                    Text(
                        text = if (maxRounds == 0) {
                            "Web tools are disabled for chats. Set at least 1 to let the model use them."
                        } else {
                            "The model can use up to $maxRounds tool rounds per reply."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SettingsSection(
                    title = "Appearance",
                    subtitle = null,
                ) {
                    Text("Theme", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { viewModel.setTheme(mode) },
                                label = {
                                    Text(
                                        when (mode) {
                                            ThemeMode.SYSTEM -> "System"
                                            ThemeMode.LIGHT -> "Light"
                                            ThemeMode.DARK -> "Dark"
                                        },
                                    )
                                },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    SwitchRow(
                        title = "Send with Enter",
                        subtitle = "Turn off to use Enter for new lines.",
                        checked = sendOnEnter,
                        onCheckedChange = { sendOnEnter = it },
                    )
                }
            }

            item {
                SettingsSection(
                    title = "Data",
                    subtitle = "Chats are stored only on this device.",
                ) {
                    OutlinedButton(
                        onClick = { exportJson.launch("openchat-export.json") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Outlined.Description,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export all chats as JSON")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showClearAll = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Clear all chats",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            item {
                SettingsSection(title = "About", subtitle = null) {
                    Text(
                        text = "OpenChat ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "A clean, local-first client for OpenAI-compatible, Anthropic, " +
                            "Responses and Gemini endpoints. Built for OpenCode Go.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            FileActions.openUrl(
                                context,
                                "https://opencode.ai/v2/docs/console/go",
                            )
                        },
                    ) {
                        Text("OpenCode Go docs")
                    }
                    Text(
                        text = "Each conversation sends a stable x-opencode-session header so the " +
                            "gateway can route and cache it correctly.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            models = state.models,
            selectedId = modelId,
            loading = state.modelsLoading,
            error = state.modelError,
            onSelect = { model ->
                modelId = model.id
                modelName = model.name
            },
            onCustomModel = { id ->
                modelId = id
                modelName = ""
            },
            onRefresh = { viewModel.refreshModels(force = true) },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text("Clear all chats?") },
            text = { Text("This removes every conversation, message and attachment reference.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAll()
                        showClearAll = false
                        Toast.makeText(context, "Chats cleared", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text("Clear all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAll = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                content()
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
