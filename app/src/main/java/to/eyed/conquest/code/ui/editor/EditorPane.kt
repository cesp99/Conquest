package to.eyed.conquest.code.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isAltPressed
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
import androidx.compose.ui.platform.LocalWindowInfo
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
import to.eyed.conquest.code.ui.theme.LocalAppSettings
import to.eyed.conquest.code.ui.theme.LocalZedTheme
import to.eyed.conquest.code.ui.theme.ZedTheme

private const val HIGHLIGHT_POLL_MILLIS = 100L
private const val CURSOR_BLINK_MILLIS = 530L

/**
 * The editor surface: a custom canvas that draws only the visible window
 * of the engine buffer — no whole-buffer state on the UI side. Virtualized
 * line rendering with per-content-line layout caching, pixel-based
 * scrolling with fling, tap cursor, tree-sitter highlight spans, multiple
 * cursors and selections with drag handles + floating toolbar,
 * soft-keyboard editing (editorTextInput) and hardware keys.
 */
@Composable
fun EditorPane(
    state: EditorState,
    modifier: Modifier = Modifier,
    onSave: (() -> Unit)? = null,
) {
    val theme = LocalZedTheme.current
    val settings = LocalAppSettings.current
    val fontSize = settings.bufferFontSize.sp
    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize,
        color = theme.color("editor.foreground"),
    )
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val layoutCache =
        remember(measurer, textStyle, theme) { TextLayoutCache(measurer, textStyle, theme) }
    // Line height follows the font so changing the size doesn't cramp or
    // scatter the lines.
    val lineHeight = settings.bufferFontSize * 1.45f

    with(density) {
        state.updateMetrics(
            lineHeight = lineHeight.sp.toPx(),
            charWidth = layoutCache.layoutFor("M").size.width.toFloat(),
            gutterPadding = 10.dp.toPx(),
            textPadding = 8.dp.toPx(),
        )
    }
    state.tabSize = settings.tabSize
    val handleRadiusPx = with(density) { 6.dp.toPx() }
    val handleTouchRadiusPx = with(density) { 24.dp.toPx() }

    // Syntax lags the text slightly by design (the reparse is off the
    // keystroke path), so watch for it landing and repaint when it does.
    LaunchedEffect(state) {
        while (true) {
            state.refreshHighlightVersion()
            delay(HIGHLIGHT_POLL_MILLIS)
        }
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
    val toolbar = LocalTextToolbar.current
    val clipboard = LocalClipboardManager.current
    var paneCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val actions = remember(state, clipboard, toolbar) {
        EditorActions(state, clipboard, toolbar) { paneCoordinates }
    }
    val layoutForLine: (String) -> TextLayoutResult =
        remember(layoutCache) { { line -> layoutCache.layoutFor(line) } }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
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
                // Alt+click drops an extra caret. Claimed in the *initial* pass,
                // before the tap and long-press detectors below get a look, so
                // an Alt-held click never also moves the cursor it just added.
                .pointerInput(state) {
                    awaitEachGesture {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Press) return@awaitEachGesture
                        if (!event.keyboardModifiers.isAltPressed) return@awaitEachGesture
                        val down = event.changes.firstOrNull() ?: return@awaitEachGesture
                        down.consume()
                        actions.hideToolbar()
                        state.addCaretAt(down.position, layoutForLine)
                        focusRequester.requestFocus()
                    }
                }
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

            // Current-line highlight, under everything else. It is the *one*
            // cursor's line: with a column of carets there is no single active
            // line, and striping half the screen would only be noise.
            val cursorTop = state.cursorRow * lineHeight - state.scrollY
            val selection = state.selectionRange()
            val extras = state.extraCarets
            if (selection == null && extras.isEmpty() &&
                cursorTop + lineHeight > 0 && cursorTop < size.height
            ) {
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
                fun paintSelection(startRow: Int, startCol: Int, endRow: Int, endCol: Int) {
                    for (row in max(startRow, firstRow)..min(endRow, lastRow - 1)) {
                        val line = lines[row - firstRow]
                        val layout = layoutCache.layoutFor(
                            line,
                            spansWindow.getOrElse(row - firstRow) { emptyList() },
                        )
                        val from = if (row == startRow) startCol.coerceAtMost(line.length) else 0
                        val left = textLeft + layout.getHorizontalPosition(from, true)
                        val right = if (row == endRow) {
                            textLeft + layout.getHorizontalPosition(
                                endCol.coerceAtMost(line.length),
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

                fun paintCaret(row: Int, col: Int) {
                    if (row !in firstRow until lastRow) return
                    val line = lines[row - firstRow]
                    val layout = layoutCache.layoutFor(line, state.spansFor(row))
                    val caretX = textLeft + layout.getHorizontalPosition(col.coerceAtMost(line.length), true)
                    if (caretX < gutterWidth - 1f) return
                    drawRect(
                        color = theme.cursor,
                        topLeft = Offset(caretX, row * lineHeight - state.scrollY),
                        size = Size(2f, lineHeight),
                    )
                }

                // Selection backgrounds.
                if (selection != null) {
                    paintSelection(
                        selection.startRow,
                        selection.startCol,
                        selection.endRow,
                        selection.endCol,
                    )
                }
                for (caret in extras) {
                    if (!caret.isEmpty) {
                        paintSelection(caret.startRow, caret.startCol, caret.endRow, caret.endCol)
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

                // Carets. The extra ones don't blink: a blinking column is hard
                // to read as one thing, and their whole job is to show where the
                // next keystroke lands.
                if (cursorVisible) paintCaret(state.cursorRow, state.cursorCol)
                for (caret in extras) paintCaret(caret.headRow, caret.headCol)

                // Selection drag handles, for the primary selection only —
                // handles on every caret of a column would be unusable, and the
                // column is a keyboard and Alt+click construct anyway.
                if (extras.isEmpty()) {
                    selectionHandles(state, layoutCache)?.let { (start, end) ->
                        drawCircle(theme.cursor, handleRadiusPx, start + Offset(0f, handleRadiusPx))
                        drawCircle(theme.cursor, handleRadiusPx, end + Offset(0f, handleRadiusPx))
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

        EditorActionRow(
            state = state,
            paneCoordinates = paneCoordinates,
            onActed = { focusRequester.requestFocus() },
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

/**
 * The commands a soft keyboard can't reach, on a strip that appears with the
 * IME and sits just above it.
 *
 * This is the same answer the terminal already gives (`ExtraKeysRow`): the
 * on-screen keyboard has no Alt, no Ctrl and no arrow cluster, so every
 * chord in `handleEditorKey` would otherwise be keyboard-only — and the
 * convention in this codebase is that nothing is. It costs nothing on DeX or
 * with a paired keyboard, where no IME comes up and the row never appears.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorActionRow(
    state: EditorState,
    paneCoordinates: LayoutCoordinates?,
    onActed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The insets are read here rather than in EditorPane so the keyboard's
    // open and close animation recomposes this strip and not the canvas.
    if (!WindowInsets.isImeVisible) return
    val density = LocalDensity.current
    // How far to lift the row so it lands on top of the keyboard.
    // `imePadding` can't do it: it would pad by the whole keyboard, and part
    // of that keyboard is already below this pane — the status bar's worth of
    // window sits between them. What is left after subtracting that is the
    // overlap, and it comes out at zero on the devices that resize the window
    // for the IME instead of letting it float over.
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val paneBottom = paneCoordinates
        ?.takeIf { it.isAttached }
        ?.let { it.localToWindow(Offset(0f, it.size.height.toFloat())).y }
        ?: windowHeight.toFloat()
    val overlap = (WindowInsets.ime.getBottom(density) - (windowHeight - paneBottom))
        .coerceAtLeast(0f)
    val theme = LocalZedTheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = with(density) { overlap.toDp() })
            .height(38.dp)
            .background(theme.color("status_bar.background"))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        fun act(action: () -> Unit): () -> Unit = {
            action()
            // Tapping a key must not take focus off the canvas, or the IME
            // session ends and the keyboard drops away under the finger.
            onActed()
        }
        ActionKey("esc", act { state.cancel() })
        ActionKey("tab", act { state.insertAtCursor(" ".repeat(state.tabSize)) })
        ActionKey("undo", act { state.undo() })
        ActionKey("redo", act { state.redo() })
        ActionKey("＋cur↑", act { state.addCaretVertically(-1) })
        ActionKey("＋cur↓", act { state.addCaretVertically(1) })
        ActionKey("＋next", act { state.selectNextOccurrence() })
        ActionKey("line↑", act { state.moveLines(-1) })
        ActionKey("line↓", act { state.moveLines(1) })
        ActionKey("dup", act { state.duplicateLines(above = false) })
        ActionKey("del line", act { state.deleteLines() })
        ActionKey("join", act { state.joinLines() })
        ActionKey("//", act { state.toggleComment() })
    }
}

@Composable
private fun ActionKey(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
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
 * Hardware-key (and IME-forwarded key event) editing: character input with
 * auto-closing pairs, backspace/enter, arrow navigation with
 * shift-selection, the multi-cursor and line commands, clipboard shortcuts,
 * undo/redo.
 *
 * The chords follow Zed's `default-linux.json` wherever it has one, which is
 * why duplicate-line is Ctrl+Alt+Shift+Arrow rather than the Ctrl+Shift+D
 * other editors use. The one addition is Ctrl+Alt+Arrow as a second way to
 * add a cursor: Zed spells that Shift+Alt+Arrow, but Shift+Alt is where a
 * lot of Android keyboards put their own layout switch.
 *
 * The workspace's table (`Keybindings.kt`) is matched in a preview pass
 * above this one and must never claim any of these.
 */
private fun handleEditorKey(
    state: EditorState,
    actions: EditorActions,
    event: KeyEvent,
    onSave: (() -> Unit)?,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val ctrl = event.isCtrlPressed
    val alt = event.isAltPressed
    val shift = event.isShiftPressed

    // Alt chords first: the Ctrl block below would otherwise swallow the
    // Ctrl+Alt+Shift twins before their Alt half was ever looked at.
    if (alt && (event.key == Key.DirectionUp || event.key == Key.DirectionDown)) {
        val delta = if (event.key == Key.DirectionUp) -1 else 1
        when {
            ctrl && shift -> state.duplicateLines(above = delta < 0)
            ctrl || shift -> state.addCaretVertically(delta)
            else -> state.moveLines(delta)
        }
        return true
    }
    if (ctrl) {
        return when (event.key) {
            Key.Z -> {
                if (shift) state.redo() else state.undo()
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
            Key.D -> state.selectNextOccurrence()
            Key.L -> shift && state.selectAllOccurrences()
            Key.K -> {
                if (shift) state.deleteLines()
                shift
            }
            Key.J -> {
                if (shift) state.joinLines()
                shift
            }
            Key.Slash -> state.toggleComment()
            else -> false
        }
    }
    val extend = shift
    return when (event.key) {
        Key.Escape -> state.cancel()
        Key.Backspace -> {
            state.backspace()
            true
        }
        Key.Enter, Key.NumPadEnter -> {
            state.insertNewline()
            true
        }
        // Spaces, not a tab character: mixed indentation is a bug factory,
        // and the width is the user's to choose.
        Key.Tab -> {
            state.insertAtCursor(" ".repeat(state.tabSize))
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
            if (!alt && codePoint >= 32 && codePoint != 127) {
                state.typeCharacter(String(Character.toChars(codePoint)))
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
