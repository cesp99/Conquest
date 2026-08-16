package to.eyed.conquest.code.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import to.eyed.conquest.code.core.BufferSession
import to.eyed.conquest.code.core.CoreBridge
import to.eyed.conquest.code.core.Utf8Diff

/** One highlighted range on one row; columns are UTF-16 offsets. */
data class HighlightSpan(val start: Int, val end: Int, val style: Int)

/**
 * View state of one editor pane: scroll offsets, cursor, and a cached
 * window of visible lines fetched from the engine via the line-window JNI
 * API. The pane never holds the whole buffer — only the lines on screen.
 *
 * Reactivity split: fields the draw pass must react to (scroll, cursor)
 * are snapshot state; geometry caches and the line window are plain fields
 * mutated from event handlers and reads, never triggering recomposition
 * themselves. Don't write snapshot state from the draw phase.
 *
 * The cursor column is a UTF-16 offset within its line (what Compose text
 * layout works in). Conversion to engine byte offsets happens at edit time
 * (phase 2 IME work), not here.
 */
class EditorState(val session: BufferSession) {
    var scrollY by mutableFloatStateOf(0f)
        private set
    var scrollX by mutableFloatStateOf(0f)
        private set
    var cursorRow by mutableIntStateOf(0)
        private set
    var cursorCol by mutableIntStateOf(0)
        private set

    // Selection anchor; -1 row = no anchor. The selection is always
    // anchor..cursor (either order). Selection state lives here — the
    // EditorState is the per-pane view layer, same as Zed's Editor (Zed's
    // text::Buffer doesn't own selections either).
    var selectionAnchorRow by mutableIntStateOf(-1)
        private set
    var selectionAnchorCol by mutableIntStateOf(0)
        private set

    val hasSelection: Boolean
        get() = selectionAnchorRow >= 0 &&
            (selectionAnchorRow != cursorRow || selectionAnchorCol != cursorCol)

    /** Normalized selection: start is always before end. */
    data class SelectionRange(
        val startRow: Int,
        val startCol: Int,
        val endRow: Int,
        val endCol: Int,
    ) {
        val isMultiLine: Boolean get() = startRow != endRow
    }

    fun selectionRange(): SelectionRange? {
        if (!hasSelection) return null
        val anchorFirst = selectionAnchorRow < cursorRow ||
            (selectionAnchorRow == cursorRow && selectionAnchorCol <= cursorCol)
        return if (anchorFirst) {
            SelectionRange(selectionAnchorRow, selectionAnchorCol, cursorRow, cursorCol)
        } else {
            SelectionRange(cursorRow, cursorCol, selectionAnchorRow, selectionAnchorCol)
        }
    }

    /**
     * The engine's highlight version, as snapshot state so a reparse landing
     * repaints the view. Polled rather than pushed: the engine's bridge is
     * one-directional, and a poll of one integer is cheaper than a callback
     * into the JVM from a Rust thread.
     */
    var highlightVersion by mutableLongStateOf(0L)
        private set

    /** True if the engine has newer spans than the ones last drawn. */
    fun refreshHighlightVersion(): Boolean {
        val version = session.highlightVersion
        if (version == highlightVersion) return false
        highlightVersion = version
        return true
    }

    /** Not snapshot state: refreshed from the engine in [refreshLineCount]. */
    var lineCount: Int = session.lineCount
        private set

    // Pixel metrics, set from composition (density-dependent).
    var lineHeightPx = 1f
        private set
    var charWidthPx = 1f
        private set
    var gutterPaddingPx = 0f
        private set
    var textPaddingPx = 0f
        private set

    // Viewport size, set from the draw pass (plain fields — see class doc).
    private var viewportWidth = 0f
    private var viewportHeight = 0f

    /** Widest line width seen so far, for the horizontal scroll extent. */
    private var contentWidthPx = 0f

    // The fetched window is padded by [WINDOW_PADDING] rows beyond what the
    // viewport asked for, so scrolling only crosses the JNI boundary (and
    // re-runs the highlight query) every few dozen rows instead of every
    // row.
    private var cachedLines: List<String> = emptyList()
    private var cachedSpans: List<List<HighlightSpan>> = emptyList()
    private var cachedFirst = -1
    private var cachedLast = -1
    private var cachedVersion = -1L
    private var cachedHighlightVersion = -1L
    private var requestedFirst = 0
    private var requestedLast = 0

    private companion object {
        const val WINDOW_PADDING = 32
    }

    val gutterWidthPx: Float
        get() = lineCount.toString().length.coerceAtLeast(2) * charWidthPx + 2 * gutterPaddingPx

    fun updateMetrics(lineHeight: Float, charWidth: Float, gutterPadding: Float, textPadding: Float) {
        lineHeightPx = lineHeight
        charWidthPx = charWidth
        gutterPaddingPx = gutterPadding
        textPaddingPx = textPadding
    }

    fun updateViewport(width: Float, height: Float) {
        viewportWidth = width
        viewportHeight = height
    }

    /**
     * The lines of rows [first, last) — cached, re-fetched over JNI only
     * when the window or the buffer version changed.
     */
    fun linesWindow(first: Int, last: Int): List<String> {
        val version = session.version
        val miss = version != cachedVersion ||
            highlightVersion != cachedHighlightVersion ||
            first < cachedFirst ||
            last > cachedLast
        if (miss) {
            val paddedFirst = (first - WINDOW_PADDING).coerceAtLeast(0)
            val paddedLast = (last + WINDOW_PADDING).coerceAtMost(lineCount)
            if (paddedLast > paddedFirst) {
                cachedLines = CoreBridge
                    .bufferLines(session.id, paddedFirst.toLong(), paddedLast.toLong())
                    .orEmpty()
                    .split('\n')
                cachedSpans = groupSpans(
                    CoreBridge.bufferHighlights(
                        session.id,
                        paddedFirst.toLong(),
                        paddedLast.toLong(),
                    ),
                    paddedFirst,
                    cachedLines.size,
                )
            } else {
                cachedLines = emptyList()
                cachedSpans = emptyList()
            }
            cachedFirst = paddedFirst
            cachedLast = paddedFirst + cachedLines.size
            cachedVersion = version
            cachedHighlightVersion = highlightVersion
        }
        requestedFirst = first.coerceIn(cachedFirst, cachedLast)
        requestedLast = last.coerceIn(requestedFirst, cachedLast)
        return cachedLines.subList(requestedFirst - cachedFirst, requestedLast - cachedFirst)
    }

    /**
     * Highlight spans for the window last returned by [linesWindow],
     * parallel to its returned lines.
     */
    fun spansWindow(): List<List<HighlightSpan>> =
        cachedSpans.subList(requestedFirst - cachedFirst, requestedLast - cachedFirst)

    fun spansFor(row: Int): List<HighlightSpan> =
        if (row in cachedFirst until cachedLast && cachedVersion == session.version) {
            cachedSpans.getOrElse(row - cachedFirst) { emptyList() }
        } else {
            emptyList()
        }

    /** Flat [row, start, end, style] groups → per-row span lists. */
    private fun groupSpans(flat: IntArray?, firstRow: Int, rowCount: Int): List<List<HighlightSpan>> {
        if (flat == null || flat.isEmpty()) return List(rowCount) { emptyList() }
        val grouped = List(rowCount) { mutableListOf<HighlightSpan>() }
        var i = 0
        while (i + 3 < flat.size) {
            val row = flat[i] - firstRow
            if (row in 0 until rowCount) {
                grouped[row].add(HighlightSpan(flat[i + 1], flat[i + 2], flat[i + 3]))
            }
            i += 4
        }
        return grouped
    }

    fun line(row: Int): String =
        if (row in cachedFirst until cachedLast && cachedVersion == session.version) {
            cachedLines[row - cachedFirst]
        } else {
            CoreBridge.bufferLines(session.id, row.toLong(), row.toLong() + 1).orEmpty()
        }

    /** Call after anything that may change the buffer contents. */
    fun refreshLineCount() {
        lineCount = session.lineCount
    }

    /** Track line widths seen during drawing to bound horizontal scroll. */
    fun noteContentWidth(width: Float) {
        if (width > contentWidthPx) contentWidthPx = width
    }

    /**
     * Consume a vertical scrollable delta (positive = finger moving down,
     * which scrolls the content up). Returns the consumed amount.
     */
    fun applyScrollDeltaY(delta: Float): Float {
        val maxY = (lineCount * lineHeightPx - viewportHeight).coerceAtLeast(0f)
        val new = (scrollY - delta).coerceIn(0f, maxY)
        val consumed = scrollY - new
        scrollY = new
        return consumed
    }

    /** Horizontal counterpart of [applyScrollDeltaY]. */
    fun applyScrollDeltaX(delta: Float): Float {
        val contentAreaWidth = (viewportWidth - gutterWidthPx).coerceAtLeast(0f)
        val maxX = (contentWidthPx + textPaddingPx + charWidthPx - contentAreaWidth)
            .coerceAtLeast(0f)
        val new = (scrollX - delta).coerceIn(0f, maxX)
        val consumed = scrollX - new
        scrollX = new
        return consumed
    }

    /** (row, UTF-16 col) at a pane-local pixel position. */
    fun positionAt(point: Offset, layoutForLine: (String) -> TextLayoutResult): Pair<Int, Int> {
        val row = ((point.y + scrollY) / lineHeightPx).toInt().coerceIn(0, lineCount - 1)
        val xInText = point.x - gutterWidthPx - textPaddingPx + scrollX
        val layout = layoutForLine(line(row))
        return row to layout.getOffsetForPosition(Offset(xInText.coerceAtLeast(0f), 0f))
    }

    /**
     * Move the cursor to the position tapped at [tap] (pane-local pixels),
     * clearing any selection. [layoutForLine] measures a line's text so
     * the horizontal hit can land between the right glyphs.
     */
    fun moveCursorTo(tap: Offset, layoutForLine: (String) -> TextLayoutResult) {
        val (row, col) = positionAt(tap, layoutForLine)
        clearSelection()
        cursorRow = row
        cursorCol = col
        onCursorChangedExternally?.invoke()
    }

    // ---- Selection -------------------------------------------------------

    fun clearSelection() {
        selectionAnchorRow = -1
        selectionAnchorCol = 0
    }

    /**
     * Move one selection endpoint during a drag: the cursor end follows the
     * pointer while the anchor stays. If [movingStart] the drag started on
     * the start handle, so anchor and cursor are swapped as needed.
     */
    fun dragSelectionEndTo(
        point: Offset,
        movingStart: Boolean,
        layoutForLine: (String) -> TextLayoutResult,
    ) {
        val range = selectionRange() ?: return
        val (row, col) = positionAt(point, layoutForLine)
        if (movingStart) {
            selectionAnchorRow = range.endRow
            selectionAnchorCol = range.endCol
        } else {
            selectionAnchorRow = range.startRow
            selectionAnchorCol = range.startCol
        }
        cursorRow = row
        cursorCol = col
        onCursorChangedExternally?.invoke()
    }

    /** Extend (or start) a selection from the current cursor to [point]. */
    fun extendSelectionTo(point: Offset, layoutForLine: (String) -> TextLayoutResult) {
        if (selectionAnchorRow < 0) {
            selectionAnchorRow = cursorRow
            selectionAnchorCol = cursorCol
        }
        val (row, col) = positionAt(point, layoutForLine)
        cursorRow = row
        cursorCol = col
        onCursorChangedExternally?.invoke()
    }

    /** Select the word (or symbol run) at a pane-local pixel position. */
    fun selectWordAt(point: Offset, layoutForLine: (String) -> TextLayoutResult) {
        val (row, col) = positionAt(point, layoutForLine)
        val line = line(row)
        if (line.isEmpty()) {
            cursorRow = row
            cursorCol = 0
            clearSelection()
            onCursorChangedExternally?.invoke()
            return
        }
        val at = col.coerceIn(0, line.length - 1)
        // Expand over a run of the same character class (word chars,
        // whitespace, or other symbols).
        fun charClass(c: Char): Int = when {
            c.isLetterOrDigit() || c == '_' -> 0
            c.isWhitespace() -> 1
            else -> 2
        }
        val target = charClass(line[at])
        var start = at
        while (start > 0 && charClass(line[start - 1]) == target) start--
        var end = at + 1
        while (end < line.length && charClass(line[end]) == target) end++
        selectionAnchorRow = row
        selectionAnchorCol = start
        cursorRow = row
        cursorCol = end
        onCursorChangedExternally?.invoke()
    }

    fun selectAll() {
        selectionAnchorRow = 0
        selectionAnchorCol = 0
        cursorRow = lineCount - 1
        cursorCol = currentLine().length
        onCursorChangedExternally?.invoke()
    }

    /** The selected text, or "" without a selection. */
    fun selectionText(): String {
        val range = selectionRange() ?: return ""
        val lines = CoreBridge
            .bufferLines(session.id, range.startRow.toLong(), range.endRow.toLong() + 1)
            .orEmpty()
            .split('\n')
        if (lines.isEmpty()) return ""
        if (!range.isMultiLine) {
            val line = lines[0]
            return line.substring(
                range.startCol.coerceAtMost(line.length),
                range.endCol.coerceAtMost(line.length),
            )
        }
        return buildString {
            append(lines.first().substring(range.startCol.coerceAtMost(lines.first().length)))
            for (i in 1 until lines.size - 1) {
                append('\n')
                append(lines[i])
            }
            append('\n')
            append(lines.last().substring(0, range.endCol.coerceAtMost(lines.last().length)))
        }
    }

    /** Delete the selected range; cursor lands at its start. */
    fun deleteSelection(): Boolean {
        val range = selectionRange() ?: return false
        val start = byteOffsetOf(range.startRow, range.startCol)
        val end = byteOffsetOf(range.endRow, range.endCol)
        clearSelection()
        if (end > start) session.editBytes(start, end, "")
        refreshLineCount()
        cursorRow = range.startRow
        cursorCol = range.startCol
        ensureCursorVisible()
        return true
    }

    /** Global byte offset of (row, UTF-16 col). */
    fun byteOffsetOf(row: Int, colUtf16: Int): Long {
        val line = line(row)
        val prefix = line.substring(0, colUtf16.coerceIn(0, line.length))
        return lineStartOffset(row) + prefix.encodeToByteArray().size
    }

    // ---- Editing ---------------------------------------------------------

    /**
     * Invoked when the cursor or buffer changes through anything except
     * [applyLineDiff] (taps, hardware keys, undo). The IME session listens
     * so it can re-seed its per-line shadow of the buffer.
     */
    var onCursorChangedExternally: (() -> Unit)? = null

    fun currentLine(): String = line(cursorRow)

    fun lineStartOffset(row: Int): Long =
        CoreBridge.pointToOffset(session.id, row.toLong(), 0)

    /**
     * Replace the whole content of [row] with [newLine] (diffed down to a
     * minimal engine edit) and put the cursor at UTF-16 offset [selUtf16]
     * of the new content. This is the IME write path: the input connection
     * hands us its per-line shadow after every operation.
     *
     * Returns true if the edit changed the line structure (a newline was
     * inserted), in which case the caller must re-seed its shadow.
     */
    fun applyLineDiff(row: Int, newLine: String, selUtf16: Int): Boolean {
        val oldLine = line(row)
        if (oldLine == newLine) {
            cursorRow = row
            cursorCol = selUtf16.coerceIn(0, newLine.length)
            ensureCursorVisible()
            return false
        }
        // Any IME text change collapses the selection (a seeded in-line
        // selection has just been replaced by the edit).
        clearSelection()
        val edit = Utf8Diff.diff(oldLine.encodeToByteArray(), newLine.encodeToByteArray())
        val lineStart = lineStartOffset(row)
        if (edit != null) {
            session.editBytes(lineStart + edit.start, lineStart + edit.end, edit.replacement)
        }
        val structural = '\n' in newLine
        if (structural) {
            val cursorOffset = lineStart +
                newLine.substring(0, selUtf16.coerceIn(0, newLine.length))
                    .encodeToByteArray().size
            val packed = CoreBridge.offsetToPoint(session.id, cursorOffset)
            val newRow = (packed ushr 32).toInt()
            val byteCol = (packed and 0xFFFFFFFFL).toInt()
            cursorRow = newRow
            cursorCol = utf16Col(line(newRow), byteCol)
        } else {
            cursorRow = row
            cursorCol = selUtf16.coerceIn(0, newLine.length)
        }
        refreshLineCount()
        ensureCursorVisible()
        return structural
    }

    /** Insert [text] at the cursor (hardware-key path), replacing any
     * selection. */
    fun insertAtCursor(text: String) {
        deleteSelection()
        val line = currentLine()
        val col = cursorCol.coerceAtMost(line.length)
        applyLineDiff(cursorRow, line.take(col) + text + line.drop(col), col + text.length)
        onCursorChangedExternally?.invoke()
    }

    /** Delete the selection, or the code point before the cursor (joining
     * lines at column 0). */
    fun backspace() {
        if (deleteSelection()) {
            onCursorChangedExternally?.invoke()
            return
        }
        val line = currentLine()
        val col = cursorCol.coerceAtMost(line.length)
        if (col > 0) {
            val previous = line.offsetByCodePoints(col, -1)
            applyLineDiff(cursorRow, line.take(previous) + line.drop(col), previous)
        } else {
            joinWithPreviousLine()
        }
        onCursorChangedExternally?.invoke()
    }

    /** Remove the newline before the cursor's line. No-op on row 0. */
    fun joinWithPreviousLine(): Boolean {
        if (cursorRow == 0) return false
        val previousLine = line(cursorRow - 1)
        val lineStart = lineStartOffset(cursorRow)
        session.editBytes(lineStart - 1, lineStart, "")
        refreshLineCount()
        cursorRow -= 1
        cursorCol = previousLine.length
        ensureCursorVisible()
        return true
    }

    fun undo() {
        if (session.undo()) afterHistoryChange()
    }

    fun redo() {
        if (session.redo()) afterHistoryChange()
    }

    fun moveCursorHorizontally(delta: Int, extendSelection: Boolean = false) {
        if (beginCursorMove(extendSelection)) {
            // Plain arrow with a selection collapses to the matching edge.
            val range = selectionRange()!!
            clearSelection()
            if (delta < 0) {
                cursorRow = range.startRow
                cursorCol = range.startCol
            } else {
                cursorRow = range.endRow
                cursorCol = range.endCol
            }
            ensureCursorVisible()
            onCursorChangedExternally?.invoke()
            return
        }
        val line = currentLine()
        val col = cursorCol.coerceAtMost(line.length)
        if (delta < 0) {
            if (col > 0) {
                cursorCol = line.offsetByCodePoints(col, -1)
            } else if (cursorRow > 0) {
                cursorRow -= 1
                cursorCol = currentLine().length
            }
        } else {
            if (col < line.length) {
                cursorCol = line.offsetByCodePoints(col, 1)
            } else if (cursorRow < lineCount - 1) {
                cursorRow += 1
                cursorCol = 0
            }
        }
        ensureCursorVisible()
        onCursorChangedExternally?.invoke()
    }

    fun moveCursorVertically(delta: Int, extendSelection: Boolean = false) {
        if (beginCursorMove(extendSelection)) clearSelection()
        cursorRow = (cursorRow + delta).coerceIn(0, lineCount - 1)
        cursorCol = cursorCol.coerceAtMost(currentLine().length)
        ensureCursorVisible()
        onCursorChangedExternally?.invoke()
    }

    /**
     * Prepare cursor movement w.r.t. selection: sets the anchor when
     * extending, clears it when not. Returns true if the caller should
     * instead collapse an existing selection (plain horizontal move).
     */
    private fun beginCursorMove(extendSelection: Boolean): Boolean {
        if (extendSelection) {
            if (selectionAnchorRow < 0) {
                selectionAnchorRow = cursorRow
                selectionAnchorCol = cursorCol
            }
            return false
        }
        if (hasSelection) return true
        clearSelection()
        return false
    }

    /** Scroll just enough to keep the cursor's line inside the viewport. */
    fun ensureCursorVisible() {
        if (viewportHeight <= 0f) return
        val top = cursorRow * lineHeightPx
        val bottom = top + lineHeightPx
        if (top < scrollY) {
            scrollY = top
        } else if (bottom > scrollY + viewportHeight) {
            scrollY = bottom - viewportHeight
        }
    }

    private fun afterHistoryChange() {
        refreshLineCount()
        cursorRow = cursorRow.coerceIn(0, lineCount - 1)
        cursorCol = cursorCol.coerceAtMost(currentLine().length)
        ensureCursorVisible()
        onCursorChangedExternally?.invoke()
    }

    private fun utf16Col(line: String, byteCol: Int): Int {
        val bytes = line.encodeToByteArray()
        return bytes.decodeToString(0, byteCol.coerceIn(0, bytes.size)).length
    }
}
