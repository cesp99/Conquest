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
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import to.eyed.conquest.code.ui.theme.LocalAppSettings
import to.eyed.conquest.code.ui.theme.BufferFontFamily
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
/**
 * How many indent levels [text] is indented past, counting a tab as a whole
 * level and spaces in [tabSize]s. A line that is only whitespace has none: a
 * guide drawn on a blank line would be a guide pointing at nothing.
 */
private fun indentLevels(text: String, tabSize: Int): Int {
    var columns = 0
    for (char in text) {
        when (char) {
            '\t' -> columns += tabSize
            ' ' -> columns++
            else -> return columns / tabSize
        }
    }
    return 0
}

/**
 * The spans of [spans] that fall inside UTF-16 range [start, end), rebased on
 * [start] — one wrapped segment's share of its row's highlighting.
 *
 * The whole row hands its own list back untouched, which matters more than it
 * looks: the layout cache is keyed by text *and* spans, so an unwrapped row
 * keys exactly as it did before wrapping existed and every measurement it
 * already holds still hits.
 */
private fun spansIn(spans: List<HighlightSpan>, start: Int, end: Int): List<HighlightSpan> {
    if (spans.isEmpty() || (start == 0 && end == Int.MAX_VALUE)) return spans
    val sliced = ArrayList<HighlightSpan>(spans.size)
    for (span in spans) {
        val from = max(span.start, start)
        val to = min(span.end, end)
        if (from < to) sliced.add(HighlightSpan(from - start, to - start, span.style))
    }
    return sliced
}

@Composable
fun EditorPane(
    state: EditorState,
    modifier: Modifier = Modifier,
    onSave: (() -> Unit)? = null,
    /**
     * Zed's `soft_wrap`, whose default is `"none"`
     * (assets/settings/default.json:1536). Defaulted here so a caller that
     * has no setting to pass gets Zed's behaviour rather than ours.
     */
    softWrap: SoftWrapMode = SoftWrapMode.None,
) {
    val theme = LocalZedTheme.current
    val settings = LocalAppSettings.current
    val fontSize = settings.bufferFontSize.sp
    val textStyle = TextStyle(
        fontFamily = BufferFontFamily,
        fontSize = fontSize,
        color = theme.color("editor.foreground"),
    )
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val layoutCache =
        remember(measurer, textStyle, theme) { TextLayoutCache(measurer, textStyle, theme) }
    // Zed's `buffer_line_height: "comfortable"`, which is φ — 1.618, not a
    // round number someone liked the look of (theme_settings/src/settings.rs:390).
    // Following the font size is what keeps the lines from cramping when it
    // changes.
    val lineHeight = settings.bufferFontSize * 1.618034f

    // Before the metrics: both feed the wrap width, and setting them in this
    // order works it out once instead of twice.
    state.softWrap = softWrap
    state.tabSize = settings.tabSize
    with(density) {
        state.updateMetrics(
            lineHeight = lineHeight.sp.toPx(),
            charWidth = layoutCache.layoutFor("M").size.width.toFloat(),
            gutterPadding = 10.dp.toPx(),
            textPadding = 8.dp.toPx(),
            cursorWidth = 2.dp.toPx(),
        )
    }
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
    // `state.revision`, not the session's version: the engine's counter is a
    // plain field and composition cannot see it change, so keying on it never
    // restarted the blink after an edit that left the caret where it was.
    LaunchedEffect(state.cursorRow, state.cursorCol, state.revision) {
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
                // The scrollbar is a real handle, not a picture: a drag on it
                // moves the viewport, and a tap on the track jumps there. It is
                // claimed in the initial pass so a drag that starts on the
                // track never also places the caret under it.
                .pointerInput(state) {
                    awaitEachGesture {
                        val down = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull()
                            ?: return@awaitEachGesture
                        val trackWidth = state.charWidthPx.coerceIn(10f, 24f)
                        if (state.maxScrollY <= 0f) return@awaitEachGesture
                        if (down.position.x < size.width - trackWidth) return@awaitEachGesture
                        down.consume()

                        // Where in the thumb the finger landed, so the page
                        // does not jump under it on the first pixel of movement.
                        val height = size.height.toFloat()
                        val visible = (
                            height /
                                (state.displayMap.displayRowCount * state.lineHeightPx)
                            ).coerceIn(0f, 1f)
                        val thumbHeight = (height * visible).coerceAtLeast(trackWidth * 2f)
                        val travel = (height - thumbHeight).coerceAtLeast(1f)
                        val thumbTop = (state.scrollY / state.maxScrollY) * travel
                        val grab = (down.position.y - thumbTop).let {
                            if (it in 0f..thumbHeight) it else thumbHeight / 2f
                        }
                        fun scrollTo(y: Float) {
                            state.scrollToY(((y - grab) / travel) * state.maxScrollY)
                        }
                        scrollTo(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            change.consume()
                            scrollTo(change.position.y)
                        }
                    }
                }
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
                        // Only the primary selection has handles drawn, so
                        // only it may be dragged: hit-testing handles nobody
                        // can see drags the primary into another caret.
                        if (state.extraCarets.isNotEmpty()) return@awaitEachGesture
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
            val map = state.displayMap
            val window = state.displayWindow
            val lineHeight = state.lineHeightPx
            val gutterWidth = state.gutterWidthPx
            // A wrapped pane has nothing to scroll sideways; reading it here
            // rather than clamping the state keeps the draw pass out of
            // snapshot writes.
            val scrollX = state.effectiveScrollX

            // Everything below counts in *display* rows. The map turns them
            // back into buffer rows and the segment of the row on show.
            val firstDisplay = state.firstDisplayRow()
            // Resolving the top row first is what makes the height below it
            // honest: the map measures the block it lands in, and only then
            // does `displayRowCount` know how far the screen reaches.
            val firstBufferRow = map.bufferRowOf(firstDisplay)
            val lastDisplay = state.lastDisplayRow(firstDisplay)
            val lastBufferRow = map.bufferRowOf((lastDisplay - 1).coerceAtLeast(firstDisplay))
            val lines = state.linesWindow(firstBufferRow, lastBufferRow + 1)
            val spans = state.spansWindow()
            map.fillWindow(window, firstDisplay, lastDisplay, firstBufferRow, lines)
            val textLeft = gutterWidth + state.textPaddingPx - scrollX

            fun lineAt(row: Int): String = lines.getOrElse(row - firstBufferRow) { "" }

            /** The text this display row shows — the whole row when it fits. */
            fun textOf(i: Int): String {
                val line = lineAt(window.bufferRow(i))
                return state.segmentText(
                    line,
                    window.startCol(i),
                    min(window.endCol(i), line.length),
                )
            }

            fun layoutOf(i: Int): TextLayoutResult {
                val row = window.bufferRow(i)
                return layoutCache.layoutFor(
                    textOf(i),
                    spansIn(
                        spans.getOrElse(row - firstBufferRow) { emptyList() },
                        window.startCol(i),
                        window.endCol(i),
                    ),
                )
            }

            /** Left edge of this display row's text, continuation indent included. */
            fun leftOf(i: Int): Float = textLeft + window.indentColumns(i) * state.charWidthPx

            fun topOf(i: Int): Float = (firstDisplay + i) * lineHeight - state.scrollY

            /**
             * Paint UTF-16 range [from, to) of one buffer row, across however
             * many display rows it is spread over. [includeNewline] adds the
             * half-character tail that shows a whole row is selected.
             */
            fun paintSpan(
                row: Int,
                from: Int,
                to: Int,
                color: Color,
                includeNewline: Boolean,
                minWidth: Float,
            ) {
                var i = window.firstIndexOf(row)
                if (i < 0) return
                val line = lineAt(row)
                while (i < window.size && window.bufferRow(i) == row) {
                    val segmentStart = window.startCol(i)
                    val segmentEnd = min(window.endCol(i), line.length)
                    // Only the segment that ends the row carries the newline.
                    val tail = includeNewline && segmentEnd >= line.length
                    val overlaps = from <= segmentEnd && to >= segmentStart
                    val left = max(from, segmentStart)
                    val right = max(left, min(to, segmentEnd))
                    // A range that collapses to nothing here is still drawn
                    // where [minWidth] says so — a search hit of zero width
                    // has to be visible somewhere.
                    if (overlaps && (right > left || tail || minWidth > 0f)) {
                        val layout = layoutOf(i)
                        val x0 = leftOf(i) + layout.getHorizontalPosition(left - segmentStart, true)
                        var x1 =
                            leftOf(i) + layout.getHorizontalPosition(right - segmentStart, true)
                        if (tail) x1 += state.charWidthPx / 2f
                        drawRect(
                            color = color,
                            topLeft = Offset(x0, topOf(i)),
                            size = Size((x1 - x0).coerceAtLeast(minWidth), lineHeight),
                        )
                    }
                    i++
                }
            }

            // Current-line highlight, under everything else. It is the *one*
            // cursor's line: with a column of carets there is no single active
            // line, and striping half the screen would only be noise. Every
            // display row of a wrapped line is highlighted, the way Zed treats
            // a wrapped line as one line.
            val selection = state.selectionRange()
            val extras = state.extraCarets
            if (selection == null && extras.isEmpty()) {
                for (i in 0 until window.size) {
                    if (window.bufferRow(i) != state.cursorRow) continue
                    val top = topOf(i)
                    if (top + lineHeight <= 0f || top >= size.height) continue
                    // Across the gutter as well: Zed's `current_line_highlight`
                    // defaults to "all" (assets/settings/default.json:314), and a
                    // highlight that stops at the gutter draws a seam down the
                    // page that Zed does not have.
                    drawRect(
                        color = theme.color("editor.active_line.background"),
                        topLeft = Offset(0f, top),
                        size = Size(size.width, lineHeight),
                    )
                }
            }

            // Indent guides. Zed draws them by default
            // (`indent_guides.enabled: true`, assets/settings/default.json:706)
            // and they are most of what makes deep code readable at a phone's
            // font size. One line per level the row is indented past, in
            // `editor.indent_guide`, with the level the cursor sits at drawn in
            // `editor.indent_guide_active` — the theme leaves both keys out and
            // ZedTheme derives them.
            //
            // Per row rather than per block: a block-aware guide needs the tree
            // the outline work will bring, and the per-row form is right for
            // every case except a blank line inside a block, where Zed carries
            // the guide through and we do not. A wrapped row keeps its guides on
            // every segment, which is what makes the continuation legible as
            // part of the same block.
            if (state.tabSize > 0) {
                val guide = theme.color("editor.indent_guide")
                val activeGuide = theme.color("editor.indent_guide_active")
                val guideWidth = state.cursorWidthPx / 2f
                val step = state.charWidthPx * state.tabSize
                val activeLevel = indentLevels(state.line(state.cursorRow), state.tabSize)
                clipRect(left = gutterWidth) {
                    for (i in 0 until window.size) {
                        val levels = indentLevels(lineAt(window.bufferRow(i)), state.tabSize)
                        val top = topOf(i)
                        for (level in 0 until levels) {
                            val x = textLeft + level * step
                            if (x < gutterWidth || x > size.width) continue
                            drawRect(
                                color = if (level == activeLevel - 1) activeGuide else guide,
                                topLeft = Offset(x, top),
                                size = Size(guideWidth, lineHeight),
                            )
                        }
                    }
                }
            }

            clipRect(left = gutterWidth) {
                // Search hits, under everything else: Zed paints them as a
                // background wash with the current one picked out
                // (`search.match_background` / `search.active_match_background`).
                if (state.searchMatches.isNotEmpty()) {
                    val match = theme.color("search.match_background")
                    val active = theme.color("search.active_match_background")
                    val windowFirst = window.firstBufferRow()
                    val windowLast = window.lastBufferRow()
                    state.searchMatches.forEachIndexed { index, range ->
                        if (range.endRow < windowFirst || range.startRow > windowLast) {
                            return@forEachIndexed
                        }
                        val color = if (index == state.activeMatch) active else match
                        val rows = max(range.startRow, windowFirst)..min(range.endRow, windowLast)
                        for (row in rows) {
                            val line = lineAt(row)
                            val from = if (row == range.startRow) {
                                range.startCol.coerceAtMost(line.length)
                            } else {
                                0
                            }
                            val to = if (row == range.endRow) {
                                range.endCol.coerceAtMost(line.length)
                            } else {
                                line.length
                            }
                            paintSpan(row, from, to, color, includeNewline = false, minWidth = 1f)
                        }
                    }
                }

                fun paintSelection(startRow: Int, startCol: Int, endRow: Int, endCol: Int) {
                    val windowFirst = window.firstBufferRow()
                    val windowLast = window.lastBufferRow()
                    if (endRow < windowFirst || startRow > windowLast) return
                    for (row in max(startRow, windowFirst)..min(endRow, windowLast)) {
                        val line = lineAt(row)
                        val from = if (row == startRow) startCol.coerceAtMost(line.length) else 0
                        val to =
                            if (row == endRow) endCol.coerceAtMost(line.length) else line.length
                        paintSpan(
                            row,
                            from,
                            to,
                            theme.selection,
                            includeNewline = row < endRow,
                            minWidth = 0f,
                        )
                    }
                }

                fun paintCaret(row: Int, col: Int) {
                    if (row < window.firstBufferRow() || row > window.lastBufferRow()) return
                    val line = lineAt(row)
                    val at = col.coerceAtMost(line.length)
                    val i = window.indexOf(row, at)
                    if (i < 0) return
                    val layout = layoutOf(i)
                    val caretX =
                        leftOf(i) + layout.getHorizontalPosition(at - window.startCol(i), true)
                    if (caretX < gutterWidth - 1f) return
                    drawRect(
                        color = theme.cursor,
                        topLeft = Offset(caretX, topOf(i)),
                        // Zed's `px(2.)` is 2 *density-independent* pixels;
                        // 2f here is 2 physical ones, a hairline on a phone.
                        size = Size(state.cursorWidthPx, lineHeight),
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
                for (i in 0 until window.size) {
                    val layout = layoutOf(i)
                    // Only an unwrapped pane has a horizontal extent to track;
                    // a wrapped one never overflows and noting a width here
                    // would leave a stale extent behind when wrapping is
                    // turned off again.
                    if (!state.softWrap.wraps) state.noteContentWidth(layout.size.width.toFloat())
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(
                            leftOf(i),
                            topOf(i) + (lineHeight - layout.size.height) / 2f,
                        ),
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

            // Gutter: background, right-aligned line numbers.
            drawRect(
                color = theme.color("editor.gutter.background"),
                topLeft = Offset.Zero,
                size = Size(gutterWidth, size.height),
            )
            // No divider between gutter and text: Zed draws none
            // (crates/editor/src/element.rs:4905), and the line we drew read
            // as a pane border where there is no pane.
            val lineNumber = theme.color("editor.line_number")
            val activeLineNumber = theme.color("editor.active_line_number")
            for (i in 0 until window.size) {
                // A wrapped row is numbered once, on the segment it starts on —
                // the number belongs to the file's row, not the screen's.
                if (!window.isFirstSegment(i)) continue
                val row = window.bufferRow(i)
                val layout = layoutCache.layoutFor((row + 1).toString())
                drawText(
                    textLayoutResult = layout,
                    color = if (row == state.cursorRow) activeLineNumber else lineNumber,
                    topLeft = Offset(
                        gutterWidth - state.gutterPaddingPx - layout.size.width,
                        topOf(i) + (lineHeight - layout.size.height) / 2f,
                    ),
                )
            }

            // The scrollbar, over everything: Zed's is a 15px track down the
            // right edge (crates/ui/src/components/scrollbar.rs:376) and on a
            // phone it earns its width twice over, as the only way to cross a
            // long file without a hundred flings.
            val maxScroll = state.maxScrollY
            if (maxScroll > 0f) {
                val trackWidth = state.charWidthPx.coerceIn(10f, 24f)
                val trackLeft = size.width - trackWidth
                drawRect(
                    color = theme.color("scrollbar.track.background"),
                    topLeft = Offset(trackLeft, 0f),
                    size = Size(trackWidth, size.height),
                )
                val visible =
                    (size.height / (map.displayRowCount * lineHeight)).coerceIn(0f, 1f)
                val thumbHeight = (size.height * visible).coerceAtLeast(trackWidth * 2f)
                val thumbTop = (state.scrollY / maxScroll) * (size.height - thumbHeight)
                drawRect(
                    color = theme.color("scrollbar.thumb.background"),
                    topLeft = Offset(trackLeft, thumbTop),
                    size = Size(trackWidth, thumbHeight),
                )
                drawRect(
                    color = theme.color("scrollbar.thumb.border"),
                    topLeft = Offset(trackLeft, thumbTop),
                    size = Size(1f, thumbHeight),
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
        ActionKey("tab", act { state.insertAtCursor(state.indentUnit()) })
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
 *
 * A handle hangs off the *display* row its end sits on, and off that row's
 * own left edge — a selection ending inside a wrapped line's third segment
 * gets its handle under that segment, not under the line's first.
 */
private fun selectionHandles(
    state: EditorState,
    layoutCache: TextLayoutCache,
): Pair<Offset, Offset>? {
    val range = state.selectionRange() ?: return null
    fun at(row: Int, col: Int): Offset {
        val line = state.line(row)
        val at = col.coerceAtMost(line.length)
        val wrap = state.displayMap.wrapOf(line)
        val segment = wrap.segmentOf(at)
        val start = wrap.startOf(segment)
        val end = wrap.endOf(segment, line.length)
        val layout = layoutCache.layoutFor(
            state.segmentText(line, start, end),
            spansIn(state.spansFor(row), start, if (wrap.wraps) end else Int.MAX_VALUE),
        )
        val indentPx = if (segment > 0) wrap.indentColumns * state.charWidthPx else 0f
        val x = state.gutterWidthPx + state.textPaddingPx - state.effectiveScrollX + indentPx +
            layout.getHorizontalPosition(at - start, true)
        val display = state.displayRowOf(row, at)
        return Offset(x, (display + 1) * state.lineHeightPx - state.scrollY)
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
        state.collapseSelections()
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
            state.displayRowOf(range.startRow, range.startCol) * state.lineHeightPx - state.scrollY,
        )
        val bottomLocal =
            (state.displayRowOf(range.endRow, range.endCol) + 1) * state.lineHeightPx - state.scrollY
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
            // Ctrl+arrow is word-wise; Ctrl+Home/End is the whole document.
            Key.DirectionLeft -> {
                state.moveByWord(forward = false, extend = shift)
                true
            }
            Key.DirectionRight -> {
                state.moveByWord(forward = true, extend = shift)
                true
            }
            Key.MoveHome -> {
                state.moveToDocumentStart(extend = shift)
                true
            }
            Key.MoveEnd -> {
                state.moveToDocumentEnd(extend = shift)
                true
            }
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
            state.insertAtCursor(state.indentUnit())
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
        Key.MoveHome -> {
            state.moveToLineStart(extend)
            true
        }
        Key.MoveEnd -> {
            state.moveToLineEnd(extend)
            true
        }
        Key.PageUp -> {
            state.movePage(down = false, extend = extend)
            true
        }
        Key.PageDown -> {
            state.movePage(down = true, extend = extend)
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
