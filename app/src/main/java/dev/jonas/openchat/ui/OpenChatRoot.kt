package dev.jonas.openchat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.jonas.openchat.di.AppContainer
import dev.jonas.openchat.ui.chat.ChatScreen
import dev.jonas.openchat.ui.chat.ChatViewModel
import dev.jonas.openchat.ui.settings.SettingsScreen
import dev.jonas.openchat.ui.theme.OpenChatTheme

@Composable
fun OpenChatRoot(container: AppContainer) {
    val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(container))
    val state by chatViewModel.uiState.collectAsStateWithLifecycle()
    OpenChatTheme(themeMode = state.settings.themeMode) {
        val navController = rememberNavController()
        NavHost(navController = navController, startDestination = "chat") {
            composable("chat") {
                ChatScreen(
                    state = state,
                    viewModel = chatViewModel,
                    onOpenSettings = { navController.navigate("settings") },
                )
            }
            composable("settings") {
                SettingsScreen(
                    state = state,
                    viewModel = chatViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
