package app.repcoach.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Gold = Color(0xFFF5B841)
private val Navy = Color(0xFF1F2A44)

@Composable
fun RepCoachTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Gold, onPrimary = Navy, secondary = Color(0xFF9DB4E0))
    } else {
        lightColorScheme(primary = Navy, onPrimary = Color.White, secondary = Color(0xFFB07A00))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
