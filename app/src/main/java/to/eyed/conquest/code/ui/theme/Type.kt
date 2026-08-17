package to.eyed.conquest.code.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Zed's UI type scale, not Material's.
 *
 * Zed sets `window.rem_size = ui_font_size` and `ui_font_size` defaults to 16,
 * so its `rems(x)` land on round numbers: the label sizes are 1rem / 0.875rem
 * / 0.75rem / 0.625rem — 16, **14**, 12 and 10 — with 14 the one nearly all
 * chrome uses (theme_settings/src/settings.rs:619, assets/settings/default.json:71,
 * ui/src/styles/typography.rs:138-141). Material's defaults are 16sp bodies
 * with tracking, which is why our chrome reads looser and larger than Zed's.
 *
 * **Tracking is zero everywhere.** Zed sets none, anywhere; Material3 ships
 * 0.25sp on bodyMedium and 0.5sp on the labels, and at 12-14sp that is
 * visible — it is most of why a row of ours never quite matched a row of
 * Zed's. Line height follows Zed's default `LineHeightStyle::TextLabel`,
 * which is gpui's φ (1.618), except where a widget asks for `UiLabel`
 * (relative 1.0) and sets its own.
 */
private const val PHI = 1.618034f

private fun ui(sizeSp: Float, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = sizeSp.sp,
    lineHeight = (sizeSp * PHI).sp,
    letterSpacing = 0.sp,
)

val Typography = Typography(
    // Zed's TextSize::Large — the biggest thing chrome uses.
    bodyLarge = ui(16f),
    // TextSize::Default. Tabs, panel rows, menu items, the status bar: if a
    // widget does not say otherwise, it is this.
    bodyMedium = ui(14f),
    // TextSize::Small.
    bodySmall = ui(12f),
    labelLarge = ui(14f, FontWeight.Medium),
    labelMedium = ui(12f),
    // TextSize::XSmall — keybinding chips and the like.
    labelSmall = ui(10f),
    titleLarge = ui(18f, FontWeight.Medium),
    titleMedium = ui(16f, FontWeight.Medium),
    titleSmall = ui(14f, FontWeight.Medium),
)
