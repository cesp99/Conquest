package to.eyed.conquest.code.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// An IDE has its own visual identity: we deliberately skip Material dynamic
// color so the editor looks like Zed everywhere. Real theme support (Zed
// theme JSON) replaces these hardcoded schemes later.
private val DarkColorScheme = darkColorScheme(
    primary = ZedDark.Accent,
    secondary = ZedDark.AccentAlt,
    background = ZedDark.Background,
    surface = ZedDark.Surface,
    surfaceVariant = ZedDark.SurfaceElevated,
    outline = ZedDark.Border,
    onPrimary = ZedDark.Background,
    onBackground = ZedDark.Text,
    onSurface = ZedDark.Text,
    onSurfaceVariant = ZedDark.TextMuted,
    error = ZedDark.Error,
)

private val LightColorScheme = lightColorScheme(
    primary = ZedLight.Accent,
    secondary = ZedLight.AccentAlt,
    background = ZedLight.Background,
    surface = ZedLight.Surface,
    surfaceVariant = ZedLight.SurfaceElevated,
    outline = ZedLight.Border,
    onPrimary = ZedLight.Background,
    onBackground = ZedLight.Text,
    onSurface = ZedLight.Text,
    onSurfaceVariant = ZedLight.TextMuted,
    error = ZedLight.Error,
)

@Composable
fun ConquestCodeByEyedTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
