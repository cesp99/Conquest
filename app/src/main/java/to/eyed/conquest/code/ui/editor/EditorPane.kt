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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
import to.eyed.conquest.code.core.GitHunk
import to.eyed.conquest.code.core.GitHunkKind
import to.eyed.conquest.code.ui.git.blameText
import to.eyed.conquest.code.ui.git.rememberGitAnnotations
import to.eyed.conquest.code.ui.workspace.GitStatusColours
import kotlin.math.floor
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
    /**
     * Whether to show who last touched the caret's line — Zed's
     * `git.inline_blame`, whose default is on. Off here by default so the
     * host tests and any caller with no setting get a pane that runs no git.
     */
    showInlineBlame: Boolean = false,
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
    // git, for the gutter and the end of the caret's line. Cheap when there is
    // no repository: the engine answers with no hunks and blame is not asked
    // for at all unless it is switched on.
    val git = rememberGitAnnotations(state, showInlineBlame)
    val gitColours = remember(theme) {
        GitStatusColours.from(theme, theme.color("editor.foreground"))
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

    // Folding's two pointer states. Zed shows the unfolded chevrons for
    // every foldable row while the pointer is over the gutter
    // (`gutter_hovered`, crates/editor/src/fold.rs:57); the chip washes to
    // `ghost_element.hover` under the pointer (fold_map.rs:68). Both are
    // mouse affordances — touch gets the caret-row chevron and the chips
    // regardless.
    var gutterHovered by remember { mutableStateOf(false) }
    var hoveredChipRow by remember { mutableStateOf(-1) }

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
                // Fold toggles: the chevron's column in the gutter and the
                // "⋯" chip after a folded line. Claimed in the initial pass,
                // like Alt+click above, so a tap on either never also moves
                // the caret; touch and mouse arrive through the same press.
                .pointerInput(state) {
                    awaitEachGesture {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Press) return@awaitEachGesture
                        val down = event.changes.firstOrNull() ?: return@awaitEachGesture
                        if (down.isConsumed) return@awaitEachGesture
                        val position = down.position
                        if (position.x < state.gutterWidthPx) {
                            // Only the fold column folds; the rest of the
                            // gutter keeps its caret-placing tap. The column
                            // is 4 characters wide — comfortably past the
                            // density decision's floor without inflating
                            // anything.
                            if (position.x < state.gutterWidthPx - state.gutterFoldColumnPx) {
                                return@awaitEachGesture
                            }
                            val display =
                                ((position.y + state.scrollY) / state.lineHeightPx).toInt()
                            if (display < 0) return@awaitEachGesture
                            val row = state.displayMap.bufferRowOf(display)
                            // Zed's chevron: a folded row unfolds, a foldable
                            // one folds (fold.rs:60-68). A row that is
                            // neither lets the tap fall through untouched.
                            if (state.toggleFoldAt(row)) down.consume()
                            return@awaitEachGesture
                        }
                        val chipRow = foldChipRowAt(state, layoutCache, position)
                        if (chipRow != null && state.unfoldRowsTouching(chipRow..chipRow)) {
                            // Zed's placeholder unfolds on click
                            // (editor.rs:1949-1961).
                            down.consume()
                        }
                    }
                }
                // Hover, for the mouse: the gutter's chevrons and the chip's
                // wash. Watched rather than composed because everything here
                // is canvas-drawn.
                .pointerInput(state) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            when (event.type) {
                                PointerEventType.Move, PointerEventType.Enter -> {
                                    val position = event.changes.firstOrNull()?.position
                                    if (position != null) {
                                        val overGutter = position.x < state.gutterWidthPx
                                        if (gutterHovered != overGutter) gutterHovered = overGutter
                                        val chip =
                                            foldChipRowAt(state, layoutCache, position) ?: -1
                                        if (hoveredChipRow != chip) hoveredChipRow = chip
                                    }
                                }
                                PointerEventType.Exit -> {
                                    if (gutterHovered) gutterHovered = false
                                    if (hoveredChipRow >= 0) hoveredChipRow = -1
                                }
                                else -> {}
                            }
                        }
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
            // Read the rows the frame *draws*, not the stretch of file
            // between the first of them and the last: with a block folded on
            // screen those two are as far apart as the block is long, and
            // reading between them would put the whole fold on the UI thread
            // of every keystroke. See [EditorState.visibleRows].
            val rows = state.visibleRows(firstBufferRow, lastBufferRow)
            map.fillWindow(window, firstDisplay, lastDisplay, firstBufferRow, rows::text)
            val textLeft = gutterWidth + state.textPaddingPx - scrollX

            fun lineAt(row: Int): String = rows.text(row)

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
                    spansIn(rows.spans(row), window.startCol(i), window.endCol(i)),
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
                        // Stepping by *visible* rows: a hit that spans a
                        // folded block covers every row of it, and the frame
                        // paints only the ones it drew.
                        var row = max(range.startRow, windowFirst)
                        val lastRow = min(range.endRow, windowLast)
                        while (row <= lastRow) {
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
                            row = map.nextVisibleRow(row + 1)
                        }
                    }
                }

                fun paintSelection(startRow: Int, startCol: Int, endRow: Int, endCol: Int) {
                    val windowFirst = window.firstBufferRow()
                    val windowLast = window.lastBufferRow()
                    if (endRow < windowFirst || startRow > windowLast) return
                    // Visible rows only, the same as the search hits above: a
                    // selection is allowed to span a fold — it paints across
                    // it — and a Ctrl+A on a folded file must not cost the
                    // frame a row of work per row of the file.
                    var row = max(startRow, windowFirst)
                    val lastRow = min(endRow, windowLast)
                    while (row <= lastRow) {
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
                        row = map.nextVisibleRow(row + 1)
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

                // Fold chips: Zed's placeholder for a folded block — "⋯" in
                // the buffer font, `text_placeholder` on
                // `ghost_element_background`, `rounded_xs` (2px), washing to
                // `ghost_element_hover` under the pointer
                // (display_map/fold_map.rs:53-72); the editor's own
                // placeholder adds the click that unfolds
                // (editor.rs:1941-1963). Width is the glyph's, height the
                // whole line — `size_full` of the inline slot.
                if (state.folds.isNotEmpty()) {
                    val chipLayout = layoutCache.layoutFor("⋯")
                    val chipBg = theme.color("ghost_element.background", Color.Transparent)
                    val chipHover = theme.color("ghost_element.hover", chipBg)
                    val chipInk = theme.color("text.placeholder", theme.color("text.muted"))
                    val chipRadius = CornerRadius(2.dp.toPx())
                    for (i in 0 until window.size) {
                        val row = window.bufferRow(i)
                        if (state.foldStartingAt(row) == null) continue
                        val line = lineAt(row)
                        // Only the segment that carries the end of the text
                        // carries the chip.
                        if (min(window.endCol(i), line.length) < line.length) continue
                        val x = leftOf(i) + layoutOf(i).size.width
                        drawRoundRect(
                            color = if (row == hoveredChipRow) chipHover else chipBg,
                            topLeft = Offset(x, topOf(i)),
                            size = Size(chipLayout.size.width.toFloat(), lineHeight),
                            cornerRadius = chipRadius,
                        )
                        drawText(
                            textLayoutResult = chipLayout,
                            color = chipInk,
                            topLeft = Offset(
                                x,
                                topOf(i) + (lineHeight - chipLayout.size.height) / 2f,
                            ),
                        )
                    }
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
            // git's own strip down the left of the gutter — Zed's, at Zed's
            // width: floor(0.275 × line height) (element.rs:5322-5327), with
            // the colours the project panel already uses for the same states.
            if (git.hunks.isNotEmpty()) {
                val strip = floor(0.275f * lineHeight)
                for (i in 0 until window.size) {
                    val hunk = hunkAt(git.hunks, window.bufferRow(i)) ?: continue
                    if (hunk.kind == GitHunkKind.Deleted) continue
                    drawRect(
                        color = when (hunk.kind) {
                            GitHunkKind.Added -> gitColours.added
                            else -> gitColours.modified
                        },
                        topLeft = Offset(0f, topOf(i)),
                        size = Size(strip, lineHeight),
                    )
                }
                // A deletion occupies no rows, so Zed draws it as a rounded
                // pill straddling the boundary above the row that replaced it
                // (element.rs:5265-5275) — wider than the strip, and centred
                // on the line between two rows rather than on a row.
                val pill = floor(0.35f * lineHeight)
                for (hunk in git.hunks) {
                    if (hunk.kind != GitHunkKind.Deleted) continue
                    val at = firstSegmentOf(window, hunk.startRow) ?: continue
                    drawRoundRect(
                        color = gitColours.deleted,
                        topLeft = Offset(0f, topOf(at) - lineHeight / 2f),
                        size = Size(pill * 2f, lineHeight),
                        cornerRadius = CornerRadius(lineHeight),
                    )
                }
            }

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
                    // The numbers end where the fold column begins — Zed's
                    // `right_padding` of `em_width * 4` with folds on
                    // (editor.rs:11758-11760), which is what keeps the
                    // chevrons off the digits.
                    topLeft = Offset(
                        gutterWidth - state.gutterFoldColumnPx - layout.size.width,
                        topOf(i) + (lineHeight - layout.size.height) / 2f,
                    ),
                )
            }

            // Fold chevrons, centred in the gutter's fold column. Zed shows
            // one on every folded row; an unfolded foldable row earns its
            // chevron when the caret sits on it or the gutter is hovered
            // (`render_crease_toggle`, fold.rs:57-73). The glyph is
            // Disclosure's ChevronRight / ChevronDown at IconSize::Small in
            // Color::Muted (ui/src/components/disclosure.rs:96-131), drawn
            // here as two strokes because the canvas owns the gutter.
            run {
                val chevronInk = theme.color("text.muted")
                val arm = 3.5.dp.toPx()
                val stroke = 1.5.dp.toPx()
                val cx = gutterWidth - state.gutterFoldColumnPx / 2f
                for (i in 0 until window.size) {
                    if (!window.isFirstSegment(i)) continue
                    val row = window.bufferRow(i)
                    val folded = state.foldStartingAt(row) != null
                    if (!folded &&
                        !(
                            (row == state.cursorRow || gutterHovered) &&
                                state.rowIsFoldable(row)
                            )
                    ) {
                        continue
                    }
                    val cy = topOf(i) + lineHeight / 2f
                    if (folded) {
                        // ChevronRight: the block is closed.
                        drawLine(
                            color = chevronInk,
                            start = Offset(cx - arm / 2f, cy - arm),
                            end = Offset(cx + arm / 2f, cy),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = chevronInk,
                            start = Offset(cx + arm / 2f, cy),
                            end = Offset(cx - arm / 2f, cy + arm),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    } else {
                        // ChevronDown: the block is open and can close.
                        drawLine(
                            color = chevronInk,
                            start = Offset(cx - arm, cy - arm / 2f),
                            end = Offset(cx, cy + arm / 2f),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = chevronInk,
                            start = Offset(cx, cy + arm / 2f),
                            end = Offset(cx + arm, cy - arm / 2f),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }

            // Who last touched the caret's line, after the end of it — Zed's
            // inline blame (git_ui/src/blame_ui.rs:280-300). Only on the
            // caret's own line, only when the buffer is clean, and never
            // covering text: it starts a couple of characters past the end of
            // the line, and it is the first thing the clip drops when the
            // pane is too narrow for it.
            if (showInlineBlame) {
                git.blameAt(state.cursorRow)?.let { line ->
                    val at = firstSegmentOf(window, state.cursorRow)
                    if (at != null) {
                        val text = blameText(line, System.currentTimeMillis() / 1000L)
                        val layout = layoutCache.layoutFor(text)
                        val lineEnd = layoutCache
                            .layoutFor(lineAt(state.cursorRow))
                            .size.width.toFloat()
                        clipRect(left = gutterWidth) {
                            drawText(
                                textLayoutResult = layout,
                                color = theme.color("hint", theme.color("text.muted")),
                                topLeft = Offset(
                                    textLeft + lineEnd + state.charWidthPx * 3f,
                                    topOf(at) + (lineHeight - layout.size.height) / 2f,
                                ),
                            )
                        }
                    }
                }
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
        // Folding, reachable while the keyboard covers the gutter — the
        // convention that nothing here is keyboard-only, in both directions.
        ActionKey("fold", act { state.foldAtCarets() })
        ActionKey("unfold", act { state.unfoldAtCarets() })
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
 * Pane-local bounds of the "⋯" chip on [row], or null when the row heads no
 * fold. The chip sits immediately after the end of the row's text on the
 * segment that carries it, exactly where Zed splices the placeholder into
 * the line (the fold starts at the line's end — display_map.rs:2318-2320).
 * One function feeds both the draw pass and the hit tests, so the pixels
 * and the pointer can never disagree.
 */
private fun foldChipBounds(
    state: EditorState,
    layoutCache: TextLayoutCache,
    row: Int,
): Rect? {
    if (state.foldStartingAt(row) == null) return null
    val line = state.line(row)
    val wrap = state.displayMap.wrapOf(line)
    val segment = wrap.segmentCount - 1
    val start = wrap.startOf(segment)
    val layout = layoutCache.layoutFor(
        state.segmentText(line, start, line.length),
        spansIn(state.spansFor(row), start, if (wrap.wraps) line.length else Int.MAX_VALUE),
    )
    val indentPx = if (segment > 0) wrap.indentColumns * state.charWidthPx else 0f
    val x = state.gutterWidthPx + state.textPaddingPx - state.effectiveScrollX + indentPx +
        layout.size.width
    val display = state.displayMap.displayRowOf(row) + segment
    val top = display * state.lineHeightPx - state.scrollY
    val chipWidth = layoutCache.layoutFor("⋯").size.width.toFloat()
    return Rect(Offset(x, top), Size(chipWidth, state.lineHeightPx))
}

/**
 * The fold whose chip is under [position], or null. The hit box grows
 * sideways by half a line height — the chip is a glyph-sized target, and the
 * density decision's answer to that is an invisible expansion, not a bigger
 * chip.
 */
private fun foldChipRowAt(
    state: EditorState,
    layoutCache: TextLayoutCache,
    position: Offset,
): Int? {
    if (state.folds.isEmpty()) return null
    if (state.lineHeightPx <= 0f) return null
    val display = ((position.y + state.scrollY) / state.lineHeightPx).toInt()
    if (display < 0) return null
    val row = state.displayMap.bufferRowOf(display)
    val bounds = foldChipBounds(state, layoutCache, row) ?: return null
    val slop = state.lineHeightPx / 2f
    return if (position.x >= bounds.left - slop && position.x <= bounds.right + slop &&
        position.y >= bounds.top && position.y < bounds.bottom
    ) {
        row
    } else {
        null
    }
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

    // The second stroke of a pending `ctrl-k` chord, resolved before
    // anything else can eat the key. Zed spells FoldAll and UnfoldAll as
    // two-stroke chords in its Linux keymap: `ctrl-k ctrl-0` and
    // `ctrl-k ctrl-j` (assets/keymaps/default-linux.json:589-590). The
    // prefix waits through the modifier keys' own down events, is spent by
    // whatever real key comes next, and an unrecognised second stroke then
    // means what it always meant.
    if (state.pendingChordCtrlK) {
        val isModifier = event.key == Key.CtrlLeft || event.key == Key.CtrlRight ||
            event.key == Key.ShiftLeft || event.key == Key.ShiftRight ||
            event.key == Key.AltLeft || event.key == Key.AltRight
        if (!isModifier) {
            state.pendingChordCtrlK = false
            if (ctrl && !shift && !alt) {
                when (event.key) {
                    Key.Zero -> {
                        state.foldAllRows()
                        return true
                    }
                    Key.J -> {
                        state.unfoldAllRows()
                        return true
                    }
                    else -> {}
                }
            }
        }
    }

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
                if (shift) {
                    state.deleteLines()
                } else {
                    // Zed's `ctrl-k` chord prefix; the second stroke is
                    // matched at the top of this function.
                    state.pendingChordCtrlK = true
                }
                true
            }
            Key.J -> {
                if (shift) state.joinLines()
                shift
            }
            Key.Slash -> state.toggleComment()
            // Zed's fold pair: `ctrl-{` is ctrl-shift-[ on the keyboards
            // that reach this handler, and `ctrl-}` its twin
            // (editor::Fold / editor::UnfoldLines,
            // assets/keymaps/default-linux.json:575-576).
            Key.LeftBracket -> {
                if (shift) state.foldAtCarets()
                shift
            }
            Key.RightBracket -> {
                if (shift) state.unfoldAtCarets()
                shift
            }
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

/**
 * The hunk covering [row], or null. Binary search: a file under review can
 * have hundreds of hunks and this is asked once per drawn row, per frame.
 *
 * Deletions are skipped — they cover no rows at all ([GitHunk.endRow] equals
 * [GitHunk.startRow]) and are drawn on the boundary instead.
 */
internal fun hunkAt(hunks: List<GitHunk>, row: Int): GitHunk? {
    var low = 0
    var high = hunks.size - 1
    while (low <= high) {
        val mid = (low + high) / 2
        val hunk = hunks[mid]
        when {
            row < hunk.startRow -> high = mid - 1
            row >= hunk.endRow -> low = mid + 1
            else -> return hunk
        }
    }
    return null
}

/** Where [row] starts on screen, or null when it is not on screen. */
private fun firstSegmentOf(window: DisplayWindow, row: Int): Int? {
    for (i in 0 until window.size) {
        if (window.bufferRow(i) == row && window.isFirstSegment(i)) return i
    }
    return null
}
