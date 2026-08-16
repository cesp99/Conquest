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

    /** Style-table lookup by Zed style key, e.g. `"editor.background"`. */
    fun color(key: String, fallback: Color = Color.Magenta): Color =
        colors[key] ?: fallback

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
