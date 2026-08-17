package to.eyed.conquest.code.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.conquest.code.core.BufferMatch
import to.eyed.conquest.code.core.SearchQuery
import to.eyed.conquest.code.core.searchBuffer
import to.eyed.conquest.code.ui.editor.EditorState
import to.eyed.conquest.code.ui.theme.LocalZedTheme

/** Zed's `buffer_search` toolbar is one row (crates/search/src/buffer_search.rs). */
private val BarHeight = 36.dp

/** `rounded_md`, the radius Zed gives a search input (styles.rs:1246). */
private val FieldRadius = 6.dp

/**
 * Find within the open buffer — Zed's buffer search, in its shape: a row above
 * the editor with the query, the three toggles, the match count and the two
 * arrows.
 *
 * The search itself is an engine call that scans the whole buffer in a few
 * milliseconds even at 100k lines, so it runs on every keystroke rather than
 * behind a debounce, and there is no incremental state to get wrong. It still
 * goes through [withContext] on the default dispatcher, because "a few
 * milliseconds" is measured on a desktop and the main thread has 16 of them
 * for everything.
 *
 * The matches are handed to [EditorState] rather than drawn here: only the
 * canvas can paint over the text.
 */
@Composable
fun BufferSearchBar(
    editor: EditorState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var caseSensitive by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf(emptyList<BufferMatch>()) }
    var total by remember { mutableIntStateOf(0) }
    var current by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }

    // Re-run whenever the query or a toggle changes. The buffer's own version
    // is in the key as well, so typing in the file keeps the highlights honest
    // rather than leaving them over text that has moved.
    LaunchedEffect(query.text, caseSensitive, wholeWord, regex, editor.session.version) {
        val text = query.text
        if (text.isEmpty()) {
            matches = emptyList()
            total = 0
            error = null
            editor.clearSearchMatches()
            return@LaunchedEffect
        }
        val search = SearchQuery(
            query = text,
            regex = regex,
            caseSensitive = caseSensitive,
            wholeWord = wholeWord,
        )
        val found = withContext(Dispatchers.Default) {
            search.error()?.let { return@withContext null }
            searchBuffer(editor.session.id, search)
        }
        if (found == null) {
            // A half-typed regex — "[" — is the normal state of the field, not
            // a failure to report loudly. Say it quietly and keep the old
            // highlights off the screen.
            error = search.error()
            matches = emptyList()
            total = 0
            editor.clearSearchMatches()
            return@LaunchedEffect
        }
        error = null
        matches = found.matches
        total = found.total
        current = current.coerceIn(0, (found.matches.size - 1).coerceAtLeast(0))
        editor.showSearchMatches(found.matches.map { editor.rangeOf(it) }, current)
    }

    fun step(delta: Int) {
        if (matches.isEmpty()) return
        current = ((current + delta) % matches.size + matches.size) % matches.size
        val range = editor.rangeOf(matches[current])
        editor.showSearchMatches(matches.map { editor.rangeOf(it) }, current)
        editor.selectRange(range)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(BarHeight)
            .background(theme.color("toolbar.background"))
            .padding(horizontal = 8.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> {
                        onDismiss()
                        true
                    }
                    // Enter walks the hits, as it does in Zed and everywhere
                    // else; shift walks them backwards.
                    Key.Enter, Key.NumPadEnter -> {
                        step(if (event.isShiftPressed) -1 else 1)
                        true
                    }
                    Key.F3 -> {
                        step(if (event.isShiftPressed) -1 else 1)
                        true
                    }
                    else -> false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(26.dp)
                .clip(RoundedCornerShape(FieldRadius))
                .background(theme.color("editor.background"))
                .border(
                    1.dp,
                    theme.color(if (error == null) "border" else "error"),
                    RoundedCornerShape(FieldRadius),
                )
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = theme.color("text"),
                ),
                cursorBrush = SolidColor(theme.cursor),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            if (query.text.isEmpty()) {
                Text(
                    text = "Find",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text.placeholder"),
                )
            }
        }

        Toggle("Aa", caseSensitive, "Match case") { caseSensitive = !caseSensitive }
        Toggle("ab", wholeWord, "Whole word") { wholeWord = !wholeWord }
        Toggle(".*", regex, "Regular expression") { regex = !regex }

        Text(
            text = error?.let { "no match" } ?: when {
                query.text.isEmpty() -> ""
                matches.isEmpty() -> "no results"
                // "3 of 12 000" stays honest when the engine capped the list:
                // the count is what it found, not what it kept.
                else -> "${current + 1} of $total"
            },
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted"),
            modifier = Modifier.width(96.dp),
        )

        Arrow("‹", "Previous match") { step(-1) }
        Arrow("›", "Next match") { step(1) }
        Arrow("✕", "Close") { onDismiss() }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, description: String, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(theme.color(if (on) "element.selected" else "ghost_element.background"))
            .clickable(onClickLabel = description, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = theme.color(if (on) "text" else "text.muted"),
        )
    }
}

@Composable
private fun Arrow(glyph: String, description: String, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClickLabel = description, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("icon"),
        )
    }
}

/** A match's byte range, as rows and UTF-16 columns the renderer can draw. */
private fun EditorState.rangeOf(match: BufferMatch): EditorState.SelectionRange {
    val startRow = match.row
    val startLine = line(startRow)
    val startCol = utf16Col(startLine, match.column)
    // Multi-line matches are rare but a regex can make one; walk forward from
    // the start rather than asking the engine again for every hit.
    var row = startRow
    var remaining = (match.end - match.start).toInt() -
        (utf8Length(startLine) - match.column).coerceAtLeast(0)
    if (remaining <= 0) {
        val endCol = utf16Col(startLine, match.column + (match.end - match.start).toInt())
        return EditorState.SelectionRange(startRow, startCol, startRow, endCol)
    }
    // Each row costs its own newline byte as we cross it.
    while (remaining > 0 && row + 1 < lineCount) {
        row++
        val text = line(row)
        val bytes = utf8Length(text)
        remaining -= 1
        if (remaining <= bytes) return EditorState.SelectionRange(startRow, startCol, row, utf16Col(text, remaining))
        remaining -= bytes
    }
    return EditorState.SelectionRange(startRow, startCol, row, line(row).length)
}
