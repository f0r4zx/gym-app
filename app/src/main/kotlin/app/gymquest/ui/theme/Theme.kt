package app.gymquest.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Orange = Color(0xFFFF7A1A)
val Mint = Color(0xFF3DDC97)
val Sky = Color(0xFF4DA3FF)
val Gold = Color(0xFFFFC53D)
val Danger = Color(0xFFFF5C5C)

private val DarkColors = darkColorScheme(
    primary = Orange,
    onPrimary = Color(0xFF1A0E00),
    secondary = Mint,
    onSecondary = Color(0xFF00200F),
    tertiary = Sky,
    background = Color(0xFF0E1320),
    onBackground = Color(0xFFE8ECF4),
    surface = Color(0xFF161D2E),
    onSurface = Color(0xFFE8ECF4),
    surfaceVariant = Color(0xFF1F2940),
    onSurfaceVariant = Color(0xFFA9B4CC),
    error = Danger,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFD9540B),
    onPrimary = Color.White,
    secondary = Color(0xFF0E9F63),
    tertiary = Color(0xFF1F78D1),
    background = Color(0xFFF6F7FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFE9ECF5),
    onSurfaceVariant = Color(0xFF50607D),
    error = Color(0xFFD33A3A),
)

@Composable
fun GymQuestTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
