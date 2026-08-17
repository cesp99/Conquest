package to.eyed.conquest.code.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import to.eyed.conquest.code.core.BufferSession
import to.eyed.conquest.code.core.CoreBridge
import to.eyed.conquest.code.core.Utf8Diff

/** One highlighted range on one row; columns are UTF-16 offsets. */
data class HighlightSpan(val start: Int, val end: Int, val style: Int)

/**
 * One caret and, when its two ends differ, the selection it drags behind it.
 * Columns are UTF-16 offsets within their row, as everywhere else in the
 * view layer.
 *
 * The head is the end that moves; the anchor is the one that stays. A caret
 * whose head sits before its anchor is a backwards selection, and dragging
 * its start handle is what produces one.
 */
data class Caret(
    val anchorRow: Int,
    val anchorCol: Int,
    val headRow: Int,
    val headCol: Int,
) {
    constructor(row: Int, col: Int) : this(row, col, row, col)

    val isEmpty: Boolean get() = anchorRow == headRow && anchorCol == headCol

    private val anchorFirst: Boolean
        get() = anchorRow < headRow || (anchorRow == headRow && anchorCol <= headCol)

    val startRow: Int get() = if (anchorFirst) anchorRow else headRow
    val startCol: Int get() = if (anchorFirst) anchorCol else headCol
    val endRow: Int get() = if (anchorFirst) headRow else anchorRow
    val endCol: Int get() = if (anchorFirst) headCol else anchorCol
}

/** Document order by where a caret starts. */
internal val CaretOrder = compareBy<Caret>({ it.startRow }, { it.startCol })

/**
 * View state of one editor pane: scroll offsets, carets, and a cached
 * window of visible lines fetched from the engine via the line-window JNI
 * API. The pane never holds the whole buffer — only the lines on screen.
 *
 * Reactivity split: fields the draw pass must react to (scroll, cursor)
 * are snapshot state; geometry caches and the line window are plain fields
 * mutated from event handlers and reads, never triggering recomposition
 * themselves. Don't write snapshot state from the draw phase.
 *
 * The cursor column is a UTF-16 offset within its line (what Compose text
 * layout works in). Conversion to engine byte offsets happens at edit time,
 * not here.
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

    /**
     * The carets *other than* the primary one, in document order — empty for
     * the ordinary single-cursor case.
     *
     * Keeping the primary in the four fields above rather than as element
     * zero of one list is deliberate. The draw pass reads the primary's row
     * and column on every frame and the IME reads them after every
     * keystroke; both would otherwise pay for a list read and a boxed Int
     * where today they read an `IntState`. A pane with one cursor — which is
     * to say a pane almost all of the time — costs exactly what it did
     * before this list existed.
     */
    var extraCarets: List<Caret> by mutableStateOf(emptyList())
        private set

    /**
     * What the two repeatable multi-cursor commands need to remember between
     * presses: which way Shift+Alt+Arrow last grew the column of carets and
     * from which column, and whether Ctrl+D's query came from expanding a
     * word — in which case, as in Zed, it only matches whole words.
     *
     * Any other change to the caret set ends the run, which is why
     * [endCommandRun] is called from everything that moves a caret and the
     * two commands re-establish it themselves.
     */
    internal var addCaretDirection = 0
    internal var addCaretGoalCol = 0
    internal var selectNextWordwise = false

    internal fun endCommandRun() {
        addCaretDirection = 0
        addCaretGoalCol = 0
        selectNextWordwise = false
    }

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
     * clearing any selection and any extra carets. [layoutForLine] measures
     * a line's text so the horizontal hit can land between the right glyphs.
     */
    fun moveCursorTo(tap: Offset, layoutForLine: (String) -> TextLayoutResult) {
        val (row, col) = positionAt(tap, layoutForLine)
        clearSelection()
        dropExtraCarets()
        endCommandRun()
        cursorRow = row
        cursorCol = col
        onCursorChangedExternally?.invoke()
    }

    // ---- Carets ----------------------------------------------------------

    /** The caret the IME follows and the viewport scrolls to. */
    fun primaryCaret(): Caret = Caret(
        if (selectionAnchorRow < 0) cursorRow else selectionAnchorRow,
        if (selectionAnchorRow < 0) cursorCol else selectionAnchorCol,
        cursorRow,
        cursorCol,
    )

    /** Every caret, primary included, in document order. */
    fun caretsInOrder(): List<Caret> {
        val primary = primaryCaret()
        if (extraCarets.isEmpty()) return listOf(primary)
        val all = ArrayList<Caret>(extraCarets.size + 1)
        all.addAll(extraCarets)
        all.add(primary)
        all.sortWith(CaretOrder)
        return all
    }

    /**
     * Replace the whole caret set. [primary] is the one the IME and the
     * viewport follow and must be a member of [carets].
     *
     * Carets that touch after the change are merged, because two carets in
     * one place would apply everything typed from then on twice. Whichever
     * of a merged pair was the primary stays the primary.
     */
    internal fun setCarets(carets: List<Caret>, primary: Caret, notify: Boolean = true) {
        if (carets.isEmpty()) return
        endCommandRun()
        val ordered = carets.sortedWith(CaretOrder)
        val merged = ArrayList<Caret>(ordered.size)
        var primaryIndex = -1
        for (caret in ordered) {
            val isPrimary = primaryIndex < 0 && caret == primary
            val last = merged.lastOrNull()
            // Sorted by start, so an overlap can only be "this one starts at
            // or before the previous one ended".
            if (last != null && !isAfter(caret.startRow, caret.startCol, last.endRow, last.endCol)) {
                merged[merged.size - 1] = union(last, caret, keepOrientationOf = isPrimary)
                if (isPrimary || primaryIndex == merged.size - 1) primaryIndex = merged.size - 1
                continue
            }
            merged.add(caret)
            if (isPrimary) primaryIndex = merged.size - 1
        }
        val head = merged.getOrElse(if (primaryIndex >= 0) primaryIndex else merged.size - 1) {
            merged.last()
        }
        cursorRow = head.headRow
        cursorCol = head.headCol
        if (head.isEmpty) {
            clearSelection()
        } else {
            selectionAnchorRow = head.anchorRow
            selectionAnchorCol = head.anchorCol
        }
        extraCarets = if (merged.size == 1) emptyList() else merged.filter { it !== head }
        ensureCursorVisible()
        if (notify) onCursorChangedExternally?.invoke()
    }

    /** Drop every caret but the primary. Returns false if there was none. */
    fun dropExtraCarets(): Boolean {
        if (extraCarets.isEmpty()) return false
        extraCarets = emptyList()
        return true
    }

    /**
     * Escape, which is Zed's `editor::Cancel`: give up the extra carets
     * first, and only then the selection, so one press never throws away
     * more than the user asked it to.
     */
    fun cancel(): Boolean {
        if (dropExtraCarets()) {
            onCursorChangedExternally?.invoke()
            return true
        }
        if (!hasSelection) return false
        clearSelection()
        onCursorChangedExternally?.invoke()
        return true
    }

    /** Place an extra caret at a pane-local pixel position (Alt+click). */
    fun addCaretAt(point: Offset, layoutForLine: (String) -> TextLayoutResult) {
        val (row, col) = positionAt(point, layoutForLine)
        val added = Caret(row, col)
        setCarets(caretsInOrder() + added, added)
    }

    private fun isAfter(row: Int, col: Int, thanRow: Int, thanCol: Int): Boolean =
        row > thanRow || (row == thanRow && col > thanCol)

    /** The span covering both carets, oriented like whichever one matters. */
    private fun union(a: Caret, b: Caret, keepOrientationOf: Boolean): Caret {
        val bEndsLast = isAfter(b.endRow, b.endCol, a.endRow, a.endCol)
        val endRow = if (bEndsLast) b.endRow else a.endRow
        val endCol = if (bEndsLast) b.endCol else a.endCol
        val source = if (keepOrientationOf) b else a
        val reversed = !source.isEmpty &&
            (source.headRow < source.anchorRow ||
                (source.headRow == source.anchorRow && source.headCol < source.anchorCol))
        return if (reversed) {
            Caret(endRow, endCol, a.startRow, a.startCol)
        } else {
            Caret(a.startRow, a.startCol, endRow, endCol)
        }
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
        endCommandRun()
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
        dropExtraCarets()
        endCommandRun()
        val line = line(row)
        if (line.isEmpty()) {
            cursorRow = row
            cursorCol = 0
            clearSelection()
            onCursorChangedExternally?.invoke()
            return
        }
        val range = wordAround(line, col)
        selectionAnchorRow = row
        selectionAnchorCol = range.first
        cursorRow = row
        cursorCol = range.second
        onCursorChangedExternally?.invoke()
    }

    fun selectAll() {
        dropExtraCarets()
        endCommandRun()
        selectionAnchorRow = 0
        selectionAnchorCol = 0
        cursorRow = lineCount - 1
        cursorCol = currentLine().length
        onCursorChangedExternally?.invoke()
    }

    /**
     * The text of every caret's selection, joined by newlines the way Zed
     * puts a multi-cursor copy on the clipboard. Empty when nothing is
     * selected.
     */
    fun selectionText(): String {
        if (extraCarets.isEmpty()) return selectionRange()?.let(::textOf).orEmpty()
        return caretsInOrder()
            .filter { !it.isEmpty }
            .joinToString("\n") {
                textOf(SelectionRange(it.startRow, it.startCol, it.endRow, it.endCol))
            }
    }

    /** The text one caret has selected. */
    internal fun textIn(caret: Caret): String =
        textOf(SelectionRange(caret.startRow, caret.startCol, caret.endRow, caret.endCol))

    private fun textOf(range: SelectionRange): String {
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

    /** Delete every caret's selection; each caret lands at its start. */
    fun deleteSelection(): Boolean {
        val carets = caretsInOrder()
        if (carets.none { !it.isEmpty }) return false
        val primary = primaryCaret()
        applyCaretEdits(
            carets.map { caret ->
                CaretEdit(
                    start = byteOffsetOf(caret.startRow, caret.startCol),
                    end = byteOffsetOf(caret.endRow, caret.endCol),
                    replacement = "",
                    isPrimary = caret == primary,
                )
            },
            notify = false,
        )
        return true
    }

    /** Global byte offset of (row, UTF-16 col). */
    fun byteOffsetOf(row: Int, colUtf16: Int): Long {
        val line = line(row)
        return lineStartOffset(row) + utf8Length(line, colUtf16.coerceIn(0, line.length))
    }

    // ---- Editing ---------------------------------------------------------

    /**
     * Invoked when the cursor or buffer changes through anything except
     * [applyLineDiff] (taps, hardware keys, undo). The IME session listens
     * so it can re-seed its per-line shadow of the buffer.
     */
    var onCursorChangedExternally: (() -> Unit)? = null

    /**
     * The buffer's grammar name, read once. A buffer is assigned its
     * language when it is opened and never changes it, and the commands that
     * need it — comment toggling, bracket pairs — ask on every keystroke.
     */
    val language: String? by lazy { session.language }

    /**
     * Width of one indent level, in spaces, from the `tab_size` setting. A
     * plain field pushed in from composition: it changes only when the user
     * edits their settings, and the commands that read it — Enter's
     * auto-indent, Tab — have no business asking a snapshot for it on the
     * keystroke path.
     */
    var tabSize: Int = 4

    fun currentLine(): String = line(cursorRow)

    fun lineStartOffset(row: Int): Long =
        CoreBridge.pointToOffset(session.id, row.toLong(), 0)

    /**
     * One caret's share of a multi-caret edit. Offsets are absolute engine
     * byte offsets, all taken from the buffer as it stands *before* any of
     * the batch is applied — see [applyCaretEdits] for why that is safe.
     */
    internal class CaretEdit(
        val start: Long,
        val end: Long,
        val replacement: String,
        /**
         * Where the caret's head lands, as a byte offset from [start]. Null
         * means the end of [replacement], which is where typing leaves it.
         */
        val head: Int? = null,
        /** Where its anchor lands, same units. Null leaves a bare caret. */
        val anchor: Int? = null,
        /**
         * A UTF-16 column to prefer on the landing row, clamped to it. The
         * line operations use it to keep the caret's column across a row
         * change, the way Zed keeps its x position.
         */
        val columnGoal: Int? = null,
        val isPrimary: Boolean = false,
    )

    /**
     * Apply one edit per caret and put the carets where the edits left them.
     *
     * **Order matters, and it is deliberate.** Every offset in [edits] was
     * measured against the buffer as it is now, so applying one edit
     * invalidates the offsets of everything after it — but only of what is
     * after it, since an edit never moves the text in front of itself. Run
     * the batch from the end of the buffer backwards and that never bites:
     * each edit is applied at a position no earlier edit has disturbed, and
     * nothing has to be re-measured mid-flight.
     *
     * The carets are the exception, because each one does have to account
     * for the whole batch; they are resolved afterwards from the running sum
     * of the edits' length changes.
     *
     * The engine groups edits made inside its 300 ms history interval into a
     * single undo transaction, so a batch this tight — microseconds end to
     * end — undoes as one step. Saying so explicitly would be better than
     * relying on the timing, and wants a begin/end-transaction pair on the
     * bridge that does not exist yet.
     */
    internal fun applyCaretEdits(edits: List<CaretEdit>, notify: Boolean = true) {
        val kept = runEdits(edits)
        if (kept.isEmpty()) return
        var shift = 0L
        val carets = ArrayList<Caret>(kept.size)
        var primary: Caret? = null
        for (edit in kept) {
            val inserted = utf8Length(edit.replacement)
            val head = pointAt(edit.start + shift + (edit.head ?: inserted), edit.columnGoal)
            val caret = if (edit.anchor == null) {
                Caret(head.first, head.second)
            } else {
                val anchor = pointAt(edit.start + shift + edit.anchor, null)
                Caret(anchor.first, anchor.second, head.first, head.second)
            }
            shift += inserted - (edit.end - edit.start)
            carets.add(caret)
            if (edit.isPrimary) primary = caret
        }
        setCarets(carets, primary ?: carets.last(), notify)
    }

    /**
     * As [applyCaretEdits], for the operations that already know where the
     * carets belong afterwards. The line operations do: they permute or
     * copy whole rows, so a caret's new row is its old one plus a count of
     * lines, and asking the engine to convert offsets back would be work
     * spent on an answer we have.
     */
    internal fun applyEdits(edits: List<CaretEdit>, carets: List<Caret>, primary: Caret) {
        if (runEdits(edits).isEmpty()) return
        setCarets(carets, primary)
    }

    /**
     * Sort, drop the edits that collide, and apply what is left back to
     * front. Returns the edits that actually ran, in document order.
     */
    private fun runEdits(edits: List<CaretEdit>): List<CaretEdit> {
        if (edits.isEmpty()) return emptyList()
        val ordered = edits.sortedBy { it.start }
        // Two carets that reached the same bytes would corrupt each other's
        // ranges; the earlier edit wins and the later one is dropped.
        val kept = ArrayList<CaretEdit>(ordered.size)
        for (edit in ordered) {
            val last = kept.lastOrNull()
            if (last != null && edit.start < last.end) continue
            kept.add(edit)
        }
        for (i in kept.indices.reversed()) {
            val edit = kept[i]
            // A caret with nothing to do still travels with the batch so it
            // survives into the new caret set, but it must not reach the
            // engine: an empty edit would bump the version and litter the
            // undo history for no change at all.
            if (edit.start == edit.end && edit.replacement.isEmpty()) continue
            session.editBytes(edit.start, edit.end, edit.replacement)
        }
        refreshLineCount()
        return kept
    }

    /** (row, UTF-16 col) of a global byte offset, clipped to the buffer. */
    private fun pointAt(offset: Long, columnGoal: Int?): Pair<Int, Int> {
        val packed = CoreBridge.offsetToPoint(session.id, offset.coerceAtLeast(0))
        if (packed < 0) return cursorRow.coerceIn(0, lineCount - 1) to 0
        val row = (packed ushr 32).toInt().coerceIn(0, lineCount - 1)
        val text = line(row)
        val col = if (columnGoal != null) {
            columnGoal.coerceAtMost(text.length)
        } else {
            utf16Col(text, (packed and 0xFFFFFFFFL).toInt())
        }
        return row to col
    }

    /**
     * Replace the whole content of [row] with [newLine] (diffed down to a
     * minimal engine edit) and put the cursor at UTF-16 offset [selUtf16]
     * of the new content. This is the IME write path: the input connection
     * hands us its per-line shadow after every operation.
     *
     * Returns true if the caller must re-seed its shadow, which is the case
     * whenever the line structure changed under it or the edit was spread
     * across carets the shadow knows nothing about.
     */
    fun applyLineDiff(row: Int, newLine: String, selUtf16: Int): Boolean {
        val oldLine = line(row)
        if (oldLine == newLine) {
            cursorRow = row
            cursorCol = selUtf16.coerceIn(0, newLine.length)
            ensureCursorVisible()
            return false
        }
        // A soft keyboard commits characters through this path rather than as
        // key events, so anything that reacts to a *typed character* has to
        // be recognised here or it would only ever work with a hardware
        // keyboard. Only a lone character replacing the caret's own range
        // qualifies, which leaves composing text, autocorrect and swipe
        // typing on the plain diff path with their composing region intact.
        val typed = typedCharacter(row, oldLine, newLine, selUtf16)
        if (typed == "\n") {
            insertNewline()
            return true
        }
        if (typed != null && EditorLanguage.isPairCharacter(language, typed)) {
            typeCharacter(typed)
            return true
        }
        if (!hasSelection && cursorRow == row &&
            deletedOneCharacterBefore(oldLine, newLine, selUtf16) &&
            enclosingPairAt(oldLine, cursorCol) != null
        ) {
            backspace()
            return true
        }
        if (extraCarets.isNotEmpty()) return spreadLineDiff(row, oldLine, newLine, selUtf16)
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
                utf8Length(newLine, selUtf16.coerceIn(0, newLine.length))
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

    /**
     * The single character an IME operation put in place of the caret's own
     * range on [row], or null if it did anything else at all. With no
     * selection that range is the bare caret, so this recognises an ordinary
     * keypress; with one, it recognises the keypress that replaced it, which
     * is what auto-surround needs to see.
     */
    private fun typedCharacter(
        row: Int,
        oldLine: String,
        newLine: String,
        selUtf16: Int,
    ): String? {
        val range = selectionRange()
        val start: Int
        val end: Int
        if (range == null) {
            if (cursorRow != row) return null
            start = cursorCol
            end = cursorCol
        } else {
            if (range.isMultiLine || range.startRow != row) return null
            start = range.startCol
            end = range.endCol
        }
        if (start > end || end > oldLine.length) return null
        if (selUtf16 != start + 1 || newLine.length != oldLine.length - (end - start) + 1) {
            return null
        }
        if (!newLine.regionMatches(0, oldLine, 0, start)) return null
        if (!newLine.regionMatches(selUtf16, oldLine, end, oldLine.length - end)) return null
        return newLine.substring(start, selUtf16)
    }

    /** Whether the operation deleted exactly the character before the caret. */
    private fun deletedOneCharacterBefore(
        oldLine: String,
        newLine: String,
        selUtf16: Int,
    ): Boolean =
        newLine.length == oldLine.length - 1 &&
            cursorCol == selUtf16 + 1 &&
            selUtf16 >= 0 &&
            newLine.regionMatches(0, oldLine, 0, selUtf16) &&
            newLine.regionMatches(selUtf16, oldLine, selUtf16 + 1, oldLine.length - selUtf16 - 1)

    /**
     * The multi-caret form of [applyLineDiff]. The IME only ever sees the
     * primary caret's line, so its change is re-expressed relative to that
     * caret — "three bytes were replaced ending where the caret was" — and
     * then repeated at every other caret. A caret whose line is too short
     * for the range is left alone rather than clipped into an edit that
     * would eat the wrong characters.
     */
    private fun spreadLineDiff(row: Int, oldLine: String, newLine: String, selUtf16: Int): Boolean {
        val edit = Utf8Diff.diff(oldLine.encodeToByteArray(), newLine.encodeToByteArray())
            ?: return false
        val caretByte = utf8Length(oldLine, cursorCol.coerceIn(0, oldLine.length))
        val before = caretByte - edit.start
        val after = edit.end - caretByte
        val head = (utf8Length(newLine, selUtf16.coerceIn(0, newLine.length)) - edit.start)
            .coerceAtLeast(0)
        val primary = primaryCaret()
        val edits = ArrayList<CaretEdit>()
        for (caret in caretsInOrder()) {
            val isPrimary = caret == primary
            val text = if (isPrimary) oldLine else line(caret.headRow)
            val at = utf8Length(text, caret.headCol.coerceIn(0, text.length))
            val lineStart = lineStartOffset(caret.headRow)
            edits.add(
                if (at - before < 0 || at + after > utf8Length(text)) {
                    CaretEdit(lineStart + at, lineStart + at, "", isPrimary = isPrimary)
                } else {
                    CaretEdit(
                        start = lineStart + at - before,
                        end = lineStart + at - before + before + after,
                        replacement = edit.replacement,
                        head = head,
                        isPrimary = isPrimary,
                    )
                }
            )
        }
        applyCaretEdits(edits, notify = false)
        return true
    }

    /**
     * Insert [text] at every caret, replacing whatever each has selected.
     *
     * The single-caret case keeps going through [applyLineDiff]: it is the
     * per-keystroke path for a hardware keyboard, and routing it through the
     * batch machinery would spend an extra JNI round trip on every key to
     * ask the engine where a position it already knows ended up.
     */
    fun insertAtCursor(text: String) {
        if (extraCarets.isEmpty()) {
            deleteSelection()
            val line = currentLine()
            val col = cursorCol.coerceAtMost(line.length)
            applyLineDiff(cursorRow, line.take(col) + text + line.drop(col), col + text.length)
            onCursorChangedExternally?.invoke()
            return
        }
        val primary = primaryCaret()
        applyCaretEdits(
            caretsInOrder().map { caret ->
                CaretEdit(
                    start = byteOffsetOf(caret.startRow, caret.startCol),
                    end = byteOffsetOf(caret.endRow, caret.endCol),
                    replacement = text,
                    isPrimary = caret == primary,
                )
            }
        )
    }

    /**
     * Delete each caret's selection, or what is behind it: the two halves of
     * an empty bracket or quote pair together, otherwise the code point
     * before the caret, joining lines at column 0.
     */
    fun backspace() {
        if (deleteSelection()) {
            onCursorChangedExternally?.invoke()
            return
        }
        if (extraCarets.isEmpty()) {
            val text = currentLine()
            val col = cursorCol.coerceAtMost(text.length)
            val pair = enclosingPairAt(text, col)
            if (pair != null) {
                val from = col - pair.open.length
                applyLineDiff(cursorRow, text.take(from) + text.drop(col + pair.close.length), from)
            } else if (col > 0) {
                val previous = text.offsetByCodePoints(col, -1)
                applyLineDiff(cursorRow, text.take(previous) + text.drop(col), previous)
            } else {
                joinWithPreviousLine()
            }
            onCursorChangedExternally?.invoke()
            return
        }
        val primary = primaryCaret()
        val edits = ArrayList<CaretEdit>()
        for (caret in caretsInOrder()) {
            val text = line(caret.headRow)
            val col = caret.headCol.coerceAtMost(text.length)
            val pair = enclosingPairAt(text, col)
            val start: Long
            val end: Long
            if (pair != null) {
                start = byteOffsetOf(caret.headRow, col - pair.open.length)
                end = byteOffsetOf(caret.headRow, col + pair.close.length)
            } else if (col > 0) {
                val previous = text.offsetByCodePoints(col, -1)
                start = byteOffsetOf(caret.headRow, previous)
                end = byteOffsetOf(caret.headRow, col)
            } else if (caret.headRow > 0) {
                start = lineStartOffset(caret.headRow) - 1
                end = start + 1
            } else {
                // A caret at the very start of the buffer has nothing behind
                // it; it rides along so it survives the batch.
                start = 0
                end = 0
            }
            edits.add(CaretEdit(start, end, "", isPrimary = caret == primary))
        }
        applyCaretEdits(edits)
    }

    /** The pair [col] sits between, with nothing in it — `(|)`, `"|"`. */
    private fun enclosingPairAt(text: String, col: Int): BracketPair? =
        EditorLanguage.pairs(language).firstOrNull { pair ->
            col >= pair.open.length &&
                text.startsWith(pair.open, col - pair.open.length) &&
                text.startsWith(pair.close, col)
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

    // ---- Motion ----------------------------------------------------------

    fun moveCursorHorizontally(delta: Int, extendSelection: Boolean = false) {
        val primary = primaryCaret()
        setCarets(
            caretsInOrder().map { movedHorizontally(it, delta, extendSelection) },
            movedHorizontally(primary, delta, extendSelection),
        )
    }

    fun moveCursorVertically(delta: Int, extendSelection: Boolean = false) {
        val primary = primaryCaret()
        setCarets(
            caretsInOrder().map { movedVertically(it, delta, extendSelection) },
            movedVertically(primary, delta, extendSelection),
        )
    }

    private fun movedHorizontally(caret: Caret, delta: Int, extend: Boolean): Caret {
        // A plain arrow with a selection collapses to the matching edge
        // rather than moving on from it.
        if (!extend && !caret.isEmpty) {
            return if (delta < 0) {
                Caret(caret.startRow, caret.startCol)
            } else {
                Caret(caret.endRow, caret.endCol)
            }
        }
        val text = line(caret.headRow)
        val col = caret.headCol.coerceAtMost(text.length)
        var row = caret.headRow
        var moved = col
        if (delta < 0) {
            if (col > 0) {
                moved = text.offsetByCodePoints(col, -1)
            } else if (row > 0) {
                row -= 1
                moved = line(row).length
            }
        } else {
            if (col < text.length) {
                moved = text.offsetByCodePoints(col, 1)
            } else if (row < lineCount - 1) {
                row += 1
                moved = 0
            }
        }
        return if (extend) Caret(caret.anchorRow, caret.anchorCol, row, moved) else Caret(row, moved)
    }

    private fun movedVertically(caret: Caret, delta: Int, extend: Boolean): Caret {
        val row = (caret.headRow + delta).coerceIn(0, lineCount - 1)
        val col = caret.headCol.coerceAtMost(line(row).length)
        return if (extend) Caret(caret.anchorRow, caret.anchorCol, row, col) else Caret(row, col)
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
        // Undoing a multi-caret edit restores the text, not the carets that
        // made it; collapsing to one is what Zed's history does too.
        dropExtraCarets()
        cursorRow = cursorRow.coerceIn(0, lineCount - 1)
        cursorCol = cursorCol.coerceAtMost(currentLine().length)
        ensureCursorVisible()
        onCursorChangedExternally?.invoke()
    }

    // ---- UTF-8 / UTF-16 arithmetic ---------------------------------------

    /**
     * UTF-8 byte length of [text]'s first [endUtf16] UTF-16 units. Counted
     * rather than encoded: this runs once per caret on every edit, and
     * `encodeToByteArray` would allocate a copy of the line each time.
     */
    internal fun utf8Length(text: String, endUtf16: Int = text.length): Int {
        var bytes = 0
        var i = 0
        while (i < endUtf16) {
            val c = text[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < text.length &&
                    Character.isLowSurrogate(text[i + 1]) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return bytes
    }

    /** The inverse: how many UTF-16 units [byteCol] bytes of [line] span. */
    private fun utf16Col(line: String, byteCol: Int): Int {
        var bytes = 0
        var i = 0
        while (i < line.length && bytes < byteCol) {
            val c = line[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < line.length &&
                    Character.isLowSurrogate(line[i + 1]) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return i
    }
}

/**
 * The half-open UTF-16 range of the run of same-class characters around
 * [col] — a word, a stretch of whitespace, or a run of symbols. Shared by
 * double-tap selection and by Ctrl+D's "the word under the cursor".
 */
internal fun wordAround(line: String, col: Int): Pair<Int, Int> {
    if (line.isEmpty()) return 0 to 0
    fun charClass(c: Char): Int = when {
        c.isLetterOrDigit() || c == '_' -> 0
        c.isWhitespace() -> 1
        else -> 2
    }
    val at = col.coerceIn(0, line.length - 1)
    val target = charClass(line[at])
    var start = at
    while (start > 0 && charClass(line[start - 1]) == target) start--
    var end = at + 1
    while (end < line.length && charClass(line[end]) == target) end++
    return start to end
}
