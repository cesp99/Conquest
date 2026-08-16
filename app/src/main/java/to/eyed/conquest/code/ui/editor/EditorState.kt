package to.eyed.conquest.code.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import to.eyed.conquest.code.core.BufferSession
import to.eyed.conquest.code.core.CoreBridge

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

    private var cachedLines: List<String> = emptyList()
    private var cachedFirst = -1
    private var cachedLast = -1
    private var cachedVersion = -1L

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
        if (first != cachedFirst || last != cachedLast || version != cachedVersion) {
            cachedLines = if (last > first) {
                CoreBridge.bufferLines(session.id, first.toLong(), last.toLong())
                    .orEmpty()
                    .split('\n')
            } else {
                emptyList()
            }
            cachedFirst = first
            cachedLast = last
            cachedVersion = version
        }
        return cachedLines
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
    }
}
