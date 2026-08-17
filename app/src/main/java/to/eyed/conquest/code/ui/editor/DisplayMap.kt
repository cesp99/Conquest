package to.eyed.conquest.code.ui.editor

import kotlin.math.min

/**
 * Zed's `soft_wrap`, cut down to the two modes a pane can honour.
 *
 * `bounded` and `preferred_line_length` belong to the wrap-guide feature and
 * are deliberately not here; `prefer_line` is Zed's own deprecated spelling
 * of `none` (assets/settings/default.json:1530-1536).
 */
enum class SoftWrapMode(val key: String) {
    /** Zed's default — a long line runs off the right edge and scrolls. */
    None("none"),

    /** Wrap at the width of the text area. */
    EditorWidth("editor_width");

    val wraps: Boolean get() = this == EditorWidth

    companion object {
        fun fromKey(key: String?): SoftWrapMode = entries.firstOrNull { it.key == key } ?: None
    }
}

/**
 * Where one buffer row's soft-wrapped segments begin and end, in UTF-16
 * columns.
 *
 * A row that fits is [FITS] — one segment, no breaks, shared by every such
 * row — which is what most rows of most files are and the case this is
 * shaped for.
 */
internal class WrappedLine(
    private val breaks: IntArray,
    /**
     * Columns every segment after the first is pushed right by: the row's own
     * indent, so a wrapped line still reads as one paragraph. Zed carries the
     * same number out of its wrapper (gpui/src/text_system/line_wrapper.rs:110).
     */
    val indentColumns: Int,
) {
    val segmentCount: Int get() = breaks.size + 1

    val wraps: Boolean get() = breaks.isNotEmpty()

    fun startOf(segment: Int): Int = if (segment <= 0) 0 else breaks[segment - 1]

    fun endOf(segment: Int, length: Int): Int =
        if (segment >= breaks.size) length else breaks[segment]

    /**
     * The segment UTF-16 column [col] is drawn in. A column that is exactly a
     * break belongs to the segment it *starts* — the caret after the last
     * character of a wrapped segment sits at the head of the next one, which
     * is where every editor puts it.
     */
    fun segmentOf(col: Int): Int {
        var low = 0
        var high = breaks.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (breaks[mid] <= col) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        val FITS = WrappedLine(IntArray(0), 0)
    }
}

/**
 * Where a line breaks when it is wrapped to a width, in columns.
 *
 * A port of Zed's `LineWrapper::wrap_line`
 * (crates/gpui/src/text_system/line_wrapper.rs:39) with pixels replaced by
 * columns. The buffer font is monospace and every other measurement in this
 * renderer already assumes so — the gutter's width, the indent guides' step —
 * and the substitution buys two things worth more than per-glyph accuracy:
 * the scan is pure arithmetic, so counting the display rows of a block of the
 * file costs no text measurement at all, and the whole of it is testable on
 * the host, where no `TextMeasurer` exists.
 *
 * What it costs: a row of double-width glyphs (CJK, emoji) is counted one
 * column per code point and overhangs the right edge rather than breaking
 * early. The breaks stay consistent between counting and drawing, which is
 * what keeps the map honest about its own geometry.
 */
internal object SoftWrap {

    /** Zed's cap on how far a continuation may be pushed right. */
    private const val MAX_INDENT = 256

    /**
     * Columns of [text]'s leading whitespace, which is what its continuations
     * are indented by. A tab counts as [tabSize] columns, the same as it does
     * for the indent guides.
     *
     * A row that is nothing but whitespace has none: there is no text on it
     * for a continuation to line up under.
     */
    fun indentColumns(text: String, tabSize: Int): Int {
        var columns = 0
        for (char in text) {
            when (char) {
                '\t' -> columns += tabSize
                ' ' -> columns++
                else -> return min(columns, MAX_INDENT)
            }
        }
        return 0
    }

    /**
     * Number of display rows [text] occupies at [wrapColumns], appending each
     * break's UTF-16 offset to [breaks] when one is given.
     *
     * Passing no list is the counting path — it walks the same characters and
     * allocates nothing, which is what makes measuring a block of the file
     * cheap enough to do while drawing.
     */
    fun wrap(
        text: String,
        wrapColumns: Int,
        tabSize: Int,
        indentColumns: Int,
        breaks: MutableList<Int>? = null,
    ): Int {
        if (wrapColumns <= 0 || text.isEmpty()) return 1
        // A continuation indent as wide as the wrap width would leave no room
        // for the text it is indenting; half the width is where an indent
        // stops helping and starts being the problem.
        val indent = indentColumns.coerceIn(0, wrapColumns / 2)
        var segments = 1
        var width = 0
        var seenText = false
        var lastCandidate = 0
        var lastCandidateWidth = 0
        var lastWrap = 0
        var previous = ' '
        var i = 0
        while (i < text.length) {
            val at = i
            val char = text[i]
            val step = if (
                Character.isHighSurrogate(char) && i + 1 < text.length &&
                Character.isLowSurrogate(text[i + 1])
            ) 2 else 1
            // Where the line would rather break: in front of a word that
            // follows a space, or in front of anything that is not a word
            // character at all — which is how CJK, written without spaces,
            // breaks at all (line_wrapper.rs:66-80).
            if (seenText) {
                if (isWordChar(char)) {
                    if (previous == ' ' && char != ' ') {
                        lastCandidate = at
                        lastCandidateWidth = width
                    }
                } else if (char != ' ') {
                    lastCandidate = at
                    lastCandidateWidth = width
                }
            }
            if (!seenText && !char.isWhitespace()) seenText = true
            val charWidth = if (char == '\t') tabSize else 1
            width += charWidth
            // `at > lastWrap` guarantees the scan advances: a single character
            // wider than the whole width still gets a row to itself instead of
            // breaking forever in front of itself.
            if (width > wrapColumns && at > lastWrap) {
                if (lastCandidate > lastWrap) {
                    lastWrap = lastCandidate
                    width -= lastCandidateWidth
                    lastCandidate = 0
                } else {
                    lastWrap = at
                    width = charWidth
                }
                width += indent
                breaks?.add(lastWrap)
                segments++
            }
            previous = char
            i += step
        }
        return segments
    }

    /** [text]'s segments at this width — the shared [WrappedLine.FITS] if it fits. */
    fun of(text: String, wrapColumns: Int, tabSize: Int): WrappedLine {
        if (wrapColumns <= 0 || text.isEmpty()) return WrappedLine.FITS
        val indent = indentColumns(text, tabSize)
        val breaks = ArrayList<Int>(4)
        wrap(text, wrapColumns, tabSize, indent, breaks)
        if (breaks.isEmpty()) return WrappedLine.FITS
        return WrappedLine(breaks.toIntArray(), indent.coerceIn(0, wrapColumns / 2))
    }

    /**
     * Zed's `is_word_char` (line_wrapper.rs:450): the characters that must
     * stay glued to what is beside them. The script ranges are Zed's own, and
     * the point of listing them rather than asking [Char.isLetterOrDigit] is
     * that CJK must fall *outside* the set — a language written without
     * spaces can only break between its characters.
     */
    private fun isWordChar(char: Char): Boolean = when (char) {
        in 'a'..'z', in 'A'..'Z', in '0'..'9' -> true
        // Latin-1 Supplement, Latin Extended-A and -B, combining marks.
        in '\u00C0'..'\u00FF', in '\u0100'..'\u017F' -> true
        in '\u0180'..'\u024F', in '\u0300'..'\u036F' -> true
        // Cyrillic, Bengali, Latin Extended Additional (Vietnamese).
        in '\u0400'..'\u04FF', in '\u0980'..'\u09FF' -> true
        in '\u1E00'..'\u1EFF' -> true
        // `a-b`, `var_name`, `won't`, `@mention`, `3.1415`, and the trailing
        // punctuation that should stay on the word in front of it.
        '-', '_', '.', '\'', '\u2019', '\u2018', '$', '%', '@', '#' -> true
        '^', '~', ',', '=', ':', ';', '\u22EF' -> true
        // Non-breaking glue: NNBSP, NBSP, non-breaking hyphen.
        '\u202F', '\u00A0', '\u2011' -> true
        else -> false
    }
}

/**
 * One frame's worth of display rows, as parallel arrays reused between
 * frames.
 *
 * Parallel `IntArray`s rather than a list of objects on purpose: this is
 * filled on every draw, and fifty small objects a frame is fifty small
 * objects a frame. Nothing here allocates once the arrays are big enough.
 */
internal class DisplayWindow {
    var firstDisplayRow: Int = 0
        private set
    var size: Int = 0
        private set

    private var rows = IntArray(0)
    private var segments = IntArray(0)
    private var starts = IntArray(0)
    private var ends = IntArray(0)
    private var indents = IntArray(0)

    /** The buffer row display row `firstDisplayRow + i` comes from. */
    fun bufferRow(i: Int): Int = rows[i]

    /** Which segment of that row; 0 is the one the line number sits on. */
    fun segment(i: Int): Int = segments[i]

    fun startCol(i: Int): Int = starts[i]

    /** Exclusive end, or [Int.MAX_VALUE] for "to the end of the row". */
    fun endCol(i: Int): Int = ends[i]

    /** Columns this segment is pushed right by; 0 on a row's first segment. */
    fun indentColumns(i: Int): Int = indents[i]

    /** True when this display row carries the row's line number. */
    fun isFirstSegment(i: Int): Boolean = segments[i] == 0

    fun firstBufferRow(): Int = if (size == 0) 0 else rows[0]

    fun lastBufferRow(): Int = if (size == 0) -1 else rows[size - 1]

    /**
     * The window entry that draws (row, col), or -1.
     *
     * Scanned rather than indexed: a caret is asked about a handful of times
     * a frame and the window is a few dozen rows long. Callers reject rows
     * outside [firstBufferRow]..[lastBufferRow] first, which is what keeps a
     * thousand carets off this path.
     */
    fun indexOf(row: Int, col: Int): Int {
        for (i in 0 until size) {
            if (rows[i] == row && col >= starts[i] && col < ends[i]) return i
        }
        return -1
    }

    /**
     * The first entry drawing buffer row [row], or -1. Binary searched
     * because the search bar asks it once per match per frame and a file can
     * have a thousand matches on screen's worth of rows.
     */
    fun firstIndexOf(row: Int): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (rows[mid] < row) low = mid + 1 else high = mid
        }
        return if (low < size && rows[low] == row) low else -1
    }

    internal fun reset(firstDisplayRow: Int, capacity: Int) {
        this.firstDisplayRow = firstDisplayRow
        size = 0
        if (rows.size >= capacity) return
        rows = IntArray(capacity)
        segments = IntArray(capacity)
        starts = IntArray(capacity)
        ends = IntArray(capacity)
        indents = IntArray(capacity)
    }

    internal fun add(row: Int, segment: Int, start: Int, end: Int, indent: Int) {
        if (size >= rows.size) return
        rows[size] = row
        segments[size] = segment
        starts[size] = start
        ends[size] = end
        indents[size] = indent
        size++
    }
}

/**
 * What is on screen, expressed separately from what is in the file.
 *
 * Zed keeps one of these (crates/editor/src/display_map.rs) so that wrapping,
 * folding and sticky scroll can all say "display row 40" without meaning
 * "buffer row 40". Everything in this pane that used to multiply a buffer row
 * by the line height asks here first.
 *
 * ### What it costs
 *
 * With soft wrap off the map is the identity: [isIdentity] is true, every
 * query is one clamp, and not a single array is allocated. That is the
 * default — Zed's `soft_wrap` is `"none"` — and it is why the map costs the
 * existing editor nothing.
 *
 * With wrap on, the file is cut into fixed blocks of `BLOCK_ROWS` rows. A
 * block is measured — one batched read of its text, then [SoftWrap.wrap] over
 * each row — the first time a query lands in it, and not again until an edit
 * invalidates it. Blocks nobody has visited are *estimated* at one display
 * row per buffer row, so the document has a height from the first frame;
 * visiting one replaces the estimate with the truth, which can only make the
 * document taller and therefore never moves anything already above the
 * viewport.
 *
 * A Fenwick tree over the blocks answers both directions in O(log blocks) —
 * eleven steps for a 100k-line file — and a measured block carries a running
 * count of its own rows, so the second half of a lookup is a binary search
 * over at most `BLOCK_ROWS` entries.
 */
internal class DisplayMap(
    private val rowCount: () -> Int,
    /** Text of buffer rows [first, last), served from the pane's line window. */
    private val textOfRows: (first: Int, last: Int) -> List<String>,
) {
    var wrapColumns: Int = 0
        private set
    var tabSize: Int = 4
        private set

    /** True while every buffer row is exactly one display row. */
    val isIdentity: Boolean get() = wrapColumns <= 0

    /**
     * Set the wrap width (0 turns wrapping off) and the tab width. Everything
     * measured is thrown away when either changes, because both change every
     * break in the file.
     */
    fun configure(wrapColumns: Int, tabSize: Int) {
        if (wrapColumns == this.wrapColumns && tabSize == this.tabSize) return
        this.wrapColumns = wrapColumns
        this.tabSize = tabSize
        rows = -1
        forgetWraps()
        if (!isIdentity) ensureShape()
    }

    // ---- Remembered breaks -----------------------------------------------

    /**
     * The last few long rows' breaks, keyed by the text they were computed
     * from.
     *
     * Wrapping a row costs a scan of the whole row, and one frame asks for
     * the same row's breaks several times over: the draw pass for the
     * segments on screen, the caret for the segment it sits in, the
     * selection handles, `positionAt` for a tap, and an arrow key three more
     * times. On an ordinary row that is a few hundred characters of
     * arithmetic and not worth remembering; on the single 50k-character line
     * a minified file is, it is the frame budget.
     *
     * Keyed by the text rather than by row number because a row's index
     * moves under an edit while its breaks depend on nothing but its own
     * text, this width and this tab size — so a stale entry cannot be
     * wrong, only unused.
     */
    private val wrapKeys = arrayOfNulls<String>(WRAP_CACHE_SLOTS)
    private val wrapValues = arrayOfNulls<WrappedLine>(WRAP_CACHE_SLOTS)
    private var wrapNext = 0

    /**
     * Rows this map has scanned to find their breaks, for the tests that
     * hold a frame to the rows on screen rather than the characters behind
     * them.
     */
    internal var wrapScans = 0
        private set

    private fun forgetWraps() {
        wrapKeys.fill(null)
        wrapValues.fill(null)
        wrapNext = 0
    }

    private fun rememberedWrap(text: String): WrappedLine? {
        for (i in wrapKeys.indices) {
            if (wrapKeys[i] == text) return wrapValues[i]
        }
        return null
    }

    private fun rememberWrap(text: String, wrapped: WrappedLine) {
        wrapKeys[wrapNext] = text
        wrapValues[wrapNext] = wrapped
        wrapNext = (wrapNext + 1) % WRAP_CACHE_SLOTS
    }

    // ---- Block index -----------------------------------------------------

    private var rows = -1
    private var blockCount = 0

    /** Per block, a running display-row count of its rows; null = unmeasured. */
    private var measured: Array<IntArray?> = emptyArray()

    /** Display rows per block, and a Fenwick tree over that. */
    private var blockValue = IntArray(0)
    private var tree = IntArray(1)
    private var total = 0

    private companion object {
        /**
         * Rows per block. Small enough that measuring one is a few dozen
         * microseconds and that the pane's own line window usually already
         * holds it; large enough that a 100k-line file is 1563 blocks rather
         * than a tree over a hundred thousand rows.
         */
        const val BLOCK_ROWS = 64

        /**
         * Rows whose breaks are remembered at once. A screenful of wrapped
         * rows is more than this, but the rows worth remembering are the
         * long ones and there are never many of those on screen at a time.
         */
        const val WRAP_CACHE_SLOTS = 8

        /**
         * Shortest row worth remembering. A row that wraps into two or three
         * is cheaper to scan again than to keep, and keeping it would evict
         * the one row on screen whose scan actually costs something.
         */
        const val WRAP_CACHE_MIN_LENGTH = 1024
    }

    private fun rowsIn(block: Int): Int = min(BLOCK_ROWS, rows - block * BLOCK_ROWS)

    private fun ensureShape() {
        val count = rowCount().coerceAtLeast(1)
        if (count == rows) return
        rows = count
        blockCount = (count + BLOCK_ROWS - 1) / BLOCK_ROWS
        measured = arrayOfNulls(blockCount)
        blockValue = IntArray(blockCount) { rowsIn(it) }
        rebuildTree()
    }

    private fun rebuildTree() {
        val n = blockCount
        tree = IntArray(n + 1)
        total = 0
        for (i in 0 until n) {
            tree[i + 1] = blockValue[i]
            total += blockValue[i]
        }
        for (i in 1..n) {
            val parent = i + (i and -i)
            if (parent <= n) tree[parent] += tree[i]
        }
    }

    private fun setBlock(block: Int, value: Int) {
        val delta = value - blockValue[block]
        if (delta == 0) return
        blockValue[block] = value
        total += delta
        var i = block + 1
        while (i <= blockCount) {
            tree[i] += delta
            i += i and -i
        }
    }

    /** Display rows in front of [block]. */
    private fun prefixOf(block: Int): Int {
        var i = block
        var sum = 0
        while (i > 0) {
            sum += tree[i]
            i -= i and -i
        }
        return sum
    }

    /** The block display row [at] falls in. */
    private fun blockAt(at: Int): Int {
        var index = 0
        var bit = Integer.highestOneBit(blockCount.coerceAtLeast(1))
        var remaining = at
        while (bit > 0) {
            val next = index + bit
            if (next <= blockCount && tree[next] <= remaining) {
                index = next
                remaining -= tree[next]
            }
            bit = bit shr 1
        }
        return index.coerceIn(0, blockCount - 1)
    }

    /**
     * Measure [block] if it has not been, and return the running display-row
     * count of its rows.
     *
     * Measuring only ever makes a block taller — a row is at least one
     * display row — so the prefix in front of it is untouched, and the query
     * that asked for it stays valid without a second pass.
     */
    private fun blockPrefix(block: Int): IntArray {
        measured[block]?.let { return it }
        val start = block * BLOCK_ROWS
        val count = rowsIn(block)
        val prefix = IntArray(count + 1)
        val texts = textOfRows(start, start + count)
        var sum = 0
        for (i in 0 until count) {
            prefix[i] = sum
            sum += segmentCountOf(texts.getOrElse(i) { "" })
        }
        prefix[count] = sum
        measured[block] = prefix
        setBlock(block, sum)
        return prefix
    }

    private fun wrap(text: String, indentColumns: Int): Int =
        SoftWrap.wrap(text, wrapColumns, tabSize, indentColumns)

    // ---- Invalidation ----------------------------------------------------

    /**
     * Forget what was measured for buffer rows [fromRow]..[toRow].
     *
     * When the row count changed, everything after [fromRow] has shifted
     * under its block and goes with it — that is the case a newline or a
     * deleted line lands in. When it did not, only the rows the edit actually
     * rewrote are dropped, which is what keeps typing in a 100k-line file
     * from re-measuring the file on every keystroke.
     */
    fun invalidate(fromRow: Int, toRow: Int) {
        if (isIdentity) return
        val count = rowCount().coerceAtLeast(1)
        if (count != rows) {
            reshapeKeeping(count, fromRow)
            return
        }
        val first = fromRow.coerceAtLeast(0) / BLOCK_ROWS
        val last = toRow.coerceIn(0, rows - 1) / BLOCK_ROWS
        if (first > last) return
        var changed = false
        for (block in first..last) {
            if (measured[block] == null) continue
            measured[block] = null
            blockValue[block] = rowsIn(block)
            changed = true
        }
        // One O(blocks) pass rather than a Fenwick update per block: it is a
        // few thousand additions even for a huge file, and it does not care
        // how many blocks the edit reached.
        if (changed) rebuildTree()
    }

    /** Everything is suspect — a reload, an undo, a new wrap width. */
    fun invalidateAll() {
        if (isIdentity) return
        rows = -1
        ensureShape()
    }

    /**
     * Re-block for a new row count, keeping what was measured in front of
     * [fromRow]: that text did not change, and neither did its rows' indices.
     */
    private fun reshapeKeeping(count: Int, fromRow: Int) {
        val keptBlocks = fromRow.coerceAtLeast(0) / BLOCK_ROWS
        val old = measured
        rows = count
        blockCount = (count + BLOCK_ROWS - 1) / BLOCK_ROWS
        measured = arrayOfNulls(blockCount)
        blockValue = IntArray(blockCount) { rowsIn(it) }
        for (block in 0 until min(keptBlocks, blockCount)) {
            val prefix = old.getOrNull(block) ?: continue
            // A block only keeps its measurement if it still holds the same
            // rows; the last block of a file that just got shorter does not.
            if (prefix.size - 1 != rowsIn(block)) continue
            measured[block] = prefix
            blockValue[block] = prefix[prefix.size - 1]
        }
        rebuildTree()
    }

    // ---- Queries ---------------------------------------------------------

    val displayRowCount: Int
        get() {
            if (isIdentity) return rowCount().coerceAtLeast(1)
            ensureShape()
            return total
        }

    /** How many display rows a row holding [text] takes at this width. */
    fun segmentCountOf(text: String): Int {
        if (isIdentity) return 1
        // A long row's breaks are worth keeping, and counting them is the
        // same scan; a short one counts without allocating anything.
        if (text.length >= WRAP_CACHE_MIN_LENGTH) return wrapOf(text).segmentCount
        wrapScans++
        return wrap(text, SoftWrap.indentColumns(text, tabSize))
    }

    /** [text]'s segments; [WrappedLine.FITS] when it takes a single row. */
    fun wrapOf(text: String): WrappedLine {
        if (isIdentity || text.isEmpty()) return WrappedLine.FITS
        if (text.length >= WRAP_CACHE_MIN_LENGTH) {
            rememberedWrap(text)?.let { return it }
        }
        wrapScans++
        val wrapped = SoftWrap.of(text, wrapColumns, tabSize)
        if (text.length >= WRAP_CACHE_MIN_LENGTH && wrapped.wraps) rememberWrap(text, wrapped)
        return wrapped
    }

    /** The display row buffer row [row] starts on. */
    fun displayRowOf(row: Int): Int {
        if (isIdentity) return row.coerceIn(0, rowCount() - 1)
        ensureShape()
        val clamped = row.coerceIn(0, rows - 1)
        val block = clamped / BLOCK_ROWS
        return prefixOf(block) + blockPrefix(block)[clamped - block * BLOCK_ROWS]
    }

    /**
     * The buffer row display row [displayRow] comes from.
     *
     * Loops because the document may be taller than it currently claims:
     * clamping to an estimate that measuring is about to raise would answer
     * for a row that is not the one asked about. Each turn either resolves or
     * measures a block further on, so it stops after one or two.
     */
    fun bufferRowOf(displayRow: Int): Int {
        if (isIdentity) return displayRow.coerceIn(0, rowCount() - 1)
        ensureShape()
        val want = displayRow.coerceAtLeast(0)
        var block: Int
        var prefix: IntArray
        var within: Int
        var turns = 0
        do {
            val at = want.coerceAtMost((total - 1).coerceAtLeast(0))
            block = blockAt(at)
            val settled = measured[block] != null
            prefix = blockPrefix(block)
            within = at - prefixOf(block)
            if (settled) break
        } while (turns++ < blockCount)
        var low = 0
        var high = prefix.size - 2
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (prefix[mid] <= within) low = mid else high = mid - 1
        }
        return (block * BLOCK_ROWS + low).coerceIn(0, rows - 1)
    }

    /** The buffer rows display rows [first, last) are drawn from, inclusive. */
    fun bufferRowRange(first: Int, last: Int): IntRange {
        val firstRow = bufferRowOf(first)
        val lastRow = bufferRowOf((last - 1).coerceAtLeast(first))
        return firstRow..lastRow.coerceAtLeast(firstRow)
    }

    /**
     * Fill [window] with display rows [first, last). [lines] holds the text
     * of buffer rows from [firstBufferRow] on, which the caller has already
     * fetched in one read.
     *
     * Costs the display rows it fills, not the characters behind them: an
     * ordinary row's breaks are a few dozen characters of arithmetic, a long
     * row's come from [wrapOf]'s memory, and either way only the segments
     * inside the window are looked at.
     */
    fun fillWindow(
        window: DisplayWindow,
        first: Int,
        last: Int,
        firstBufferRow: Int,
        lines: List<String>,
    ) {
        val count = (last - first).coerceAtLeast(0)
        window.reset(first, count)
        if (count == 0) return
        if (isIdentity) {
            val rowCount = rowCount()
            for (i in 0 until count) {
                val row = first + i
                if (row >= rowCount) break
                window.add(row, 0, 0, Int.MAX_VALUE, 0)
            }
            return
        }
        ensureShape()
        var display = displayRowOf(firstBufferRow)
        var row = firstBufferRow
        while (display < last && row < rows) {
            val text = lines.getOrElse(row - firstBufferRow) { "" }
            val wrapped = wrapOf(text)
            val segments = wrapped.segmentCount
            // Open at the segment the window starts on rather than walking
            // the row from its first one: a window forty rows into the single
            // long line a minified file is must cost forty rows of work, not
            // the line's thousand.
            var segment = (first - display).coerceAtLeast(0)
            while (segment < segments && display + segment < last) {
                window.add(
                    row = row,
                    segment = segment,
                    start = wrapped.startOf(segment),
                    end = if (segment == segments - 1) {
                        Int.MAX_VALUE
                    } else {
                        wrapped.endOf(segment, text.length)
                    },
                    indent = if (segment == 0) 0 else wrapped.indentColumns,
                )
                segment++
            }
            display += segments
            row++
        }
    }

    /**
     * Measure every block the display rows [first, last) fall in.
     *
     * Anything that decides *where* to scroll has to do this before it works
     * out where the caret is drawn. The draw pass measures these blocks as it
     * resolves the top of the viewport, measuring can only make a block
     * taller, and a block that grows above the caret pushes the caret's own
     * display row down — which is how a jump used to scroll to a row the
     * caret was no longer on.
     */
    fun measureWindow(first: Int, last: Int) {
        if (isIdentity) return
        ensureShape()
        var at = first.coerceAtLeast(0)
        while (at < last && at < total) {
            val block = blockAt(at)
            blockPrefix(block)
            val end = prefixOf(block) + blockValue[block]
            // Measuring can only have made this block taller, so `end` is
            // past `at`; the guard is for the empty-file shape, not for it.
            if (end <= at) break
            at = end
        }
    }
}
