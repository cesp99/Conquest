package to.eyed.conquest.code.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
        val miss = version != cachedVersion || first < cachedFirst || last > cachedLast
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

    /**
     * Move the cursor to the position tapped at [tap] (pane-local pixels).
     * [layoutForLine] measures a line's text so the horizontal hit can land
     * between the right glyphs.
     */
    fun moveCursorTo(tap: Offset, layoutForLine: (String) -> TextLayoutResult) {
        val row = ((tap.y + scrollY) / lineHeightPx).toInt().coerceIn(0, lineCount - 1)
        val xInText = tap.x - gutterWidthPx - textPaddingPx + scrollX
        val layout = layoutForLine(line(row))
        cursorRow = row
        cursorCol = layout.getOffsetForPosition(Offset(xInText.coerceAtLeast(0f), 0f))
        onCursorChangedExternally?.invoke()
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

    /** Insert [text] at the cursor (hardware-key path). */
    fun insertAtCursor(text: String) {
        val line = currentLine()
        val col = cursorCol.coerceAtMost(line.length)
        applyLineDiff(cursorRow, line.take(col) + text + line.drop(col), col + text.length)
        onCursorChangedExternally?.invoke()
    }

    /** Delete the code point before the cursor, joining lines at column 0. */
    fun backspace() {
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

    fun moveCursorHorizontally(delta: Int) {
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

    fun moveCursorVertically(delta: Int) {
        cursorRow = (cursorRow + delta).coerceIn(0, lineCount - 1)
        cursorCol = cursorCol.coerceAtMost(currentLine().length)
        ensureCursorVisible()
        onCursorChangedExternally?.invoke()
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
