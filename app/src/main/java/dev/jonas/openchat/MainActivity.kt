package dev.jonas.openchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.jonas.openchat.di.AppContainer
import dev.jonas.openchat.ui.OpenChatRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container: AppContainer = (application as OpenChatApplication).container
        setContent {
            OpenChatRoot(container)
        }
    }
}
