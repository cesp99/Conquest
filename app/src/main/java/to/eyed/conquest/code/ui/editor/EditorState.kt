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
import to.eyed.conquest.code.core.Utf8Diff
import kotlin.math.ceil
import kotlin.math.min

/** One highlighted range on one row; columns are UTF-16 offsets. */
data class HighlightSpan(val start: Int, val end: Int, val style: Int)

/** One unbroken stretch of buffer rows, with the text and spans read for it. */
internal class RowRun(
    val first: Int,
    val lines: List<String>,
    val spans: List<List<HighlightSpan>>,
) {
    /** One past the last row this run holds. */
    val last: Int get() = first + lines.size

    /** Whether rows [from, to) are all in here. */
    fun covers(from: Int, to: Int): Boolean = from >= first && to <= last
}

/**
 * The text and highlight spans of the rows one frame draws, in the runs they
 * form: one run for an ordinary pane, one per unbroken stretch of visible
 * rows once something between them is folded.
 *
 * A fold is the whole reason this is not a list with a base row. The first
 * and the last row of a frame are a screenful apart until a block is folded
 * between them, and then they are as far apart as the block is long — so
 * reading "the rows between them" would put the entire folded span (its
 * text, its highlight query, one span list per row) on the UI thread of
 * every keystroke. The runs are what is actually on screen, and what this
 * costs is therefore the screen.
 */
internal class VisibleRows(private val runs: List<RowRun>) {

    /** The text of buffer row [row]; "" for a row this frame does not draw. */
    fun text(row: Int): String {
        val run = runOf(row) ?: return ""
        return run.lines.getOrElse(row - run.first) { "" }
    }

    /** Its highlight spans, in the same terms. */
    fun spans(row: Int): List<HighlightSpan> {
        val run = runOf(row) ?: return emptyList()
        return run.spans.getOrElse(row - run.first) { emptyList() }
    }

    // Scanned rather than searched: a frame has one run, or the handful the
    // folds on screen cut it into.
    private fun runOf(row: Int): RowRun? {
        for (run in runs) {
            if (row >= run.first && row < run.last) return run
        }
        return null
    }
}

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
 * What [EditorState.setCarets] does with a caret aimed at a row a fold has
 * hidden — the one question every caller that moves a caret has to answer,
 * and the reason it is asked here rather than at each of them.
 *
 * Zed never has to choose: a hidden buffer position keeps its place and only
 * the *display* point clips into the fold (crates/editor/src/display_map.rs).
 * Ours has a one-line IME shadow that can only follow a row on screen, so a
 * caret either takes its fold open with it or is moved out of it — and which
 * of the two is right depends entirely on what the user just did.
 */
internal enum class HiddenCaret {
    /**
     * Open the fold over the caret and leave it on the row that was asked
     * for. What a *jump* means — go-to-line, the outline picker, a search
     * hit, an edit that landed inside a block: the row asked for is the row
     * the next keystroke must type into, so the file opens to show it.
     */
    Reveal,

    /**
     * Move the caret out to the end of the fold's own line, keeping the fold
     * shut. Only right when the fold is what just changed: folding the block
     * the caret stands in is a command to hide those rows, and revealing
     * them again would undo it on the spot.
     */
    Snap,
}

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
class EditorState private constructor(
    private val buffer: EditorBuffer,
    private val boundSession: BufferSession?,
) {
    constructor(session: BufferSession) : this(SessionBuffer(session), session)

    /** An editor over a buffer that is not an engine one — see [EditorBuffer]. */
    internal constructor(buffer: EditorBuffer) : this(buffer, null)

    /**
     * The engine buffer behind this pane, for the workspace (saving, closing,
     * dirty state). Every editor the app builds has one; only the host-side
     * tests of the caret machinery do not.
     */
    val session: BufferSession
        get() = requireNotNull(boundSession) { "this editor has no engine buffer" }

    /**
     * The same buffer, for callers that have something else to do when there
     * isn't one — the git annotations, which a fake buffer has no id for.
     */
    internal val sessionOrNull: BufferSession? get() = boundSession

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

    /**
     * A `ctrl-k` waiting for its second half. Zed's Linux keymap spells
     * FoldAll and UnfoldAll as two-stroke chords (`ctrl-k ctrl-0`,
     * `ctrl-k ctrl-j` — assets/keymaps/default-linux.json:589-590), which a
     * single-event key handler can only honour by remembering the prefix.
     * A plain field: nothing draws it, and it lives exactly one keypress.
     */
    internal var pendingChordCtrlK = false

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
        val version = buffer.highlightVersion
        if (version == highlightVersion) return false
        highlightVersion = version
        return true
    }

    /** Not snapshot state: refreshed from the engine in [refreshLineCount]. */
    var lineCount: Int = buffer.lineCount
        private set

    /**
     * Bumped whenever this editor changes the buffer. Snapshot state, unlike
     * the engine's own version counter, which is a plain field on
     * `BufferSession` and therefore invisible to composition — anything that
     * has to *react* to an edit (the search bar's highlights, for one) needs
     * something Compose can see.
     */
    var revision: Int by mutableIntStateOf(0)
        private set

    private fun bumpRevision() {
        revision++
    }

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

    /** The visible height, for anything drawing against the viewport. */
    internal val viewportHeightPx: Float get() = viewportHeight

    /** Rows that fit on screen, which is what a page motion moves by. */
    internal fun viewportRows(): Int =
        if (lineHeightPx <= 0f) 1 else (viewportHeight / lineHeightPx).toInt().coerceAtLeast(1)

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

    // The runs of visible rows a fold cuts off below the window above — see
    // [visibleRows] and [foldRun]. Empty in a pane with nothing folded on
    // screen, which is every pane most of the time.
    private var extraRuns: List<RowRun> = emptyList()
    private var extraRunsVersion = -1L
    private var extraRunsHighlightVersion = -1L

    /**
     * What is on screen, as opposed to what is in the file. Every place that
     * used to multiply a row by the line height goes through it — see
     * [DisplayMap], which also explains what it costs.
     *
     * It is handed the *cached* line count rather than the buffer's, so a
     * query never crosses the bridge, and a reader that serves the window
     * already fetched for drawing wherever it covers the rows asked for.
     */
    internal val displayMap = DisplayMap({ lineCount }, ::textOfRows)

    /** Refilled by the draw pass every frame; see [DisplayWindow]. */
    internal val displayWindow = DisplayWindow()

    // ---- Folds -----------------------------------------------------------

    /**
     * Every fold this pane holds, chip rows and all, sorted by start row —
     * nested folds included, the way Zed's fold map keeps overlapping
     * creases so unfolding an outer block reveals the inner ones still
     * folded (crates/editor/src/display_map/fold_map.rs:715-727).
     *
     * Snapshot state because the gutter's chevrons and the "⋯" chips are
     * drawn from it every frame. It lives here, on the pane's view state,
     * for the same reason the scroll position does: an [EditorState] is kept
     * per open file, so folds survive a tab switch and die with the tab —
     * exactly the lifetime Zed's per-editor fold map has.
     */
    internal var folds: List<FoldRange> by mutableStateOf(emptyList())
        private set

    /** The fold whose chip sits on [row], or null. */
    internal fun foldStartingAt(row: Int): FoldRange? {
        // Sorted by start row; the draw pass asks once per visible row per
        // frame, so this is a binary search rather than a scan.
        var low = 0
        var high = folds.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (folds[mid].startRow < row) low = mid + 1 else high = mid
        }
        return folds.getOrNull(low)?.takeIf { it.startRow == row }
    }

    /** Whether [row] is inside a fold and off the screen. */
    internal fun isRowFoldedAway(row: Int): Boolean = displayMap.isRowHidden(row)

    /** Install a new fold list: sort it, hand the hidden union to the map. */
    private fun installFolds(newFolds: List<FoldRange>) {
        val sorted = newFolds.sortedWith(compareBy({ it.startRow }, { it.endRow }))
        folds = sorted
        displayMap.setFoldedRows(mergedHiddenRows(sorted))
        bumpRevision()
    }

    /**
     * The union of the folds' interiors as disjoint ranges that never touch,
     * which is the only shape [DisplayMap.setFoldedRows] accepts — its
     * neighbouring-row arithmetic depends on it.
     */
    private fun mergedHiddenRows(sorted: List<FoldRange>): List<IntRange> {
        if (sorted.isEmpty()) return emptyList()
        val merged = ArrayList<IntRange>()
        for (fold in sorted) {
            val first = fold.startRow + 1
            if (first > fold.endRow) continue
            val last = merged.lastOrNull()
            if (last != null && first <= last.last + 1) {
                if (fold.endRow > last.last) merged[merged.size - 1] = last.first..fold.endRow
            } else {
                merged.add(first..fold.endRow)
            }
        }
        return merged
    }

    /**
     * Fold [ranges], drop the carets that just vanished onto their chip
     * rows, and keep the primary on screen — Zed's `fold_creases` with its
     * autoscroll (crates/editor/src/fold.rs:578-600).
     */
    internal fun foldRanges(ranges: List<FoldRange>) {
        val added = (folds + ranges.filter { it.endRow > it.startRow }).distinct()
        if (added.size == folds.size) return
        installFolds(added)
        // The caret set re-enters through its one door. This is the one
        // caller that asks for [HiddenCaret.Snap]: the user just folded the
        // block the caret was standing in, so opening it again to keep the
        // caret where it was would undo the command they gave — see
        // [snappedOutOfFold].
        setCarets(caretsInOrder(), primaryCaret(), hidden = HiddenCaret.Snap)
        ensureCursorVisible()
    }

    /**
     * Remove the folds that intersect [rows] — Zed's `unfold_intersecting`
     * with `inclusive: true` (fold_map.rs, via fold.rs:603-614). With
     * [hiddenOnly] the chip row does not count as an intersection, which is
     * what revealing a search hit wants: landing *on* a chip row should not
     * open the fold under it.
     */
    internal fun unfoldRowsTouching(rows: IntRange, hiddenOnly: Boolean = false): Boolean {
        if (folds.isEmpty()) return false
        val kept = folds.filterNot { fold ->
            val from = if (hiddenOnly) fold.startRow + 1 else fold.startRow
            rows.first <= fold.endRow && rows.last >= from
        }
        if (kept.size == folds.size) return false
        installFolds(kept)
        ensureCursorVisible()
        return true
    }

    /** Zed's `editor::UnfoldAll` (crates/editor/src/fold.rs:520-534). */
    internal fun unfoldAllRows(): Boolean {
        if (folds.isEmpty()) return false
        installFolds(emptyList())
        ensureCursorVisible()
        return true
    }

    /**
     * Reconcile the folds with an edit that touched rows [fromRow, toRow]
     * (measured before it ran) and changed the row count by [rowDelta].
     * Returns true when anything changed, in which case every measured
     * height in the display map is suspect.
     *
     * Zed carries folds through edits as buffer anchors and only drops the
     * ones whose range collapses to nothing (fold_map.rs:562,
     * `if fold_range.end > fold_range.start`). Row numbers cannot ride an
     * edit the way an anchor can, so this does the honest flat version: an
     * edit into a fold's hidden rows unfolds it, a structural edit on any of
     * its rows unfolds it, folds past the edit shift by the row delta, and
     * folds in front of it stand. Typing on the chip row itself leaves the
     * fold alone — the hidden rows were not touched — which is also what
     * Zed's anchors arrive at.
     */
    private fun adjustFoldsForEdit(fromRow: Int, toRow: Int, rowDelta: Int): Boolean {
        if (folds.isEmpty()) return false
        var changed = false
        val kept = ArrayList<FoldRange>(folds.size)
        for (fold in folds) {
            val touchesHidden = fromRow <= fold.endRow && toRow >= fold.startRow + 1
            val touchesAtAll = fromRow <= fold.endRow && toRow >= fold.startRow
            when {
                touchesHidden || (rowDelta != 0 && touchesAtAll) -> changed = true
                rowDelta != 0 && fold.startRow > toRow -> {
                    kept.add(FoldRange(fold.startRow + rowDelta, fold.endRow + rowDelta))
                    changed = true
                }
                else -> kept.add(fold)
            }
        }
        if (changed) installFolds(kept)
        return changed
    }

    /**
     * An empty caret standing on a folded-away row, moved to the end of the
     * fold's own line — where Zed *draws* a caret whose buffer position is
     * inside a fold (a hidden point clips to the fold's start). Zed keeps
     * the underlying position; a one-line IME shadow cannot follow a row
     * that is not on screen, so ours moves for real. Selection endpoints are
     * left where they are: the selection spans the fold and paints across
     * it, and any edit through it unfolds first.
     *
     * Moving the caret is only ever right when the *fold* is what changed —
     * see [HiddenCaret], and [revealFoldsOver] for what happens the rest
     * of the time.
     */
    private fun snappedOutOfFold(caret: Caret): Caret {
        if (!displayMap.hasFolds || !caret.isEmpty) return caret
        if (!displayMap.isRowHidden(caret.headRow)) return caret
        val row = displayMap.prevVisibleRow(caret.headRow)
        return Caret(row, line(row).length)
    }

    /**
     * Open the folds hiding the rows [carets] are being put on, so that the
     * caret can stay on the row the caller asked for.
     *
     * The chip row itself does not count (`hiddenOnly`): landing *on* a
     * fold's own line is landing somewhere already visible, and opening the
     * block under it would be a jump the user did not ask for — the same
     * rule [selectRange] follows for a search hit. Only empty carets are
     * revealed, because only they are otherwise moved.
     *
     * Per caret rather than over the span between the first and the last:
     * two carets in two different folds must not open every fold between
     * them.
     */
    private fun revealFoldsOver(carets: List<Caret>, primary: Caret) {
        for (caret in carets) {
            if (!caret.isEmpty || !displayMap.isRowHidden(caret.headRow)) continue
            unfoldRowsTouching(caret.headRow..caret.headRow, hiddenOnly = true)
        }
        // The primary is normally a member of [carets]; a caller that hands
        // over one it built separately still gets its fold opened.
        if (primary.isEmpty && displayMap.isRowHidden(primary.headRow)) {
            unfoldRowsTouching(primary.headRow..primary.headRow, hiddenOnly = true)
        }
    }

    private companion object {
        const val WINDOW_PADDING = 32

        /**
         * Hidden rows not worth cutting a frame's read in two for. Under
         * this many, reading the folded rows and throwing them away costs
         * less than a second bridge round trip and a second highlight query
         * — the same trade [WINDOW_PADDING] makes for the rows either side
         * of the viewport — so a screenful of small folds stays one read.
         */
        const val FOLD_GAP_ROWS = 16

        /**
         * Narrowest pane we will still wrap in. A pane thinner than this is
         * a layout accident (a pane mid-animation, a hinge fold), and wrapping
         * every character onto its own row would be worse than overflowing.
         */
        const val MIN_WRAP_COLUMNS = 8

        /** Rows of the file read to decide whether it indents with tabs. */
        const val INDENT_SAMPLE_ROWS = 200

        /** Zed's `gutter.min_line_number_digits`. */
        const val MIN_GUTTER_DIGITS = 4

        /** Zed's gutter margins: 3 character widths left, 4 right. */
        const val GUTTER_PADDING_CHARS = 7

        /**
         * How many times [ensureCursorVisible] will re-measure before it
         * settles for what it has. Two is the most a jump has ever needed;
         * the bound is there so a pathological file cannot turn a caret move
         * into a walk of the document.
         */
        const val MAX_SETTLE_TURNS = 4
    }

    /**
     * Zed's rule, not a dp guess: `max(digits, 4)` characters wide, with the
     * padding measured in character widths too — three before the number and
     * four after (crates/editor/src/editor.rs:11712-11770, and the `min 4`
     * comes from `gutter.min_line_number_digits` in default.json:697). Padding
     * in dp would drift away from the numbers as the buffer font changed.
     */
    val gutterWidthPx: Float
        get() = (lineCount.toString().length.coerceAtLeast(MIN_GUTTER_DIGITS) +
            GUTTER_PADDING_CHARS) * charWidthPx

    /**
     * The gutter's fold column: Zed's `right_padding` is `em_width * 4` when
     * folds and line numbers are both on (editor.rs:11758-11760) — the line
     * numbers end where it begins, and the fold chevron lives in it. It is
     * the right-hand share of the [GUTTER_PADDING_CHARS] the gutter already
     * reserves, not extra width.
     */
    val gutterFoldColumnPx: Float get() = 4 * charWidthPx

    /** Zed's `px(2.)` for the cursor, in device pixels. Set from composition. */
    var cursorWidthPx: Float = 2f
        private set

    fun updateMetrics(
        lineHeight: Float,
        charWidth: Float,
        gutterPadding: Float,
        textPadding: Float,
        cursorWidth: Float = cursorWidthPx,
    ) {
        cursorWidthPx = cursorWidth
        lineHeightPx = lineHeight
        charWidthPx = charWidth
        gutterPaddingPx = gutterPadding
        textPaddingPx = textPadding
        syncDisplayMap()
    }

    fun updateViewport(width: Float, height: Float) {
        viewportWidth = width
        viewportHeight = height
        syncDisplayMap()
    }

    /**
     * Zed's `soft_wrap`. A plain field pushed in from composition like
     * [tabSize]: it changes when the user changes a setting, never on the
     * keystroke path, and the draw pass reads it through the composition that
     * set it.
     */
    var softWrap: SoftWrapMode = SoftWrapMode.None
        set(value) {
            if (field == value) return
            field = value
            syncDisplayMap()
        }

    /**
     * Horizontal scroll as the renderer should use it: a wrapped pane has
     * nothing to scroll sideways, and a stale [scrollX] left over from before
     * wrapping was turned on would shift every row off its gutter.
     *
     * Read rather than clamped, because the only place that would notice is
     * the draw pass and the draw pass must not write snapshot state.
     */
    internal val effectiveScrollX: Float get() = if (softWrap.wraps) 0f else scrollX

    /**
     * Tell the display map how wide a row may be, in characters.
     *
     * The text area is what is left of the pane once the gutter, the text's
     * own left padding and the scrollbar's track are taken off — which is
     * what `soft_wrap: "editor_width"` means. Called from both the metrics
     * and the viewport update because either can change it; the map itself
     * ignores a width it already has, so the per-frame call costs a compare.
     */
    private fun syncDisplayMap() {
        val columns = if (!softWrap.wraps || charWidthPx <= 0f || viewportWidth <= 0f) {
            0
        } else {
            val track = charWidthPx.coerceIn(10f, 24f)
            ((viewportWidth - gutterWidthPx - textPaddingPx - track) / charWidthPx)
                .toInt()
                .coerceAtLeast(MIN_WRAP_COLUMNS)
        }
        // Called from every frame's `updateViewport`, so nothing below may
        // run for a width the map already has.
        if (columns == displayMap.wrapColumns && tabSize == displayMap.tabSize) return
        // A new width throws away every measurement, so the display row
        // [scrollY] names now means a different row of the file — and left
        // alone it can sit past the whole document, which draws an empty
        // window and therefore an empty pane. Re-anchor on the buffer row
        // that was at the top: a fold, a rotation, a DeX resize or a font
        // change should keep the reader where they were reading.
        val topRow = if (scrollY > 0f && lineHeightPx > 0f) {
            displayMap.bufferRowOf((scrollY / lineHeightPx).toInt())
        } else {
            -1
        }
        displayMap.configure(columns, tabSize)
        if (topRow >= 0) scrollY = displayMap.displayRowOf(topRow) * lineHeightPx
        scrollY = scrollY.coerceIn(0f, maxScrollY)
    }

    /**
     * The lines of rows [first, last) — cached, re-fetched over JNI only
     * when the window or the buffer version changed.
     */
    fun linesWindow(first: Int, last: Int): List<String> {
        val miss = buffer.version != cachedVersion ||
            highlightVersion != cachedHighlightVersion ||
            first < cachedFirst ||
            last > cachedLast
        if (miss) fetchWindow(first, last)
        requestedFirst = first.coerceIn(cachedFirst, cachedLast)
        requestedLast = last.coerceIn(requestedFirst, cachedLast)
        return cachedLines.subList(requestedFirst - cachedFirst, requestedLast - cachedFirst)
    }

    /** Read rows [first, last) — padded — into the window cache. */
    private fun fetchWindow(first: Int, last: Int) {
        val paddedFirst = (first - WINDOW_PADDING).coerceAtLeast(0)
        val paddedLast = (last + WINDOW_PADDING).coerceAtMost(lineCount)
        if (paddedLast > paddedFirst) {
            cachedLines = buffer.lines(paddedFirst, paddedLast).split('\n')
            cachedSpans = groupSpans(
                buffer.highlights(paddedFirst, paddedLast),
                paddedFirst,
                cachedLines.size,
            )
        } else {
            cachedLines = emptyList()
            cachedSpans = emptyList()
        }
        cachedFirst = paddedFirst
        cachedLast = paddedFirst + cachedLines.size
        cachedVersion = buffer.version
        cachedHighlightVersion = highlightVersion
        // The window moved under whoever asked last; keep the spans they are
        // about to read inside it.
        requestedFirst = requestedFirst.coerceIn(cachedFirst, cachedLast)
        requestedLast = requestedLast.coerceIn(requestedFirst, cachedLast)
    }

    /**
     * Text and highlight spans for the rows the draw pass will actually
     * draw between [firstRow] and [lastRow], inclusive.
     *
     * The two rows are a screenful apart in an unfolded pane and this is
     * [linesWindow] with one more object around it. With a block folded
     * between them they are as far apart as the block is long, and the
     * difference is the whole point: the rows are read in the runs the folds
     * cut them into, so a frame costs the rows on screen rather than the
     * rows the file happens to have between the top of the viewport and the
     * bottom of it. See [VisibleRows].
     */
    internal fun visibleRows(firstRow: Int, lastRow: Int): VisibleRows {
        val end = (lineCount - 1).coerceAtLeast(0)
        val last = lastRow.coerceIn(0, end)
        val first = firstRow.coerceIn(0, last)
        val runs = ArrayList<RowRun>(1)
        var row = first
        while (row <= last) {
            var runLast = row
            while (true) {
                val next = displayMap.nextVisibleRow(runLast + 1)
                if (next > last || next - runLast - 1 > FOLD_GAP_ROWS) break
                runLast = next
            }
            if (runs.isEmpty()) {
                // The first run is the pane's own padded window, which is
                // the cache `line`, `spansFor` and the display map's
                // measuring all read through. Nothing about the frame's
                // first rows has changed since before folds existed.
                val lines = linesWindow(row, runLast + 1)
                runs.add(RowRun(requestedFirst, lines, spansWindow()))
            } else {
                runs.add(foldRun(row, runLast + 1))
            }
            row = displayMap.nextVisibleRow(runLast + 1)
        }
        extraRuns = if (runs.size > 1) ArrayList(runs.subList(1, runs.size)) else emptyList()
        return VisibleRows(runs)
    }

    /**
     * A run of rows past the first one, from the small cache the frames
     * below a fold share.
     *
     * Kept between frames because a caret blink is a frame too: without it a
     * folded file would re-read every run twice a second. Thrown away whole
     * when the buffer or its highlights move, which costs at most a
     * viewport's worth of rows to rebuild.
     */
    private fun foldRun(first: Int, last: Int): RowRun {
        if (buffer.version != extraRunsVersion || highlightVersion != extraRunsHighlightVersion) {
            extraRuns = emptyList()
            extraRunsVersion = buffer.version
            extraRunsHighlightVersion = highlightVersion
        }
        extraRuns.firstOrNull { it.covers(first, last) }?.let { return it }
        val lines = buffer.lines(first, last).split('\n')
        return RowRun(first, lines, groupSpans(buffer.highlights(first, last), first, lines.size))
    }

    /**
     * Highlight spans for the window last returned by [linesWindow],
     * parallel to its returned lines.
     */
    fun spansWindow(): List<List<HighlightSpan>> =
        cachedSpans.subList(requestedFirst - cachedFirst, requestedLast - cachedFirst)

    fun spansFor(row: Int): List<HighlightSpan> {
        if (row in cachedFirst until cachedLast && cachedVersion == buffer.version) {
            return cachedSpans.getOrElse(row - cachedFirst) { emptyList() }
        }
        if (highlightVersion != extraRunsHighlightVersion) return emptyList()
        val run = rowFromRuns(row) ?: return emptyList()
        return run.spans.getOrElse(row - run.first) { emptyList() }
    }

    /**
     * The run holding [row] out of the ones a fold cut off below the window,
     * or null. Worth trying before the bridge — a frame holds one or two of
     * them — and it is what keeps the row *under* a fold (the caret's line,
     * a chip's own line) from costing a read on every frame.
     */
    private fun rowFromRuns(row: Int): RowRun? {
        if (extraRuns.isEmpty() || buffer.version != extraRunsVersion) return null
        for (run in extraRuns) {
            if (row >= run.first && row < run.last) return run
        }
        return null
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

    fun line(row: Int): String {
        if (row in cachedFirst until cachedLast && cachedVersion == buffer.version) {
            return cachedLines[row - cachedFirst]
        }
        val run = rowFromRuns(row)
        return if (run != null) run.lines[row - run.first] else buffer.lines(row, row + 1)
    }

    /** Rows [firstRow, lastRow) straight from the buffer, past the window. */
    internal fun linesOf(firstRow: Int, lastRow: Int): String = buffer.lines(firstRow, lastRow)

    /**
     * Text of buffer rows [first, last) for the display map.
     *
     * Served from the window already fetched for drawing wherever it covers
     * them: measuring a block of a wrapped file must not turn into a bridge
     * call on every frame, and the block the viewport sits in is the one the
     * window is holding.
     */
    private fun textOfRows(first: Int, last: Int): List<String> {
        val from = first.coerceIn(0, lineCount)
        val to = last.coerceIn(from, lineCount)
        if (to == from) return emptyList()
        if (buffer.version == cachedVersion && from >= cachedFirst && to <= cachedLast) {
            return cachedLines.subList(from - cachedFirst, to - cachedFirst)
        }
        // An edit drops the window, and the block the map has to re-measure
        // afterwards is the one the caret is in — the very rows the draw pass
        // is about to ask for again. Reading them as a window rather than as
        // a bare block turns two bridge round trips per keystroke into one.
        // A block nowhere near the window is a jump or a drag into a far part
        // of the file: that one is read on its own, so a query into a
        // 100k-line file still costs a block and not a window around it.
        if (from < cachedLast && to > cachedFirst) {
            fetchWindow(from, to)
            if (from >= cachedFirst && to <= cachedLast) {
                return cachedLines.subList(from - cachedFirst, to - cachedFirst)
            }
        }
        return buffer.lines(from, to).split('\n')
    }

    /**
     * Call after anything that may change the buffer contents from outside an
     * edit this class made — a reload, a history step. Everything the display
     * map measured is suspect.
     */
    fun refreshLineCount() {
        lineCount = buffer.lineCount
        // A reload or a history step moved text under every fold's row
        // numbers, and there are no anchors here to carry them — Zed's folds
        // ride an undo on theirs (fold_map anchors); ours honestly cannot,
        // so the folds open rather than hide the wrong rows.
        if (folds.isNotEmpty()) installFolds(emptyList())
        displayMap.invalidateAll()
        bumpRevision()
    }

    /**
     * The same, for an edit whose reach is known: only the rows it rewrote
     * lose their measured wrapping, so typing in a 100k-line file re-measures
     * one block rather than the file. Everything after goes too when the row
     * count changed, because every row after an inserted or deleted line has
     * moved to a different index.
     */
    private fun refreshLineCount(fromRow: Int, toRow: Int) {
        val rowDelta = buffer.lineCount - lineCount
        lineCount = buffer.lineCount
        // Folds first: [adjustFoldsForEdit] resets the whole map when it
        // changes anything, and rows an unfold just revealed can be far in
        // front of the edit — a narrow invalidate would leave their blocks
        // measured at zero.
        if (!adjustFoldsForEdit(fromRow, toRow, rowDelta)) {
            displayMap.invalidate(fromRow - 1, toRow + 1)
        }
        bumpRevision()
    }

    /**
     * Ranges the search bar wants painted, and which of them is current.
     *
     * Held here rather than in the bar because only the canvas can draw over
     * the text, and only this class knows where a row is. Rows are 0-based and
     * columns are UTF-16, like everything else the renderer works in.
     */
    var searchMatches: List<SelectionRange> by mutableStateOf(emptyList())
        private set
    var activeMatch: Int by mutableIntStateOf(-1)
        private set

    fun showSearchMatches(matches: List<SelectionRange>, active: Int) {
        searchMatches = matches
        activeMatch = active
    }

    fun clearSearchMatches() {
        if (searchMatches.isEmpty() && activeMatch < 0) return
        searchMatches = emptyList()
        activeMatch = -1
    }

    /**
     * Put the caret on [range] and bring it into view — what following a
     * search hit means. The selection is the hit, so the next thing typed
     * replaces it, which is what every editor does here.
     */
    fun selectRange(range: SelectionRange) {
        // A hit inside a fold has to be revealed before it can be shown —
        // the same unfold the "⋯" chip runs, aimed by the navigation instead
        // of the pointer. Chip rows themselves don't count: a hit on the
        // fold's own line is already visible.
        unfoldRowsTouching(range.startRow..range.endRow, hiddenOnly = true)
        setCarets(
            listOf(Caret(range.startRow, range.startCol, range.endRow, range.endCol)),
            Caret(range.startRow, range.startCol, range.endRow, range.endCol),
        )
    }

    /** Track line widths seen during drawing to bound horizontal scroll. */
    fun noteContentWidth(width: Float) {
        if (width > contentWidthPx) contentWidthPx = width
    }

    /**
     * Consume a vertical scrollable delta (positive = finger moving down,
     * which scrolls the content up). Returns the consumed amount.
     */
    /** The largest [scrollY] the content allows — in display rows, not file rows. */
    internal val maxScrollY: Float
        get() = (displayMap.displayRowCount * lineHeightPx - viewportHeight).coerceAtLeast(0f)

    /** Put the viewport at [y], clamped — what dragging the scrollbar does. */
    internal fun scrollToY(y: Float) {
        scrollY = y.coerceIn(0f, maxScrollY)
    }

    /**
     * The first display row the draw pass paints.
     *
     * Clamped inside the document, not merely at zero: [scrollY] can name a
     * row past the end — a wrap width thrown away between the scroll and the
     * frame, a document that shrank — and a first row past the end leaves the
     * window empty, which is a pane with nothing in it at all. The clamp only
     * bites in that case, because an honest [scrollY] is at most [maxScrollY].
     */
    internal fun firstDisplayRow(): Int {
        val last = (displayMap.displayRowCount - 1).coerceAtLeast(0)
        return (scrollY / lineHeightPx).toInt().coerceIn(0, last)
    }

    /**
     * One past the last display row the draw pass paints, given [first] from
     * [firstDisplayRow]. Always at least one row: something is always on
     * screen while there is a document to draw.
     */
    internal fun lastDisplayRow(first: Int): Int {
        val fits = ceil(viewportHeight / lineHeightPx).toInt() + 1
        return min(first + fits, displayMap.displayRowCount).coerceAtLeast(first + 1)
    }

    fun applyScrollDeltaY(delta: Float): Float {
        val maxY = maxScrollY
        val new = (scrollY - delta).coerceIn(0f, maxY)
        val consumed = scrollY - new
        scrollY = new
        return consumed
    }

    /** Horizontal counterpart of [applyScrollDeltaY]. */
    fun applyScrollDeltaX(delta: Float): Float {
        // Nothing overflows a wrapped pane, so nothing is consumed and the
        // gesture goes back to whatever is behind the editor.
        if (softWrap.wraps) return 0f
        val contentAreaWidth = (viewportWidth - gutterWidthPx).coerceAtLeast(0f)
        val maxX = (contentWidthPx + textPaddingPx + charWidthPx - contentAreaWidth)
            .coerceAtLeast(0f)
        val new = (scrollX - delta).coerceIn(0f, maxX)
        val consumed = scrollX - new
        scrollX = new
        return consumed
    }

    /**
     * (row, UTF-16 col) at a pane-local pixel position.
     *
     * The vertical hit lands on a *display* row, so with wrapping on the tap
     * is resolved against the segment under the finger — its own text, its
     * own left edge — and the column is clamped to that segment. A tap past
     * the end of a wrapped segment therefore lands at the segment's end
     * rather than at the end of the whole row.
     */
    fun positionAt(point: Offset, layoutForLine: (String) -> TextLayoutResult): Pair<Int, Int> {
        val display = ((point.y + scrollY) / lineHeightPx).toInt().coerceAtLeast(0)
        val row = displayMap.bufferRowOf(display)
        val text = line(row)
        val wrap = displayMap.wrapOf(text)
        val segment = (display - displayMap.displayRowOf(row))
            .coerceIn(0, wrap.segmentCount - 1)
        val start = wrap.startOf(segment)
        val end = wrap.endOf(segment, text.length)
        val indentPx = if (segment > 0) wrap.indentColumns * charWidthPx else 0f
        val xInText = point.x - gutterWidthPx - textPaddingPx - indentPx + effectiveScrollX
        val layout = layoutForLine(segmentText(text, start, end))
        val col = start + layout.getOffsetForPosition(Offset(xInText.coerceAtLeast(0f), 0f))
        return row to col.coerceIn(start, end)
    }

    /**
     * One segment's text. An unwrapped row hands back the row itself rather
     * than a copy of it, which keeps the layout cache's key — and therefore
     * every measurement it holds — exactly what it was before wrapping
     * existed.
     */
    internal fun segmentText(text: String, start: Int, end: Int): String {
        if (start <= 0 && end >= text.length) return text
        val from = start.coerceIn(0, text.length)
        return text.substring(from, end.coerceIn(from, text.length))
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
     *
     * [hidden] says what to do about a caret aimed at a row a fold has
     * hidden; the default is the one every caller but [foldRanges] wants.
     */
    internal fun setCarets(
        carets: List<Caret>,
        primary: Caret,
        notify: Boolean = true,
        hidden: HiddenCaret = HiddenCaret.Reveal,
    ) {
        if (carets.isEmpty()) return
        endCommandRun()
        // Through the one door every caret change takes: a caret can never
        // settle on a row a fold has hidden. Revealing comes first so that
        // the snap below finds nothing left to move — it stays as the last
        // resort for the fold that was just created over a caret.
        if (displayMap.hasFolds && hidden == HiddenCaret.Reveal) {
            revealFoldsOver(carets, primary)
        }
        val visible = if (displayMap.hasFolds) carets.map(::snappedOutOfFold) else carets
        val visiblePrimary = snappedOutOfFold(primary)
        val ordered = visible.sortedWith(CaretOrder)
        val merged = ArrayList<Caret>(ordered.size)
        var primaryIndex = -1
        for (caret in ordered) {
            val isPrimary = primaryIndex < 0 && caret == visiblePrimary
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

    /**
     * Move the primary caret and leave the others where they are.
     *
     * Everything that moves the primary on its own goes through here rather
     * than writing the four fields, because a primary that lands *on* an
     * extra caret is two carets in one place, and two carets in one place
     * apply everything typed from then on twice. With extras in play the
     * move goes through [setCarets], which is where coincident carets merge;
     * with none there is nothing to collide with, and the fields are written
     * directly so an ordinary tap or IME selection report costs what it did.
     */
    private fun setPrimaryCaret(moved: Caret, notify: Boolean = true) {
        if (extraCarets.isEmpty()) {
            cursorRow = moved.headRow
            cursorCol = moved.headCol
            if (!moved.isEmpty) {
                selectionAnchorRow = moved.anchorRow
                selectionAnchorCol = moved.anchorCol
            }
            ensureCursorVisible()
            if (notify) onCursorChangedExternally?.invoke()
            return
        }
        val primary = primaryCaret()
        setCarets(caretsInOrder().map { if (it == primary) moved else it }, moved, notify)
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
     * Collapse every caret's selection to its head — what a copy leaves
     * behind. [clearSelection] only ever reaches the primary, so after a
     * multi-caret copy the other selections would stay highlighted with
     * nothing selecting them.
     */
    fun collapseSelections() {
        if (extraCarets.isEmpty()) {
            clearSelection()
            return
        }
        val primary = primaryCaret()
        var head: Caret? = null
        val carets = caretsInOrder().map { caret ->
            Caret(caret.headRow, caret.headCol).also { if (caret == primary) head = it }
        }
        setCarets(carets, head ?: carets.last())
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
        val anchor = if (movingStart) {
            range.endRow to range.endCol
        } else {
            range.startRow to range.startCol
        }
        setPrimaryCaret(Caret(anchor.first, anchor.second, row, col))
    }

    /** Extend (or start) a selection from the current cursor to [point]. */
    fun extendSelectionTo(point: Offset, layoutForLine: (String) -> TextLayoutResult) {
        endCommandRun()
        val anchorRow = if (selectionAnchorRow < 0) cursorRow else selectionAnchorRow
        val anchorCol = if (selectionAnchorRow < 0) cursorCol else selectionAnchorCol
        val (row, col) = positionAt(point, layoutForLine)
        setPrimaryCaret(Caret(anchorRow, anchorCol, row, col))
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
        val lines = buffer.lines(range.startRow, range.endRow + 1).split('\n')
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
    val language: String? by lazy { buffer.language }

    /**
     * The language's editing rules, straight from the grammar's own
     * `config.toml` by way of the engine. Read once per pane; the parsed
     * config itself is shared between every pane of the same language.
     */
    internal val languageConfig: LanguageConfig by lazy {
        EditorLanguage.configFor(language) { buffer.languageConfigJson }
    }

    /**
     * For each of [offsets], a bitmask of the pairs of [languageConfig]'s
     * `brackets` that are live there — the `not_in` question, which needs the
     * syntax tree the engine holds and this side does not.
     *
     * One call for every caret at once, and only when a pair character has
     * actually been typed: it can cost the engine a reparse.
     */
    internal fun enabledPairsAt(offsets: LongArray): LongArray = buffer.bracketScopes(offsets)

    /**
     * Width of one indent level, in spaces, from the `tab_size` setting. A
     * plain field pushed in from composition: it changes only when the user
     * edits their settings, and the commands that read it — Enter's
     * auto-indent, Tab — have no business asking a snapshot for it on the
     * keystroke path.
     */
    var tabSize: Int = 4
        set(value) {
            if (field == value) return
            field = value
            // A tab is this many columns wide to the wrapper as well, so
            // every break in the file has just moved.
            syncDisplayMap()
        }

    /**
     * Whether this file indents with tabs, asked once of the file itself.
     *
     * The row an operation happens on is not evidence enough on its own: at
     * column zero its indent is empty, and a tab-indented file would then
     * get spaces for every top-level block it opens — mixed indentation, one
     * level down. So the sample is the head of the file, and only when it
     * has nothing to say does the language answer (Zed's Go config sets
     * `hard_tabs`; nothing else we carry does).
     *
     * Computed once: a file does not change how it is indented, and this is
     * on the Enter and Tab path.
     */
    private val usesHardTabs: Boolean by lazy {
        var tabs = 0
        var spaces = 0
        for (text in buffer.lines(0, minOf(lineCount, INDENT_SAMPLE_ROWS)).split('\n')) {
            when {
                text.startsWith('\t') -> tabs++
                // Two, because one leading space is as likely to be the
                // continuation of a block comment as an indent level.
                text.startsWith("  ") -> spaces++
            }
        }
        if (tabs == 0 && spaces == 0) languageConfig.hardTabs else tabs > spaces
    }

    /**
     * One indent level's worth of text. [lineIndent] is the indent of the
     * row being worked on, which wins where it exists so that a line already
     * indented one way keeps going that way; the file decides the rest.
     */
    internal fun indentUnit(lineIndent: String = ""): String = when {
        lineIndent.startsWith('\t') -> "\t"
        lineIndent.isNotEmpty() -> " ".repeat(tabSize)
        usesHardTabs -> "\t"
        else -> " ".repeat(tabSize)
    }

    fun currentLine(): String = line(cursorRow)

    fun lineStartOffset(row: Int): Long = buffer.rowStart(row)

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
        // How far the batch can reach, for the display map. Every range in it
        // was derived from a caret, and the furthest an operation ever goes
        // past one is the newline on either side — deleting a line takes the
        // break in front of it, moving one takes the row beyond it — which is
        // the ±1 [refreshLineCount] adds. Measured before the edits run,
        // while the carets still name the rows the offsets came from.
        var touchedFrom = Int.MAX_VALUE
        var touchedTo = 0
        for (caret in caretsInOrder()) {
            if (caret.startRow < touchedFrom) touchedFrom = caret.startRow
            if (caret.endRow > touchedTo) touchedTo = caret.endRow
        }
        val ordered = edits.sortedBy { it.start }
        // Two carets that reached the same bytes would corrupt each other's
        // ranges; the earlier edit wins and the later one is dropped.
        val kept = ArrayList<CaretEdit>(ordered.size)
        for (edit in ordered) {
            val last = kept.lastOrNull()
            if (last != null && collides(last, edit)) continue
            kept.add(edit)
        }
        var refused: MutableList<CaretEdit>? = null
        for (i in kept.indices.reversed()) {
            val edit = kept[i]
            // A caret with nothing to do still travels with the batch so it
            // survives into the new caret set, but it must not reach the
            // engine: an empty edit would bump the version and litter the
            // undo history for no change at all.
            if (edit.start == edit.end && edit.replacement.isEmpty()) continue
            if (!buffer.edit(edit.start, edit.end, edit.replacement)) {
                // The engine refused this range, so the buffer is exactly as
                // it was. Letting the edit stay in the batch would count its
                // length against every caret after it and put all of them at
                // offsets the buffer never had.
                (refused ?: ArrayList<CaretEdit>(1).also { refused = it }).add(edit)
            }
        }
        refreshLineCount(touchedFrom, touchedTo)
        val rejected = refused ?: return kept
        return kept.filterNot { edit -> rejected.any { it === edit } }
    }

    /** Whether [edit] reaches bytes [last] has already claimed. */
    private fun collides(last: CaretEdit, edit: CaretEdit): Boolean =
        edit.start < last.end ||
            // Two zero-width inserts at one offset are two carets standing in
            // one place: run both and the text goes in twice.
            (edit.start == last.end && edit.start == edit.end && last.start == last.end)

    /** (row, UTF-16 col) of a global byte offset, clipped to the buffer. */
    private fun pointAt(offset: Long, columnGoal: Int?): Pair<Int, Int> {
        val packed = buffer.pointOf(offset.coerceAtLeast(0))
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
            // Only the caret moved (a tap on the IME's cursor control, a
            // composing region set). It still goes through the one door: on
            // a soft keyboard this is the way the primary reaches the column
            // an extra caret is already standing in.
            setPrimaryCaret(Caret(row, selUtf16.coerceIn(0, newLine.length)), notify = false)
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
        if (typed != null && languageConfig.isPairCharacter(typed)) {
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
            buffer.edit(lineStart + edit.start, lineStart + edit.end, edit.replacement)
        }
        val structural = '\n' in newLine
        if (structural) {
            val cursorOffset = lineStart +
                utf8Length(newLine, selUtf16.coerceIn(0, newLine.length))
            val packed = buffer.pointOf(cursorOffset)
            val newRow = (packed ushr 32).toInt()
            val byteCol = (packed and 0xFFFFFFFFL).toInt()
            cursorRow = newRow
            cursorCol = utf16Col(line(newRow), byteCol)
        } else {
            cursorRow = row
            cursorCol = selUtf16.coerceIn(0, newLine.length)
        }
        refreshLineCount(row, row)
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
     * caret — "one character either side of where the caret was" — and then
     * repeated at every other caret. A caret whose line is too short for the
     * range is left alone rather than clipped into an edit that would eat
     * the wrong characters.
     *
     * The range is counted in *code points*, not in the bytes the diff comes
     * in. The other carets sit on other text: replaying "two bytes back from
     * here" on a line whose character before the caret happens to be one
     * byte wide deletes two characters where the user asked for one, and a
     * range that lands mid-character is one the engine refuses outright.
     */
    private fun spreadLineDiff(row: Int, oldLine: String, newLine: String, selUtf16: Int): Boolean {
        val edit = Utf8Diff.diff(oldLine.encodeToByteArray(), newLine.encodeToByteArray())
            ?: return false
        val caretCol = cursorCol.coerceIn(0, oldLine.length)
        val before = codePointsBetween(oldLine, utf16Col(oldLine, edit.start), caretCol)
        val after = codePointsBetween(oldLine, caretCol, utf16Col(oldLine, edit.end))
        val head = (utf8Length(newLine, selUtf16.coerceIn(0, newLine.length)) - edit.start)
            .coerceAtLeast(0)
        val primary = primaryCaret()
        val edits = ArrayList<CaretEdit>()
        for (caret in caretsInOrder()) {
            val isPrimary = caret == primary
            val text = if (isPrimary) oldLine else line(caret.headRow)
            val col = caret.headCol.coerceIn(0, text.length)
            val lineStart = lineStartOffset(caret.headRow)
            val from = codePointOffset(text, col, -before)
            val to = codePointOffset(text, col, after)
            edits.add(
                if (from == null || to == null) {
                    val at = lineStart + utf8Length(text, col)
                    CaretEdit(at, at, "", isPrimary = isPrimary)
                } else {
                    CaretEdit(
                        start = lineStart + utf8Length(text, from),
                        end = lineStart + utf8Length(text, to),
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
        if (extraCarets.isEmpty()) {
            if (deleteSelection()) {
                onCursorChangedExternally?.invoke()
                return
            }
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
            val pair = if (caret.isEmpty) enclosingPairAt(text, col) else null
            val start: Long
            val end: Long
            if (!caret.isEmpty) {
                // Every caret answers for itself: the ones with a selection
                // lose it, the bare ones lose what is behind them, and it is
                // one press either way. Deleting only the selections would
                // leave the bare carets watching.
                start = byteOffsetOf(caret.startRow, caret.startCol)
                end = byteOffsetOf(caret.endRow, caret.endCol)
            } else if (pair != null) {
                start = byteOffsetOf(caret.headRow, col - pair.open.length)
                end = byteOffsetOf(caret.headRow, col + pair.close.length)
            } else if (col > 0) {
                val previous = text.offsetByCodePoints(col, -1)
                start = byteOffsetOf(caret.headRow, previous)
                end = byteOffsetOf(caret.headRow, col)
            } else if (caret.headRow > 0) {
                // Joining upward eats the newline of the row above — which,
                // beside a fold, is the fold's own last hidden row. The
                // batch's touched-row window is measured from the carets and
                // cannot see that reach, so the fold is opened here instead
                // of sliding over rearranged text.
                unfoldRowsTouching(caret.headRow - 1..caret.headRow, hiddenOnly = true)
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

    /**
     * The pair [col] sits between, with nothing in it — `(|)`, `"|"`.
     *
     * Only pairs we would have closed ourselves count: Rust's `<`/`>` never
     * auto-closes, so the `>` in `Vec<|>` is one the user typed and deleting
     * it with the `<` would be deleting something they did not ask about.
     */
    private fun enclosingPairAt(text: String, col: Int): BracketPair? =
        languageConfig.brackets.firstOrNull { pair ->
            pair.autoClose &&
                col >= pair.open.length &&
                text.startsWith(pair.open, col - pair.open.length) &&
                text.startsWith(pair.close, col)
        }

    /**
     * Remove the newline before the cursor's line. No-op on row 0.
     *
     * Private on purpose: it moves the primary caret and nothing else, so
     * reaching it from outside — the IME's backspace at column zero used
     * to — leaves every other caret naming a row that has moved under it.
     * [backspace] is the door.
     */
    private fun joinWithPreviousLine(): Boolean {
        if (cursorRow == 0) return false
        val previousLine = line(cursorRow - 1)
        val lineStart = lineStartOffset(cursorRow)
        buffer.edit(lineStart - 1, lineStart, "")
        refreshLineCount(cursorRow - 1, cursorRow)
        cursorRow -= 1
        cursorCol = previousLine.length
        ensureCursorVisible()
        return true
    }

    fun undo() {
        if (buffer.undo()) afterHistoryChange()
    }

    fun redo() {
        if (buffer.redo()) afterHistoryChange()
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
                // The neighbouring *visible* row: stepping over a fold's
                // edge lands beside it, never inside it — Zed's motion runs
                // in display coordinates and cannot express a hidden row.
                row = displayMap.prevVisibleRow(row - 1)
                moved = line(row).length
            }
        } else {
            if (col < text.length) {
                moved = text.offsetByCodePoints(col, 1)
            } else if (row < lineCount - 1) {
                val next = displayMap.nextVisibleRow(row + 1)
                if (next < lineCount) {
                    row = next
                    moved = 0
                }
            }
        }
        return if (extend) Caret(caret.anchorRow, caret.anchorCol, row, moved) else Caret(row, moved)
    }

    private fun movedVertically(caret: Caret, delta: Int, extend: Boolean): Caret {
        if (displayMap.isIdentity) {
            val row = (caret.headRow + delta).coerceIn(0, lineCount - 1)
            val col = caret.headCol.coerceAtMost(line(row).length)
            return if (extend) Caret(caret.anchorRow, caret.anchorCol, row, col) else Caret(row, col)
        }
        // With wrapping on, up and down move by a row of the *screen*: one
        // press on a paragraph that wraps into six should step down one of
        // them, not skip the whole paragraph. Zed's MoveUp/MoveDown walk
        // display rows for the same reason.
        val display = displayRowOf(caret.headRow, caret.headCol) + delta
        val (row, col) = pointAtDisplayRow(
            display,
            columnWithinSegment(caret.headRow, caret.headCol),
        )
        return if (extend) Caret(caret.anchorRow, caret.anchorCol, row, col) else Caret(row, col)
    }

    /**
     * The display row that draws a caret position.
     *
     * Column zero needs no text at all, and asking for a row's text is a
     * bridge call whenever the edit that moved the caret has just invalidated
     * the line window — which is exactly when this is called.
     */
    internal fun displayRowOf(row: Int, col: Int): Int = when {
        displayMap.isIdentity -> row.coerceIn(0, lineCount - 1)
        col <= 0 -> displayMap.displayRowOf(row)
        // The row's start first, then its text: measuring the row's block
        // refills the line window the edit just dropped, so the text below is
        // served from that read instead of a second one.
        else -> displayMap.displayRowOf(row) + displayMap.wrapOf(line(row)).segmentOf(col)
    }

    /** How far into its own display row a caret sits, in UTF-16 units. */
    internal fun columnWithinSegment(row: Int, col: Int): Int {
        if (displayMap.isIdentity) return col
        val wrap = displayMap.wrapOf(line(row))
        return col - wrap.startOf(wrap.segmentOf(col))
    }

    /**
     * The (row, UTF-16 col) [offset] units into display row [display]'s
     * segment, clamped to that segment.
     */
    internal fun pointAtDisplayRow(display: Int, offset: Int): Pair<Int, Int> {
        // Clamped by the map, not against `displayRowCount`: that count is an
        // estimate until the block is measured, and clamping to it would
        // answer for a row short of the one asked about.
        val at = display.coerceAtLeast(0)
        val row = displayMap.bufferRowOf(at)
        val text = line(row)
        if (displayMap.isIdentity) return row to offset.coerceIn(0, text.length)
        val wrap = displayMap.wrapOf(text)
        val segment = (at - displayMap.displayRowOf(row)).coerceIn(0, wrap.segmentCount - 1)
        val start = wrap.startOf(segment)
        val end = wrap.endOf(segment, text.length)
        return row to (start + offset).coerceIn(start, end)
    }

    /**
     * Scroll just enough to keep the cursor's display row inside the
     * viewport.
     *
     * Settled against what the next draw will measure rather than against
     * what happens to be measured now. A caret that jumps — a search hit, a
     * goto-line — is placed against blocks estimated at one display row per
     * file row, and the first thing the draw pass does is measure the block
     * the top of the viewport lands in, which makes it taller and pushes the
     * caret's display row down with it. Measuring here first is what stops
     * the pane scrolling to a row the caret is no longer on. Each turn
     * measures at least one more block and the caret's row only ever moves
     * down, so this settles in a turn or two.
     */
    fun ensureCursorVisible() {
        if (viewportHeight <= 0f) return
        var turns = 0
        while (true) {
            val display = displayRowOf(cursorRow, cursorCol)
            val top = display * lineHeightPx
            val bottom = top + lineHeightPx
            if (top < scrollY) {
                scrollY = top
            } else if (bottom > scrollY + viewportHeight) {
                scrollY = bottom - viewportHeight
            }
            if (displayMap.isIdentity) break
            val first = (scrollY / lineHeightPx).toInt().coerceAtLeast(0)
            displayMap.measureWindow(first, first + viewportRows() + 2)
            if (displayRowOf(cursorRow, cursorCol) == display) break
            if (++turns >= MAX_SETTLE_TURNS) break
        }
        scrollY = scrollY.coerceIn(0f, maxScrollY)
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
    internal fun utf16Col(line: String, byteCol: Int): Int {
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
 * The UTF-16 offset [count] code points from [col], or null if that runs off
 * either end of [text]. Negative counts move backwards.
 *
 * Null rather than a clamp: a count that does not fit means the line is not
 * long enough for the operation being replayed on it, and clipping it into
 * range would silently eat characters nobody pointed at.
 */
internal fun codePointOffset(text: String, col: Int, count: Int): Int? {
    if (col < 0 || col > text.length) return null
    if (count >= 0) {
        if (text.codePointCount(col, text.length) < count) return null
    } else if (text.codePointCount(0, col) < -count) {
        return null
    }
    return text.offsetByCodePoints(col, count)
}

/** Code points from [from] to [to], negative when [to] is the earlier one. */
internal fun codePointsBetween(text: String, from: Int, to: Int): Int =
    if (from <= to) text.codePointCount(from, to) else -text.codePointCount(to, from)

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
