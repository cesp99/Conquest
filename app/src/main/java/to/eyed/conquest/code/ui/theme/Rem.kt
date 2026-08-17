package to.eyed.conquest.code.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Zed's rem, which is the unit its entire chrome is measured in.
 *
 * gpui sets `window.rem_size = ui_font_size`
 * (`theme_settings/src/settings.rs:619`), so every `rems(x)` and every
 * `DynamicSpacing::BaseNN` in Zed's UI resolves against the user's UI font
 * size rather than against a constant. That is why bumping `ui_font_size` in
 * Zed grows the tab bar, the rows and the gaps together instead of only the
 * text: the numbers *are* multiples of it. A port that hardcodes 16 has the
 * setting do nothing, which is item 24 of the look spec.
 *
 * Chrome should therefore reach for [rem] and [remSp] rather than writing
 * `.dp` literals, using the spec's own table: 1rem = 16dp at the default, so
 * Zed's 32px tab bar is `rem(2f)` and its 4px gap is `rem(0.25f)`.
 */
val LocalUiFontSize = staticCompositionLocalOf { ThemeStore.DEFAULT_UI_FONT_SIZE }

/** `rems(x)` in dp, at the user's UI font size. */
@Composable
@ReadOnlyComposable
fun rem(multiple: Float): Dp = (LocalUiFontSize.current * multiple).dp

/** The same, for a text size. */
@Composable
@ReadOnlyComposable
fun remSp(multiple: Float): TextUnit = (LocalUiFontSize.current * multiple).sp
