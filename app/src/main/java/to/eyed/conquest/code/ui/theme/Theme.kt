package to.eyed.conquest.code.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import to.eyed.conquest.code.core.AppSettings
import to.eyed.conquest.code.core.ThemeMode

// An IDE has its own visual identity: we deliberately skip Material dynamic
// color so the editor looks like Zed everywhere. All colors come from Zed's
// theme JSON (One Dark / One Light) parsed by ZedTheme; Material roles are
// mapped from the theme's style table for the stock components we use.
@Composable
fun ConquestCodeByEyedTheme(
    settings: AppSettings = AppSettings(),
    content: @Composable () -> Unit
) {
    // "system" follows the device; the other two pin it.
    val darkTheme = when (settings.theme) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val theme = remember(darkTheme) { ZedTheme.load(context, darkTheme) }
    val colorScheme = remember(theme) {
        val base = if (darkTheme) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = theme.color("text.accent"),
            background = theme.color("editor.background"),
            surface = theme.color("panel.background"),
            surfaceVariant = theme.color("elevated_surface.background"),
            outline = theme.color("border"),
            outlineVariant = theme.color("border.variant"),
            onPrimary = theme.color("editor.background"),
            onBackground = theme.color("text"),
            onSurface = theme.color("text"),
            onSurfaceVariant = theme.color("text.muted"),
            error = theme.color("error"),
        )
    }
    CompositionLocalProvider(
        LocalZedTheme provides theme,
        LocalAppSettings provides settings,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
