package to.eyed.conquest.code.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONObject

/**
 * A parsed Zed theme. The source of truth is Zed's theme JSON (the family
 * files under assets/themes/, vendored from the Zed repository — see
 * docs/THIRD_PARTY.md); no hardcoded palettes remain in the app.
 *
 * A *family* file holds several themes — One is two, Gruvbox is six — so a
 * theme's identity is its full name ("Gruvbox Dark Hard"), not its file.
 * [ZedThemes] is the index that maps one to the other.
 */
class ZedTheme(
    val name: String,
    /** The family the theme is shipped in: "One", "Ayu", "Gruvbox". */
    val family: String,
    val isDark: Boolean,
    private val colors: Map<String, Color>,
    syntax: Map<String, SyntaxStyle>,
    val cursor: Color,
    val selection: Color,
) {
    data class SyntaxStyle(val color: Color?, val italic: Boolean, val bold: Boolean)

    /** A theme's identity, as the picker lists it before anything is loaded. */
    data class Meta(val name: String, val family: String, val isDark: Boolean)

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
            // one.json writes the key with a literal `null`; Zed's Rust side
            // fills it from ThemeColors, where it is the focused-border blue.
            "panel.focused_border" to "border.focused",
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
         * The one omitted key no other key can stand in for.
         *
         * `element.selection_background` tints selected text inside chrome
         * inputs — the palette's query field, the rename box. Zed's default is
         * the same wash the editor selects with, which lives in `players[0]`
         * rather than in the flat style table, so it is seeded at parse time
         * instead of being borrowed by [DERIVED].
         */
        private const val UI_SELECTION = "element.selection_background"

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
         * The themes a family file contains, without parsing their palettes.
         *
         * The picker lists every installed theme, and listing them must not
         * cost eleven palette parses — so this reads only the identity of
         * each, and [parse] is paid for the one theme actually shown.
         */
        internal fun index(json: String): List<Meta> {
            val family = JSONObject(json)
            val familyName = family.optString("name")
            val themes = family.optJSONArray("themes") ?: return emptyList()
            return (0 until themes.length()).mapNotNull { i ->
                val theme = themes.optJSONObject(i) ?: return@mapNotNull null
                val name = theme.optString("name").takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                Meta(
                    name = name,
                    family = familyName,
                    isDark = theme.optString("appearance") != "light",
                )
            }
        }

        /** The theme called [name] from a family file, or null if it isn't in it. */
        internal fun parse(json: String, name: String): ZedTheme? {
            val family = JSONObject(json)
            val themes = family.optJSONArray("themes") ?: return null
            for (i in 0 until themes.length()) {
                val theme = themes.getJSONObject(i)
                if (theme.optString("name") == name) {
                    return parseTheme(theme, family.optString("name"))
                }
            }
            return null
        }

        private fun parseTheme(theme: JSONObject, family: String): ZedTheme {
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
            val selection = player0?.optString("selection")?.let(::parseColor)
                ?: accent.copy(alpha = 0.24f)
            colors.getOrPut(UI_SELECTION) { selection }
            return ZedTheme(
                name = theme.getString("name"),
                family = family,
                isDark = theme.optString("appearance") != "light",
                colors = colors,
                syntax = syntax,
                cursor = player0?.optString("cursor")?.let(::parseColor) ?: accent,
                selection = selection,
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
