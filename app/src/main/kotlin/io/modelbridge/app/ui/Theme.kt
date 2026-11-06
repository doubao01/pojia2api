package io.modelbridge.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE8FF),
    secondary = Color(0xFF0E7490),
    background = Color(0xFFF7F8FA),
    surface = Color.White,
    error = Color(0xFFDC2626),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7AA5FF),
    onPrimary = Color(0xFF0B1B33),
    primaryContainer = Color(0xFF1D3557),
    secondary = Color(0xFF4FD1C5),
    background = Color(0xFF101318),
    surface = Color(0xFF181C22),
    error = Color(0xFFF87171),
)

/**
 * App 主题。Android 12+ 跟随系统动态取色，否则用预设配色。
 */
@Composable
fun BridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
