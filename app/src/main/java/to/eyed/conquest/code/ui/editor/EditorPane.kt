package to.eyed.conquest.code.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
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
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import to.eyed.conquest.code.ui.theme.LocalZedTheme
import to.eyed.conquest.code.ui.theme.ZedTheme

private const val CURSOR_BLINK_MILLIS = 530L

/**
 * The editor surface: a custom canvas that draws only the visible window
 * of the engine buffer — no whole-buffer state on the UI side. Virtualized
 * line rendering with per-content-line layout caching, pixel-based
 * scrolling with fling, tap cursor, tree-sitter highlight spans, selection
 * with drag handles + floating toolbar, soft-keyboard editing
 * (editorTextInput) and hardware keys.
 */
@Composable
fun EditorPane(
    state: EditorState,
    modifier: Modifier = Modifier,
    onSave: (() -> Unit)? = null,
) {
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
    val handleRadiusPx = with(density) { 6.dp.toPx() }
    val handleTouchRadiusPx = with(density) { 24.dp.toPx() }

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
    val toolbar = LocalTextToolbar.current
    val clipboard = LocalClipboardManager.current
    var paneCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val actions = remember(state, clipboard, toolbar) {
        EditorActions(state, clipboard, toolbar) { paneCoordinates }
    }
    val layoutForLine: (String) -> TextLayoutResult =
        remember(layoutCache) { { line -> layoutCache.layoutFor(line) } }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(theme.color("editor.background"))
            // DeX and paired keyboards mean a mouse is ordinary here, not
            // exotic; text should say so under the pointer.
            .pointerHoverIcon(PointerIcon.Text)
            .onGloballyPositioned { paneCoordinates = it }
            .scrollable(verticalScroll, Orientation.Vertical)
            .scrollable(horizontalScroll, Orientation.Horizontal)
            .focusRequester(focusRequester)
            .editorTextInput(state)
            .onKeyEvent { event -> handleEditorKey(state, actions, event, onSave) }
            .focusable()
            .pointerInput(state) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        actions.hideToolbar()
                        state.selectWordAt(position, layoutForLine)
                        focusRequester.requestFocus()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        state.extendSelectionTo(change.position, layoutForLine)
                    },
                    onDragEnd = { actions.showToolbar() },
                    onDragCancel = { actions.showToolbar() },
                )
            }
            .pointerInput(state) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        state.selectWordAt(tap, layoutForLine)
                        focusRequester.requestFocus()
                        actions.showToolbar()
                    },
                    // No-op: the long press belongs to
                    // detectDragGesturesAfterLongPress above; registering it
                    // here stops onTap from also firing on release (which
                    // would clear the fresh selection).
                    onLongPress = {},
                    onTap = { tap ->
                        actions.hideToolbar()
                        state.moveCursorTo(tap, layoutForLine)
                        focusRequester.requestFocus()
                        keyboard?.show()
                    },
                )
            }
            // Selection-handle dragging. Innermost pointer input: it must
            // inspect the down before the tap detector consumes it. A down
            // near a handle claims the gesture and moves that selection
            // end; otherwise the event flows on untouched.
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val handles = selectionHandles(state, layoutCache) ?: return@awaitEachGesture
                    val distStart = (down.position - handles.first).getDistance()
                    val distEnd = (down.position - handles.second).getDistance()
                    if (min(distStart, distEnd) > handleTouchRadiusPx) return@awaitEachGesture
                    val movingStart = distStart <= distEnd
                    down.consume()
                    actions.hideToolbar()
                    drag(down.id) { change ->
                        change.consume()
                        // The handle hangs below the line: aim the hit point
                        // back up into the text.
                        val target = change.position - Offset(0f, state.lineHeightPx * 0.75f)
                        state.dragSelectionEndTo(target, movingStart, layoutForLine)
                    }
                    actions.showToolbar()
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
        val selection = state.selectionRange()
        if (selection == null && cursorTop + lineHeight > 0 && cursorTop < size.height) {
            clipRect(left = gutterWidth) {
                drawRect(
                    color = theme.color("editor.active_line.background"),
                    topLeft = Offset(gutterWidth, cursorTop),
                    size = Size(size.width - gutterWidth, lineHeight),
                )
            }
        }

        val spansWindow = state.spansWindow()
        clipRect(left = gutterWidth) {
            // Selection background.
            if (selection != null) {
                for (row in max(selection.startRow, firstRow)..
                    min(selection.endRow, lastRow - 1)) {
                    val line = lines[row - firstRow]
                    val layout = layoutCache.layoutFor(
                        line,
                        spansWindow.getOrElse(row - firstRow) { emptyList() },
                    )
                    val startCol =
                        if (row == selection.startRow)

                            selection.startCol.coerceAtMost(line.length)
                        else 0
                    val left = textLeft + layout.getHorizontalPosition(startCol, true)
                    val right = if (row == selection.endRow) {
                        textLeft + layout.getHorizontalPosition(
                            selection.endCol.coerceAtMost(line.length),
                            true,
                        )
                    } else {
                        // Full-line rows: include a half-char for the newline.
                        textLeft + layout.getHorizontalPosition(line.length, true) +
                            state.charWidthPx / 2f
                    }
                    drawRect(
                        color = theme.selection,
                        topLeft = Offset(left, row * lineHeight - state.scrollY),
                        size = Size((right - left).coerceAtLeast(0f), lineHeight),
                    )
                }
            }

            // Buffer text.
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

            // Selection drag handles.
            selectionHandles(state, layoutCache)?.let { (start, end) ->
                drawCircle(theme.cursor, handleRadiusPx, start + Offset(0f, handleRadiusPx))
                drawCircle(theme.cursor, handleRadiusPx, end + Offset(0f, handleRadiusPx))
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
 * Pane-local baseline positions of the selection start/end (where the drag
 * handles hang), or null without a selection.
 */
private fun selectionHandles(
    state: EditorState,
    layoutCache: TextLayoutCache,
): Pair<Offset, Offset>? {
    val range = state.selectionRange() ?: return null
    fun at(row: Int, col: Int): Offset {
        val line = state.line(row)
        val layout = layoutCache.layoutFor(line, state.spansFor(row))
        val x = state.gutterWidthPx + state.textPaddingPx - state.scrollX +
            layout.getHorizontalPosition(col.coerceAtMost(line.length), true)
        return Offset(x, (row + 1) * state.lineHeightPx - state.scrollY)
    }
    return at(range.startRow, range.startCol) to at(range.endRow, range.endCol)
}

/**
 * Clipboard + floating-toolbar actions. Selection ops route through here so
 * hardware shortcuts and the toolbar share one implementation.
 */
internal class EditorActions(
    private val state: EditorState,
    private val clipboard: ClipboardManager,
    private val toolbar: TextToolbar,
    private val paneCoordinates: () -> LayoutCoordinates?,
) {
    fun copy(): Boolean {
        val text = state.selectionText()
        if (text.isEmpty()) return false
        clipboard.setText(AnnotatedString(text))
        state.clearSelection()
        hideToolbar()
        return true
    }

    fun cut(): Boolean {
        val text = state.selectionText()
        if (text.isEmpty()) return false
        clipboard.setText(AnnotatedString(text))
        state.deleteSelection()
        hideToolbar()
        return true
    }

    fun paste(): Boolean {
        val text = clipboard.getText()?.text ?: return false
        state.insertAtCursor(text)
        hideToolbar()
        return true
    }

    fun selectAll() {
        state.selectAll()
        showToolbar()
    }

    fun showToolbar() {
        val coords = paneCoordinates() ?: return
        val range = state.selectionRange() ?: return
        val topLeftLocal = Offset(
            state.gutterWidthPx,
            range.startRow * state.lineHeightPx - state.scrollY,
        )
        val bottomLocal = (range.endRow + 1) * state.lineHeightPx - state.scrollY
        val topLeft = coords.localToRoot(topLeftLocal)
        val bottomRight = coords.localToRoot(
            Offset(coords.size.width.toFloat(), bottomLocal),
        )
        toolbar.showMenu(
            rect = Rect(topLeft, bottomRight),
            onCopyRequested = { copy() },
            onPasteRequested = { paste() },
            onCutRequested = { cut() },
            onSelectAllRequested = { selectAll() },
        )
    }

    fun hideToolbar() {
        toolbar.hide()
    }
}

/**
 * Hardware-key (and IME-forwarded key event) editing: character input,
 * backspace/enter, arrow navigation with shift-selection, clipboard
 * shortcuts, undo/redo.
 */
private fun handleEditorKey(
    state: EditorState,
    actions: EditorActions,
    event: KeyEvent,
    onSave: (() -> Unit)?,
): Boolean {
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
            Key.A -> {
                actions.selectAll()
                true
            }
            Key.C -> actions.copy()
            Key.X -> actions.cut()
            Key.V -> actions.paste()
            Key.S -> {
                onSave?.invoke()
                onSave != null
            }
            else -> false
        }
    }
    val extend = event.isShiftPressed
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
            state.moveCursorHorizontally(-1, extend)
            true
        }
        Key.DirectionRight -> {
            state.moveCursorHorizontally(1, extend)
            true
        }
        Key.DirectionUp -> {
            state.moveCursorVertically(-1, extend)
            true
        }
        Key.DirectionDown -> {
            state.moveCursorVertically(1, extend)
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
internal class TextLayoutCache(
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
