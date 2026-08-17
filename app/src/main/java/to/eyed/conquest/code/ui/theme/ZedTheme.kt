package to.eyed.conquest.code.ui.theme

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONObject

/**
 * A parsed Zed theme. The source of truth is Zed's theme JSON
 * (assets/themes/one.json, vendored from the Zed repository — GPL, see
 * README credits); no hardcoded palettes remain in the app.
 */
class ZedTheme(
    val name: String,
    val isDark: Boolean,
    private val colors: Map<String, Color>,
    syntax: Map<String, SyntaxStyle>,
    val cursor: Color,
    val selection: Color,
) {
    data class SyntaxStyle(val color: Color?, val italic: Boolean, val bold: Boolean)

    /**
     * Style-table lookup by Zed style key, e.g. `"editor.background"`.
     *
     * A miss falls back to [DERIVED], then to magenta. The derivations are not
     * invention: a dozen keys Zed's own themes never write — the indent
     * guides, the minimap, `pane_group.border` — are filled by its Rust
     * deserializer from `ThemeColors::dark()`, and a theme JSON that omits
     * them is normal rather than broken. Without this table the first indent
     * guide we draw is magenta.
     */
    fun color(key: String, fallback: Color = Color.Magenta): Color =
        colors[key] ?: DERIVED[key]?.let { colors[it] } ?: fallback

    /** Compose span styles indexed by engine style id. */
    private val spanStyles: List<SpanStyle?> = STYLE_NAMES.map { name ->
        syntax[name]?.let { style ->
            SpanStyle(
                color = style.color ?: Color.Unspecified,
                fontWeight = if (style.bold) FontWeight.Bold else null,
                fontStyle = if (style.italic) FontStyle.Italic else null,
            )
        }
    }

    fun spanStyle(styleId: Int): SpanStyle? = spanStyles.getOrNull(styleId)

    companion object {
        /**
         * Keys Zed's themes leave out, and the key each one borrows from.
         *
         * Zed fills these in Rust from `ThemeColors::dark()`
         * (crates/theme/src/default_colors.rs) rather than in the JSON, so
         * even its own One Dark omits every one of them. The borrowings below
         * follow those defaults' *relationships* — an indent guide is the
         * quiet border, an active one the ordinary border — rather than
         * hardcoding One Dark's hexes, so a user's own theme stays coherent.
         */
        private val DERIVED = mapOf(
            "editor.indent_guide" to "border.variant",
            "editor.indent_guide_active" to "border",
            "panel.indent_guide" to "border.variant",
            "panel.indent_guide_hover" to "border",
            "panel.indent_guide_active" to "border.selected",
            "pane_group.border" to "border",
            "scrollbar.thumb.active_background" to "scrollbar.thumb.hover_background",
            "minimap.thumb.background" to "scrollbar.thumb.background",
            "minimap.thumb.hover_background" to "scrollbar.thumb.hover_background",
            "minimap.thumb.active_background" to "scrollbar.thumb.hover_background",
            "minimap.thumb.border" to "scrollbar.thumb.border",
            "drop_target.border" to "border.selected",
            "editor.document_highlight.bracket_background"
                to "editor.document_highlight.read_background",
            "editor.debugger_active_line.background" to "editor.highlighted_line.background",
            "debugger.accent" to "text.accent",
            "terminal.ansi.background" to "terminal.background",
            "panel.overlay_background" to "elevated_surface.background",
            "panel.overlay_hover" to "element.hover",
        )

        /**
         * Mirrors `STYLE_NAMES` in `core/crates/engine/src/highlight.rs` —
         * the engine's highlight style ids index this list. Keep in sync.
         */
        private val STYLE_NAMES = listOf(
            "attribute", "boolean", "comment", "comment.doc", "constant",
            "constructor", "embedded", "emphasis", "emphasis.strong", "enum",
            "function", "keyword", "label", "link_text", "link_uri", "number",
            "operator", "preproc", "property", "punctuation",
            "punctuation.bracket", "punctuation.delimiter",
            "punctuation.list_marker", "punctuation.special", "string",
            "string.escape", "string.regex", "string.special",
            "string.special.symbol", "tag", "text.literal", "title", "type",
            "variable", "variable.special",
        )

        /**
         * Load the theme matching [dark] from the bundled theme family
         * (One Dark / One Light).
         */
        fun load(context: Context, dark: Boolean): ZedTheme {
            val json = context.assets.open("themes/one.json")
                .bufferedReader()
                .use { it.readText() }
            return parse(json, dark)
        }

        internal fun parse(json: String, dark: Boolean): ZedTheme {
            val family = JSONObject(json)
            val themes = family.getJSONArray("themes")
            val wanted = if (dark) "dark" else "light"
            var chosen: JSONObject? = null
            for (i in 0 until themes.length()) {
                val theme = themes.getJSONObject(i)
                if (theme.getString("appearance") == wanted) {
                    chosen = theme
                    break
                }
            }
            val theme = requireNotNull(chosen) { "no $wanted theme in family" }
            val style = theme.getJSONObject("style")

            val colors = mutableMapOf<String, Color>()
            for (key in style.keys()) {
                val value = style.opt(key)
                if (value is String && value.startsWith("#")) {
                    parseColor(value)?.let { colors[key] = it }
                }
            }

            val syntax = mutableMapOf<String, SyntaxStyle>()
            val syntaxJson = style.optJSONObject("syntax") ?: JSONObject()
            for (key in syntaxJson.keys()) {
                val entry = syntaxJson.getJSONObject(key)
                syntax[key] = SyntaxStyle(
                    color = entry.optString("color").takeIf { it.startsWith("#") }
                        ?.let(::parseColor),
                    italic = entry.optString("font_style") == "italic",
                    bold = entry.optInt("font_weight", 400) >= 600,
                )
            }

            val player0 = style.optJSONArray("players")?.optJSONObject(0)
            val accent = colors["text.accent"] ?: Color.White
            return ZedTheme(
                name = theme.getString("name"),
                isDark = dark,
                colors = colors,
                syntax = syntax,
                cursor = player0?.optString("cursor")?.let(::parseColor) ?: accent,
                selection = player0?.optString("selection")?.let(::parseColor)
                    ?: accent.copy(alpha = 0.24f),
            )
        }

        /** `#rrggbb` or `#rrggbbaa` → [Color]. */
        private fun parseColor(hex: String): Color? {
            val digits = hex.removePrefix("#")
            return when (digits.length) {
                6 -> digits.toLongOrNull(16)?.let { Color(0xFF000000L or it) }
                8 -> digits.toLongOrNull(16)?.let {
                    // Zed is #rrggbbaa; Compose wants aarrggbb.
                    Color(((it and 0xFF) shl 24) or (it shr 8))
                }
                else -> null
            }
        }
    }
}

val LocalZedTheme = staticCompositionLocalOf<ZedTheme> {
    error("ZedTheme not provided — wrap content in ConquestCodeByEyedTheme")
}
