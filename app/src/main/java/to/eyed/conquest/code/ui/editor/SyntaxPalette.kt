package to.eyed.conquest.code.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Maps engine style ids to Compose span styles. The order MUST mirror
 * `STYLE_NAMES` in `core/crates/engine/src/highlight.rs` — the style id in
 * `bufferHighlights` output is an index into that list.
 *
 * Colors are Zed's One Dark syntax palette (hardcoded for now; phase-2
 * theme work parses Zed theme JSON and replaces this table).
 */
object SyntaxPalette {
    private data class Entry(
        val color: Color? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
    )

    private val entries = listOf(
        Entry(Color(0xFFD19A66)),                  // attribute
        Entry(Color(0xFFD19A66)),                  // boolean
        Entry(Color(0xFF5C6370), italic = true),   // comment
        Entry(Color(0xFF878E98), italic = true),   // comment.doc
        Entry(Color(0xFFD19A66)),                  // constant
        Entry(Color(0xFF61AFEF)),                  // constructor
        Entry(Color(0xFFDCE0E5)),                  // embedded
        Entry(italic = true),                      // emphasis
        Entry(bold = true),                        // emphasis.strong
        Entry(Color(0xFFE06C75)),                  // enum
        Entry(Color(0xFF61AFEF)),                  // function
        Entry(Color(0xFFC678DD)),                  // keyword
        Entry(Color(0xFF61AFEF)),                  // label
        Entry(Color(0xFF61AFEF), italic = true),   // link_text
        Entry(Color(0xFF56B6C2)),                  // link_uri
        Entry(Color(0xFFD19A66)),                  // number
        Entry(Color(0xFF56B6C2)),                  // operator
        Entry(Color(0xFFC678DD)),                  // preproc
        Entry(Color(0xFFE06C75)),                  // property
        Entry(Color(0xFFABB2BF)),                  // punctuation
        Entry(Color(0xFFABB2BF)),                  // punctuation.bracket
        Entry(Color(0xFFABB2BF)),                  // punctuation.delimiter
        Entry(Color(0xFFE06C75)),                  // punctuation.list_marker
        Entry(Color(0xFF56B6C2)),                  // punctuation.special
        Entry(Color(0xFF98C379)),                  // string
        Entry(Color(0xFF56B6C2)),                  // string.escape
        Entry(Color(0xFFD19A66)),                  // string.regex
        Entry(Color(0xFFD19A66)),                  // string.special
        Entry(Color(0xFF98C379)),                  // string.special.symbol
        Entry(Color(0xFFE06C75)),                  // tag
        Entry(Color(0xFF98C379)),                  // text.literal
        Entry(Color(0xFF61AFEF), bold = true),     // title
        Entry(Color(0xFFE5C07B)),                  // type
        Entry(Color(0xFFDCE0E5)),                  // variable
        Entry(Color(0xFFE06C75)),                  // variable.special
    )

    private val spanStyles: List<SpanStyle> = entries.map { entry ->
        SpanStyle(
            color = entry.color ?: Color.Unspecified,
            fontWeight = if (entry.bold) FontWeight.Bold else null,
            fontStyle = if (entry.italic) FontStyle.Italic else null,
        )
    }

    fun spanStyle(styleId: Int): SpanStyle? = spanStyles.getOrNull(styleId)
}

/** One highlighted range on one row; columns are UTF-16 offsets. */
data class HighlightSpan(val start: Int, val end: Int, val style: Int)
