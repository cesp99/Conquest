package to.eyed.conquest.code.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.min
import kotlinx.coroutines.delay
import to.eyed.conquest.code.ui.theme.LocalZedTheme
import to.eyed.conquest.code.ui.theme.ZedTheme

private const val CURSOR_BLINK_MILLIS = 530L

/**
 * The editor surface (phase-2 v1, read-only): a custom canvas that draws
 * only the visible window of the engine buffer — no whole-buffer state on
 * the UI side. Virtualized line rendering with per-content-line layout
 * caching, pixel-based vertical/horizontal scrolling with fling,
 * tap-to-position blinking cursor, current-line highlight and a
 * line-number gutter.
 *
 * Editing (IME), selection and syntax highlighting land in later phase-2
 * tasks; this surface is what they build on.
 */
@Composable
fun EditorPane(state: EditorState, modifier: Modifier = Modifier) {
    val theme = LocalZedTheme.current
    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        color = theme.color("editor.foreground"),
    )
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val layoutCache =
        remember(measurer, textStyle, theme) { TextLayoutCache(measurer, textStyle, theme) }

    with(density) {
        state.updateMetrics(
            lineHeight = 20.sp.toPx(),
            charWidth = layoutCache.layoutFor("M").size.width.toFloat(),
            gutterPadding = 10.dp.toPx(),
            textPadding = 8.dp.toPx(),
        )
    }

    var cursorVisible by remember { mutableStateOf(true) }
    LaunchedEffect(state.cursorRow, state.cursorCol, state.session.version) {
        cursorVisible = true
        while (true) {
            delay(CURSOR_BLINK_MILLIS)
            cursorVisible = !cursorVisible
        }
    }

    val verticalScroll = rememberScrollableState { delta -> state.applyScrollDeltaY(delta) }
    val horizontalScroll = rememberScrollableState { delta -> state.applyScrollDeltaX(delta) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(theme.color("editor.background"))
            .scrollable(verticalScroll, Orientation.Vertical)
            .scrollable(horizontalScroll, Orientation.Horizontal)
            .focusRequester(focusRequester)
            .editorTextInput(state)
            .onKeyEvent { event -> handleEditorKey(state, event) }
            .focusable()
            .pointerInput(state) {
                detectTapGestures { tap ->
                    state.moveCursorTo(tap) { line -> layoutCache.layoutFor(line) }
                    focusRequester.requestFocus()
                    keyboard?.show()
                }
            }
    ) {
        state.updateViewport(size.width, size.height)
        val lineHeight = state.lineHeightPx
        val gutterWidth = state.gutterWidthPx
        val firstRow = (state.scrollY / lineHeight).toInt().coerceAtLeast(0)
        val lastRow = min(
            firstRow + ceil(size.height / lineHeight).toInt() + 1,
            state.lineCount,
        )
        val lines = state.linesWindow(firstRow, lastRow)
        val textLeft = gutterWidth + state.textPaddingPx - state.scrollX

        // Current-line highlight, under everything else.
        val cursorTop = state.cursorRow * lineHeight - state.scrollY
        if (cursorTop + lineHeight > 0 && cursorTop < size.height) {
            clipRect(left = gutterWidth) {
                drawRect(
                    color = theme.color("editor.active_line.background"),
                    topLeft = Offset(gutterWidth, cursorTop),
                    size = Size(size.width - gutterWidth, lineHeight),
                )
            }
        }

        // Buffer text, clipped so it never slides under the gutter.
        val spansWindow = state.spansWindow()
        clipRect(left = gutterWidth) {
            lines.forEachIndexed { index, line ->
                val layout =
                    layoutCache.layoutFor(line, spansWindow.getOrElse(index) { emptyList() })
                state.noteContentWidth(layout.size.width.toFloat())
                val top = (firstRow + index) * lineHeight - state.scrollY
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(textLeft, top + (lineHeight - layout.size.height) / 2f),
                )
            }

            // Cursor.
            if (cursorVisible && state.cursorRow in firstRow until lastRow) {
                val line = lines[state.cursorRow - firstRow]
                val layout = layoutCache.layoutFor(line, state.spansFor(state.cursorRow))
                val col = state.cursorCol.coerceAtMost(line.length)
                val cursorX = textLeft + layout.getHorizontalPosition(col, true)
                if (cursorX >= gutterWidth - 1f) {
                    drawRect(
                        color = theme.cursor,
                        topLeft = Offset(cursorX, cursorTop),
                        size = Size(2f, lineHeight),
                    )
                }
            }
        }

        // Gutter: background, divider, right-aligned line numbers.
        drawRect(
            color = theme.color("editor.gutter.background"),
            topLeft = Offset.Zero,
            size = Size(gutterWidth, size.height),
        )
        drawLine(
            color = theme.color("border.variant"),
            start = Offset(gutterWidth, 0f),
            end = Offset(gutterWidth, size.height),
        )
        val lineNumber = theme.color("editor.line_number")
        val activeLineNumber = theme.color("editor.active_line_number")
        for (row in firstRow until lastRow) {
            val layout = layoutCache.layoutFor((row + 1).toString())
            val top = row * lineHeight - state.scrollY
            drawText(
                textLayoutResult = layout,
                color = if (row == state.cursorRow) activeLineNumber else lineNumber,
                topLeft = Offset(
                    gutterWidth - state.gutterPaddingPx - layout.size.width,
                    top + (lineHeight - layout.size.height) / 2f,
                ),
            )
        }
    }
}

/**
 * Hardware-key (and IME-forwarded key event) editing: character input,
 * backspace/enter, arrow navigation, undo/redo. Selection and Zed-style
 * bindings are later phase-2 tasks.
 */
private fun handleEditorKey(state: EditorState, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.isCtrlPressed) {
        return when (event.key) {
            Key.Z -> {
                if (event.isShiftPressed) state.redo() else state.undo()
                true
            }
            Key.Y -> {
                state.redo()
                true
            }
            else -> false
        }
    }
    return when (event.key) {
        Key.Backspace -> {
            state.backspace()
            true
        }
        Key.Enter, Key.NumPadEnter -> {
            state.insertAtCursor("\n")
            true
        }
        Key.DirectionLeft -> {
            state.moveCursorHorizontally(-1)
            true
        }
        Key.DirectionRight -> {
            state.moveCursorHorizontally(1)
            true
        }
        Key.DirectionUp -> {
            state.moveCursorVertically(-1)
            true
        }
        Key.DirectionDown -> {
            state.moveCursorVertically(1)
            true
        }
        else -> {
            val codePoint = event.utf16CodePoint
            if (codePoint >= 32 && codePoint != 127) {
                state.insertAtCursor(String(Character.toChars(codePoint)))
                true
            } else {
                false
            }
        }
    }
}

/**
 * LRU cache of text layouts keyed by line content + highlight spans.
 * Identical styled lines (blank lines, closing braces, repeated code)
 * share one measured layout, so steady-state scrolling measures only
 * lines it has never seen.
 */
private class TextLayoutCache(
    private val measurer: TextMeasurer,
    private val style: TextStyle,
    private val theme: ZedTheme,
    private val capacity: Int = 512,
) {
    private data class Key(val line: String, val spans: List<HighlightSpan>)

    private val cache = object : LinkedHashMap<Key, TextLayoutResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, TextLayoutResult>) =
            size > capacity
    }

    fun layoutFor(line: String, spans: List<HighlightSpan> = emptyList()): TextLayoutResult =
        cache.getOrPut(Key(line, spans)) {
            measurer.measure(annotate(line, spans), style, softWrap = false)
        }

    private fun annotate(line: String, spans: List<HighlightSpan>): AnnotatedString {
        if (spans.isEmpty()) return AnnotatedString(line)
        return buildAnnotatedString {
            append(line)
            for (span in spans) {
                val start = span.start.coerceIn(0, line.length)
                val end = span.end.coerceIn(0, line.length)
                if (start >= end) continue
                theme.spanStyle(span.style)?.let { addStyle(it, start, end) }
            }
        }
    }
}
