package dev.jonas.openchat.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.jonas.openchat.core.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E9F6E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F5E8),
    onPrimaryContainer = Color(0xFF003827),
    secondary = Color(0xFF3F6F5E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1F0E9),
    onSecondaryContainer = Color(0xFF12251E),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF15171A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15171A),
    surfaceVariant = Color(0xFFF2F4F5),
    onSurfaceVariant = Color(0xFF5D6369),
    outline = Color(0xFFE1E5E8),
    outlineVariant = Color(0xFFEDF0F2),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4ADE9C),
    onPrimary = Color(0xFF003825),
    primaryContainer = Color(0xFF0A4633),
    onPrimaryContainer = Color(0xFFB9F5D9),
    secondary = Color(0xFF9BD2BC),
    onSecondary = Color(0xFF0A3728),
    secondaryContainer = Color(0xFF1D4A3B),
    onSecondaryContainer = Color(0xFFC6EEDD),
    background = Color(0xFF0F0F11),
    onBackground = Color(0xFFECEDEF),
    surface = Color(0xFF0F0F11),
    onSurface = Color(0xFFECEDEF),
    surfaceVariant = Color(0xFF1A1B1E),
    onSurfaceVariant = Color(0xFFA5A9AF),
    outline = Color(0xFF2B2D32),
    outlineVariant = Color(0xFF222428),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF4E1512),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val OpenChatTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp,
    ),
)

@Composable
fun OpenChatTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = OpenChatTypography, content = content)
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
