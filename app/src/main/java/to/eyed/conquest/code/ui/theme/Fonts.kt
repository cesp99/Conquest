package to.eyed.conquest.code.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import to.eyed.conquest.code.R

/**
 * The two typefaces Zed itself ships, vendored from its own assets.
 *
 * Zed's `.ZedSans` is IBM Plex Sans and its `.ZedMono` is Lilex
 * (crates/gpui/src/text_system.rs:1185-1186), and both are SIL Open Font
 * License 1.1 — the licence text travels with them in assets/fonts/. Android's
 * defaults are Roboto and Droid Sans Mono, and no amount of matching sizes and
 * spacing gets to "the same look" while every glyph is a different shape.
 *
 * Only the weights we actually draw are bundled: a full family of both would
 * be 1.6 MB for italics the UI never asks for. Compose synthesises the rest.
 */
val UiFontFamily = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    // Zed's UI has no bold; SemiBold is what it reaches for, and Compose maps
    // a request for Bold onto the nearest weight it has.
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

/**
 * The editor and terminal face. Italic and bold are here because the syntax
 * theme asks for them — One Dark sets comments italic and keywords bold — and
 * a synthesised slant on a monospace face makes code look wrong.
 */
val BufferFontFamily = FontFamily(
    Font(R.font.lilex_regular, FontWeight.Normal),
    Font(R.font.lilex_bold, FontWeight.Bold),
    Font(R.font.lilex_italic, FontWeight.Normal, FontStyle.Italic),
)
