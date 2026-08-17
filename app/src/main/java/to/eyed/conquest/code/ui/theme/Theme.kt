package to.eyed.conquest.code.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.conquest.code.core.AppSettings

/**
 * No ripple, anywhere.
 *
 * gpui has no transition machinery and Zed's chrome swaps colours instantly on
 * hover and press (ui/src/styles/animation.rs has a vocabulary and almost no
 * callers) — the animated Material ripple is the loudest single "not Zed" tell
 * a Compose port can carry. Widgets that want press feedback draw their own
 * state from the interaction source, exactly as Zed's components restate
 * their hover/active fills.
 */
private object NoIndication : IndicationNodeFactory {
    private class NoopNode : Modifier.Node()
    override fun create(interactionSource: InteractionSource): DelegatableNode = NoopNode()
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

// An IDE has its own visual identity: we deliberately skip Material dynamic
// color so the editor looks like Zed everywhere. All colors come from Zed's
// theme JSON parsed by ZedTheme; Material roles are mapped from the theme's
// style table for the stock components we use.
@Composable
fun ConquestCodeByEyedTheme(
    settings: AppSettings = AppSettings(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val choices by ThemeStore.choices.collectAsState()

    // The stored choices are a SharedPreferences read, so the first frame
    // paints with the defaults and swaps once. That is the right trade: an
    // app that blocks its first frame on disk to avoid one repaint is the
    // worse of the two.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ThemeStore.load(context) } }

    // The mode comes from settings.json; the name for that mode comes from
    // ThemeStore, and a live preview overrides both.
    val isDark = settings.theme.isDark(isSystemInDarkTheme())
    val name = choices.resolve(isDark)
    // Blocking the first time each theme is asked for — one asset read and one
    // JSON parse, as before this file knew about more than one theme. The
    // selector warms the cache when it opens, so previewing down the list
    // never pays it.
    val theme = remember(name) { ZedThemes.get(context, name, isDark) }

    // Material's light/dark base follows the *theme*, not the mode: previewing
    // a light theme while the device is dark must give light scrollbars and
    // ripples too, or half the stock components fight the palette.
    val colorScheme = remember(theme) {
        val base = if (theme.isDark) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = theme.color("text.accent"),
            background = theme.color("editor.background"),
            surface = theme.color("panel.background"),
            surfaceVariant = theme.color("elevated_surface.background"),
            // Material's menus and dialogs paint themselves from the
            // surfaceContainer roles, not from `surface` — left unmapped they
            // leak Material's own purple-tinted defaults into any stock
            // DropdownMenu or AlertDialog (tokens/MenuTokens.kt maps menus to
            // SurfaceContainer). All of them are Zed's elevated surface.
            surfaceContainerLowest = theme.color("elevated_surface.background"),
            surfaceContainerLow = theme.color("elevated_surface.background"),
            surfaceContainer = theme.color("elevated_surface.background"),
            surfaceContainerHigh = theme.color("elevated_surface.background"),
            surfaceContainerHighest = theme.color("elevated_surface.background"),
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
        LocalUiFontSize provides choices.uiFontSize,
        LocalIndication provides NoIndication,
        // Zed's chrome is LTR-only, and ours draws indent guides, tab borders
        // and focus rails at absolute x in drawBehind — mirroring the layout
        // under an RTL locale would put them on the wrong side of rows that
        // did mirror. Pin the direction instead of mirroring every draw.
        LocalLayoutDirection provides LayoutDirection.Ltr,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = remember(choices.uiFontSize) { zedTypography(choices.uiFontSize) },
            content = content
        )
    }
}
