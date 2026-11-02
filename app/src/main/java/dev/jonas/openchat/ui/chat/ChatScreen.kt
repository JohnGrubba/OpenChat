package dev.jonas.openchat.ui.chat

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import dev.jonas.openchat.core.model.Conversation
import dev.jonas.openchat.core.model.MessageStatus
import dev.jonas.openchat.core.model.Role
import dev.jonas.openchat.ui.common.FileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }

    var showModelPicker by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    var showClearAll by remember { mutableStateOf(false) }
    var viewImage by remember { mutableStateOf<String?>(null) }
    var cameraOutput by rememberSaveable { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(8),
    ) { uris -> viewModel.attachImages(uris) }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.attachDocuments(uris) }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val file = cameraOutput?.let(::File)
        cameraOutput = null
        scope.launch {
            // Most camera apps honour EXTRA_OUTPUT, so prefer the file they wrote. Some devices
            // ignore EXTRA_OUTPUT and return only a thumbnail in the result data instead.
            if (file != null) {
                val hasOutput = withContext(Dispatchers.IO) { file.length() > 0 }
                if (hasOutput) {
                    viewModel.attachImages(listOf(Uri.fromFile(file)))
                    return@launch
                }
            }

            val extra = result.data?.extras?.get("data")
            val thumbnail = extra as? Bitmap
            val thumbnailUri = extra as? Uri
            if (thumbnail != null) {
                withContext(Dispatchers.IO) { file?.delete() }
                viewModel.attachBitmap(thumbnail)
                return@launch
            }
            if (thumbnailUri != null) {
                withContext(Dispatchers.IO) { file?.delete() }
                viewModel.attachImages(listOf(thumbnailUri))
                return@launch
            }

            // A few camera apps report success slightly before the file has been flushed.
            if (file != null && result.resultCode == Activity.RESULT_OK) {
                val flushed = withContext(Dispatchers.IO) { file.awaitContent() }
                if (flushed) {
                    viewModel.attachImages(listOf(Uri.fromFile(file)))
                    return@launch
                }
            }

            withContext(Dispatchers.IO) { file?.delete() }
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.reportAttachmentError(
                    "The camera app did not save a photo. Try the Photos option instead.",
                )
            }
        }
    }

    val createJson = rememberLauncherForActivityResult(
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
                    Toast.makeText(
                        context,
                        it.message ?: "Export failed",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    fun startCapture() {
        val file = viewModel.newCameraFile()
        val uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        } catch (t: Exception) {
            file.delete()
            viewModel.reportAttachmentError("Could not prepare the camera file.")
            return
        }
        cameraOutput = file.absolutePath
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            // Explicit grants keep camera apps happy on devices where the implicit
            // EXTRA_OUTPUT grant is not applied.
            clipData = ClipData.newRawUri("captured_photo", uri)
            addFlags(
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        try {
            takePicture.launch(intent)
        } catch (e: ActivityNotFoundException) {
            cameraOutput = null
            file.delete()
            viewModel.reportAttachmentError("No camera app is available on this device.")
        } catch (e: Exception) {
            cameraOutput = null
            file.delete()
            viewModel.reportAttachmentError("Could not open the camera app.")
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCapture()
        } else {
            Toast.makeText(context, "Camera permission is needed.", Toast.LENGTH_SHORT).show()
        }
    }

    fun openCamera() {
        showAttachmentSheet = false
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startCapture()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let { message ->
            snackbar.showSnackbar(message)
            viewModel.clearError()
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    val modelId = state.active?.modelId ?: state.settings.modelId
    val modelName = state.models.firstOrNull { it.id == modelId }?.name
        ?: state.settings.modelName.ifBlank { modelId }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                conversations = state.conversations,
                activeId = state.active?.id,
                models = state.models,
                endpointUrl = state.settings.endpointUrl,
                onSelect = { id ->
                    viewModel.selectConversation(id)
                    scope.launch { drawerState.close() }
                },
                onNewChat = {
                    viewModel.newChat()
                    scope.launch { drawerState.close() }
                },
                onSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
                onClose = { scope.launch { drawerState.close() } },
                onPin = viewModel::togglePin,
                onRename = {
                    renameTarget = it
                    scope.launch { drawerState.close() }
                },
                onDelete = {
                    deleteTarget = it
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            modifier = Modifier.imePadding(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, contentDescription = "Open chats")
                        }
                    },
                    title = {
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showModelPicker = true }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = state.active?.title ?: "New chat",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = modelName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Icon(
                                    Icons.Outlined.ExpandMore,
                                    contentDescription = "Change model",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::newChat) {
                            Icon(Icons.Outlined.Add, contentDescription = "New chat")
                        }
                        Box {
                            IconButton(onClick = { showOverflow = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = showOverflow,
                                onDismissRequest = { showOverflow = false },
                            ) {
                                state.active?.let { conversation ->
                                    DropdownMenuItem(
                                        text = { Text("Rename chat") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Edit, contentDescription = null)
                                        },
                                        onClick = {
                                            showOverflow = false
                                            renameTarget = conversation
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (conversation.pinned) "Unpin chat" else "Pin chat",
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.PushPin, contentDescription = null)
                                        },
                                        onClick = {
                                            showOverflow = false
                                            viewModel.togglePin(conversation.id)
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Export as Markdown") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Share, contentDescription = null)
                                    },
                                    enabled = state.messages.isNotEmpty(),
                                    onClick = {
                                        showOverflow = false
                                        FileActions.share(
                                            context,
                                            conversationAsMarkdown(state),
                                            state.active?.title ?: "OpenChat",
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Export as JSON") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Description, contentDescription = null)
                                    },
                                    enabled = state.conversations.isNotEmpty(),
                                    onClick = {
                                        showOverflow = false
                                        createJson.launch("openchat-export.json")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear all chats") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    enabled = state.conversations.isNotEmpty(),
                                    onClick = {
                                        showOverflow = false
                                        showClearAll = true
                                    },
                                )
                                state.active?.let { conversation ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "Delete chat",
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Outlined.Delete,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            showOverflow = false
                                            deleteTarget = conversation
                                        },
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
            bottomBar = {
                Composer(
                    state = state,
                    onInput = viewModel::onInputChange,
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                    onAttach = { showAttachmentSheet = true },
                    onToggleWebTools = viewModel::toggleWebTools,
                    onRemove = viewModel::removePending,
                    onOpenImage = { viewImage = it },
                    modifier = Modifier.navigationBarsPadding(),
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            MessageList(
                state = state,
                listState = listState,
                onRetry = viewModel::retryLast,
                onOpenImage = { viewImage = it },
                onSuggestion = viewModel::onInputChange,
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            models = state.models,
            selectedId = modelId,
            loading = state.modelsLoading,
            error = state.modelError,
            onSelect = viewModel::selectModel,
            onCustomModel = viewModel::setCustomModel,
            onRefresh = { viewModel.refreshModels(force = true) },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showAttachmentSheet) {
        AttachmentSheet(
            onDismiss = { showAttachmentSheet = false },
            onCamera = ::openCamera,
            onPhotos = {
                showAttachmentSheet = false
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onFiles = {
                showAttachmentSheet = false
                documentPicker.launch(arrayOf("*/*"))
            },
        )
    }

    renameTarget?.let { conversation ->
        RenameDialog(
            initial = conversation.title,
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                viewModel.renameConversation(conversation.id, title)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete chat?") },
            text = { Text("\"${conversation.title}\" and its messages will be removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteConversation(conversation.id)
                        deleteTarget = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text("Clear all chats?") },
            text = { Text("This removes every conversation and attachment reference.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAll()
                        showClearAll = false
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

    viewImage?.let { path ->
        ImageViewerDialog(path = path, onDismiss = { viewImage = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentSheet(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text("Add to this chat", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(14.dp))
            AttachmentOption(
                icon = Icons.Outlined.CameraAlt,
                title = "Camera",
                subtitle = "Take a photo",
                onClick = onCamera,
            )
            AttachmentOption(
                icon = Icons.Outlined.Image,
                title = "Photos",
                subtitle = "Choose images from your library",
                onClick = onPhotos,
            )
            AttachmentOption(
                icon = Icons.Outlined.Description,
                title = "Files",
                subtitle = "PDF, DOCX, PPTX, XLSX, text and code",
                onClick = onFiles,
            )
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun AttachmentOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImageViewerDialog(path: String, onDismiss: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    val transformState = rememberTransformableState { zoomChange, _, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = File(path),
                contentDescription = "Image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale)
                    .transformable(transformState),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

private fun conversationAsMarkdown(state: ChatUiState): String = buildString {
    append("# ").append(state.active?.title ?: "OpenChat").append("\n\n")
    append("Model: ").append(state.active?.modelId ?: state.settings.modelId).append("\n\n")
    state.messages.forEach { message ->
        when (message.role) {
            Role.USER -> {
                append("## You\n\n")
                message.blocks.filterIsInstance<dev.jonas.openchat.core.model.ContentBlock.Document>()
                    .forEach { append("*Attached: ").append(it.name).append("*\n\n") }
                append(message.text).append("\n\n")
            }
            Role.ASSISTANT -> {
                if (message.text.isNotBlank()) {
                    append("## Assistant\n\n")
                    append(message.text).append("\n\n")
                }
            }
            else -> Unit
        }
    }
}

/**
 * Some camera apps deliver RESULT_OK just before the output file is fully flushed. Give them a
 * short grace period instead of failing the attachment immediately.
 */
private suspend fun File.awaitContent(timeoutMs: Long = 1500): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (length() > 0) return true
        delay(50)
    }
    return length() > 0
}
