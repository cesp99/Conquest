package to.eyed.conquest.code.ui.editor

/**
 * The editing commands that go beyond moving one cursor around: multiple
 * cursors, the line operations, comment toggling, auto-closing pairs and
 * auto-indent.
 *
 * They live outside [EditorState] because they are *commands* — each one
 * reads the caret set, works out one edit per caret, and hands the batch to
 * [EditorState.applyCaretEdits], which owns the ordering rules that make a
 * multi-caret edit safe. Keeping them here leaves EditorState as what it has
 * always been: the pane's view state plus the primitives.
 *
 * Every one of them is multi-caret by construction. Where Zed's own
 * behaviour is not obvious the comment says which of its actions was
 * followed; the bindings are in `EditorPane.handleEditorKey` and the
 * user-facing list is `docs/SHORTCUTS.md`.
 */

/** Rows read per bridge call while searching for occurrences. */
private const val SEARCH_CHUNK_ROWS = 256

/** Ceiling on Ctrl+Shift+L, so one press on a common word can't hang a frame. */
private const val SELECT_ALL_MATCHES_LIMIT = 1024

// ---- Multiple cursors ----------------------------------------------------

/**
 * Add a caret one row above or below the outermost one, or — if the last
 * press went the other way — take the outermost one back off again, which is
 * how Zed's AddSelectionAbove/Below pair behaves when you overshoot.
 *
 * The column is a goal rather than a position: a short line in the middle of
 * the run clamps its caret without dragging the rest of the column in with
 * it, the same as Zed's columnar selections.
 */
internal fun EditorState.addCaretVertically(delta: Int) {
    val direction = if (delta < 0) -1 else 1
    val carets = caretsInOrder()
    val grew = addCaretDirection
    if (grew != 0 && grew != direction && carets.size > 1) {
        val goal = addCaretGoalCol
        val kept = if (grew < 0) carets.drop(1) else carets.dropLast(1)
        val edge = if (grew < 0) kept.first() else kept.last()
        setCarets(kept, edge)
        // The run is still growing the way it was; it has just been asked to
        // give a row back.
        addCaretDirection = grew
        addCaretGoalCol = goal
        return
    }
    val edge = if (direction < 0) carets.first() else carets.last()
    val row = edge.headRow + direction
    if (row !in 0 until lineCount) return
    val goal = if (addCaretDirection == 0) edge.headCol else addCaretGoalCol
    val added = Caret(row, goal.coerceAtMost(line(row).length))
    setCarets(carets + added, added)
    addCaretDirection = direction
    addCaretGoalCol = goal
}

/**
 * Zed's `editor::SelectNext`: with bare carets, select the word each one
 * sits in; with something already selected, add a caret over the next
 * occurrence of it, wrapping at the end of the buffer.
 *
 * A selection that spans rows is left alone. Zed searches across newlines;
 * doing that here would mean stitching the search across the chunks the
 * bridge hands back for the sake of a case nobody reaches with Ctrl+D.
 */
internal fun EditorState.selectNextOccurrence(): Boolean {
    val carets = caretsInOrder()
    val primary = primaryCaret()
    if (carets.all { it.isEmpty }) {
        var newPrimary: Caret? = null
        val words = carets.map { caret ->
            val text = line(caret.headRow)
            val (start, end) = wordAround(text, caret.headCol)
            Caret(caret.headRow, start, caret.headRow, end)
                .also { if (caret == primary) newPrimary = it }
        }
        if (words.all { it.isEmpty }) return false
        setCarets(words, newPrimary ?: words.last())
        // The query came from a word, so from here on only whole words match.
        selectNextWordwise = true
        return true
    }
    val newest = carets.last { !it.isEmpty }
    if (newest.startRow != newest.endRow) return false
    val query = textIn(newest)
    if (query.isEmpty()) return false
    val match = nextOccurrence(query, newest.endRow, newest.endCol, selectNextWordwise, carets)
        ?: return false
    val wordwise = selectNextWordwise
    setCarets(carets + match, match)
    selectNextWordwise = wordwise
    return true
}

/** Zed's `editor::SelectAllMatches`: a caret on every occurrence at once. */
internal fun EditorState.selectAllOccurrences(): Boolean {
    val carets = caretsInOrder()
    val seed = carets.lastOrNull { !it.isEmpty }
        ?: run {
            if (!selectNextOccurrence()) return false
            caretsInOrder().lastOrNull { !it.isEmpty } ?: return false
        }
    if (seed.startRow != seed.endRow) return false
    val query = textIn(seed)
    if (query.isEmpty()) return false
    val matches = ArrayList<Caret>()
    forEachOccurrence(query, selectNextWordwise, 0, revisitFirstRow = false) { row, col, _ ->
        matches.add(Caret(row, col, row, col + query.length))
        matches.size < SELECT_ALL_MATCHES_LIMIT
    }
    if (matches.isEmpty()) return false
    val nearest = matches.firstOrNull { it.startRow >= seed.startRow } ?: matches.last()
    setCarets(matches, nearest)
    return true
}

/** The first occurrence at or after (row, col) that no caret already holds. */
private fun EditorState.nextOccurrence(
    query: String,
    fromRow: Int,
    fromCol: Int,
    wordwise: Boolean,
    taken: List<Caret>,
): Caret? {
    var found: Caret? = null
    forEachOccurrence(query, wordwise, fromRow, revisitFirstRow = true) { row, col, wrapped ->
        // The starting row is walked twice — once for what follows the
        // caret, and again at the end of the wrap for what precedes it.
        if (!wrapped && row == fromRow && col < fromCol) return@forEachOccurrence true
        val candidate = Caret(row, col, row, col + query.length)
        if (taken.any { it.startRow == row && it.startCol < candidate.endCol && col < it.endCol }) {
            return@forEachOccurrence true
        }
        found = candidate
        false
    }
    return found
}

/**
 * Walk the occurrences of [query] from [fromRow] forward and round the end
 * of the buffer, stopping when [action] answers false. Its third argument is
 * true once the walk has wrapped.
 *
 * [revisitFirstRow] makes the walk end on [fromRow] a second time, which is
 * what "the next occurrence after the cursor" needs — the matches earlier on
 * the starting row come last. A walk that wants every match once, and
 * starts at row 0, must not: it would count that row's matches twice.
 *
 * Reads the buffer in chunks straight from the bridge rather than through
 * the pane's line window, so searching never evicts the lines being drawn.
 * The right home for this is a search API on the engine — Zed runs an
 * Aho-Corasick automaton over the rope — but that is a bridge call we don't
 * have, and Ctrl+D is a keypress somebody made, not a keystroke on the
 * typing path.
 */
internal fun EditorState.forEachOccurrence(
    query: String,
    wordwise: Boolean,
    fromRow: Int,
    revisitFirstRow: Boolean,
    action: (row: Int, col: Int, wrapped: Boolean) -> Boolean,
) {
    var row = fromRow.coerceIn(0, lineCount - 1)
    val rowsToVisit = if (revisitFirstRow) lineCount + 1 else lineCount
    var visited = 0
    var wrapped = false
    while (visited < rowsToVisit) {
        val end = (row + SEARCH_CHUNK_ROWS).coerceAtMost(lineCount)
        val chunk = linesOf(row, end).split('\n')
        for ((index, text) in chunk.withIndex()) {
            if (visited >= rowsToVisit) return
            val at = row + index
            var found = text.indexOf(query)
            while (found >= 0) {
                if (!wordwise || isWholeWord(text, found, found + query.length)) {
                    if (!action(at, found, wrapped)) return
                }
                found = text.indexOf(query, found + 1)
            }
            visited++
        }
        if (end >= lineCount) {
            row = 0
            wrapped = true
        } else {
            row = end
        }
    }
}

private fun isWholeWord(text: String, start: Int, end: Int): Boolean {
    fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'
    if (start > 0 && isWordChar(text[start - 1])) return false
    if (end < text.length && isWordChar(text[end])) return false
    return true
}

// ---- Line operations -----------------------------------------------------

/**
 * The contiguous row ranges the carets cover, with the carets that produced
 * each. A caret whose selection stops at column 0 doesn't claim that row,
 * and ranges that touch are merged — both of them Zed's rules, from
 * `consume_contiguous_rows`.
 */
private fun EditorState.rowGroups(): List<Pair<IntRange, MutableList<Caret>>> {
    val groups = ArrayList<Pair<IntRange, MutableList<Caret>>>()
    for (caret in caretsInOrder()) {
        val end = if (caret.endRow > caret.startRow && caret.endCol == 0) {
            caret.endRow - 1
        } else {
            caret.endRow
        }
        val last = groups.lastOrNull()
        if (last != null && caret.startRow <= last.first.last + 1) {
            groups[groups.size - 1] =
                (last.first.first..maxOf(last.first.last, end)) to last.second
            last.second.add(caret)
        } else {
            groups.add((caret.startRow..end) to mutableListOf(caret))
        }
    }
    return groups
}

/** Byte offset just past the text of [row], before its newline. */
private fun EditorState.lineEndOffset(row: Int): Long =
    lineStartOffset(row) + utf8Length(line(row))

private fun EditorState.linesOf(rows: IntRange): String = linesOf(rows.first, rows.last + 1)

/**
 * Zed's `MoveLineUp` / `MoveLineDown`: swap each group of rows with the row
 * on the far side of it. Groups that would run off the ends of the buffer
 * stay where they are, and their carets with them.
 */
internal fun EditorState.moveLines(delta: Int) {
    val edits = ArrayList<EditorState.CaretEdit>()
    val carets = ArrayList<Caret>()
    val primary = primaryCaret()
    var newPrimary: Caret? = null
    for ((rows, group) in rowGroups()) {
        val blocked = if (delta < 0) rows.first == 0 else rows.last >= lineCount - 1
        for (caret in group) {
            val moved = if (blocked) {
                caret
            } else {
                Caret(
                    caret.anchorRow + delta,
                    caret.anchorCol,
                    caret.headRow + delta,
                    caret.headCol,
                )
            }
            carets.add(moved)
            if (caret == primary) newPrimary = moved
        }
        if (blocked) continue
        val block = linesOf(rows)
        if (delta < 0) {
            val neighbour = line(rows.first - 1)
            edits.add(
                EditorState.CaretEdit(
                    start = lineStartOffset(rows.first - 1),
                    end = lineEndOffset(rows.last),
                    replacement = "$block\n$neighbour",
                )
            )
        } else {
            val neighbour = line(rows.last + 1)
            edits.add(
                EditorState.CaretEdit(
                    start = lineStartOffset(rows.first),
                    end = lineEndOffset(rows.last + 1),
                    replacement = "$neighbour\n$block",
                )
            )
        }
    }
    if (edits.isEmpty()) return
    applyEdits(edits, carets, newPrimary ?: carets.last())
}

/**
 * Zed's `DuplicateLineUp` / `DuplicateLineDown`. Either way the carets stay
 * on the row they were on, which for the upward copy means they end up on
 * the duplicate — what Zed's own selection fix-up arrives at.
 */
internal fun EditorState.duplicateLines(above: Boolean) {
    val edits = ArrayList<EditorState.CaretEdit>()
    val carets = ArrayList<Caret>()
    val primary = primaryCaret()
    var newPrimary: Caret? = null
    var shift = 0
    for ((rows, group) in rowGroups()) {
        for (caret in group) {
            val moved = Caret(
                caret.anchorRow + shift,
                caret.anchorCol,
                caret.headRow + shift,
                caret.headCol,
            )
            carets.add(moved)
            if (caret == primary) newPrimary = moved
        }
        val block = linesOf(rows)
        val at = if (above) lineStartOffset(rows.first) else lineEndOffset(rows.last)
        edits.add(
            EditorState.CaretEdit(
                start = at,
                end = at,
                replacement = if (above) "$block\n" else "\n$block",
            )
        )
        // Everything below this group has just been pushed down by the copy.
        shift += rows.last - rows.first + 1
    }
    if (edits.isEmpty()) return
    applyEdits(edits, carets, newPrimary ?: carets.last())
}

/**
 * Zed's `DeleteLine`: take out every row the carets touch, leaving one caret
 * per group on the row that closes the gap, at the column it had.
 */
internal fun EditorState.deleteLines() {
    val groups = rowGroups()
    val primary = primaryCaret()
    val edits = groups.map { (rows, group) ->
        val isPrimary = group.any { it == primary }
        val goal = group.first().headCol
        if (rows.last < lineCount - 1) {
            EditorState.CaretEdit(
                start = lineStartOffset(rows.first),
                end = lineStartOffset(rows.last + 1),
                replacement = "",
                head = 0,
                columnGoal = goal,
                isPrimary = isPrimary,
            )
        } else {
            // Nothing follows, so the newline that has to go is the one
            // *before* the range — which also puts the caret a row higher.
            EditorState.CaretEdit(
                start = (lineStartOffset(rows.first) - 1).coerceAtLeast(0),
                end = lineEndOffset(rows.last),
                replacement = "",
                head = 0,
                columnGoal = goal,
                isPrimary = isPrimary,
            )
        }
    }
    applyCaretEdits(edits)
}

/**
 * Zed's `JoinLines`: pull the following row onto this one, dropping its
 * indent and putting a single space at the seam unless one side is already
 * blank or already ends in whitespace. A bare caret joins its row with the
 * next; a selection joins everything it spans.
 */
internal fun EditorState.joinLines() {
    val primary = primaryCaret()
    val edits = rowGroups().map { (rows, group) ->
        // A group that covers a single row still means "and the next one",
        // or the command would do nothing for a bare caret.
        val last = if (rows.last == rows.first) rows.first + 1 else rows.last
        if (last > lineCount - 1) {
            // Nothing follows the last row of the buffer, so this group has
            // nothing to pull up. Its caret still travels with the batch:
            // the new caret set is built from these edits, and dropping the
            // edit would drop the caret with it.
            val at = lineEndOffset(rows.first)
            return@map EditorState.CaretEdit(
                start = at,
                end = at,
                replacement = "",
                head = 0,
                columnGoal = group.first().headCol,
                isPrimary = group.any { it == primary },
            )
        }
        val joined = StringBuilder()
        var tail = line(rows.first)
        var seam = 0
        for (row in rows.first + 1..last) {
            val text = line(row).trimStart(' ', '\t')
            val separator =
                if (text.isEmpty() || tail.isEmpty() || tail.last().isWhitespace()) "" else " "
            joined.append(separator)
            seam = utf8Length(joined.toString())
            joined.append(text)
            if (text.isNotEmpty()) tail = text
        }
        EditorState.CaretEdit(
            start = lineEndOffset(rows.first),
            end = lineEndOffset(last),
            replacement = joined.toString(),
            head = seam,
            isPrimary = group.any { it == primary },
        )
    }
    if (edits.isEmpty()) return
    applyCaretEdits(edits)
}

// ---- Comments ------------------------------------------------------------

/** How one row's columns move when its comment prefix goes in or comes out. */
private class ColumnShift(val at: Int, val removed: Int, val inserted: Int) {
    fun apply(col: Int): Int = when {
        col <= at -> col
        col <= at + removed -> at + inserted
        else -> col - removed + inserted
    }
}

/**
 * Zed's `editor::ToggleComments`. The tokens are the grammar's own, so this
 * writes `#` in Python, `//` in Rust and `<!--` … `-->` in Markdown, and
 * writes nothing at all in a language that has neither.
 *
 * Zed's order of preference, and ours: line comments where the language has
 * them, the block comment where it does not. Markdown and CSS are the two of
 * ours with only a block comment; a diff has neither, and toggling in one
 * does nothing rather than corrupting the patch.
 */
internal fun EditorState.toggleComment(): Boolean {
    val token = languageConfig.lineComment
        ?: return languageConfig.blockComment?.let { toggleBlockComment(it) } ?: false
    val prefix = token.trimEnd(' ')
    val padding = token.substring(prefix.length)

    val edits = ArrayList<EditorState.CaretEdit>()
    val shifts = HashMap<Int, ColumnShift>()
    for ((rows, _) in rowGroups()) {
        val affected = ArrayList<Int>()
        var allCommented = true
        for (row in rows) {
            val text = line(row)
            // A blank row inside a multi-row range is passed over rather
            // than counted against the "everything is commented" test.
            if (rows.first != rows.last && text.isBlank()) continue
            affected.add(row)
            if (!text.trimStart(' ', '\t').startsWith(prefix)) allCommented = false
        }
        if (affected.isEmpty()) continue

        if (allCommented) {
            for (row in affected) {
                val text = line(row)
                val indent = text.indexOfFirst { it != ' ' && it != '\t' }.coerceAtLeast(0)
                var end = indent + prefix.length
                // Take the padding with it only where it matches, so an
                // aligned `//␠` round-trips and a bare `//` stays bare.
                var i = 0
                while (i < padding.length && end < text.length && text[end] == padding[i]) {
                    end++
                    i++
                }
                edits.add(
                    EditorState.CaretEdit(
                        start = byteOffsetOf(row, indent),
                        end = byteOffsetOf(row, end),
                        replacement = "",
                    )
                )
                shifts[row] = ColumnShift(indent, end - indent, 0)
            }
        } else {
            val column = affected.minOf { row ->
                val text = line(row)
                text.indexOfFirst { it != ' ' && it != '\t' }.let {
                    if (it < 0) text.length else it
                }
            }
            for (row in affected) {
                val at = byteOffsetOf(row, column)
                edits.add(EditorState.CaretEdit(start = at, end = at, replacement = token))
                shifts[row] = ColumnShift(column, 0, token.length)
            }
        }
    }
    if (edits.isEmpty()) return true

    // The carets keep their rows; only their columns move, and only by the
    // width of what went in or came out of the row they sit on.
    val primary = primaryCaret()
    var newPrimary: Caret? = null
    val carets = caretsInOrder().map { caret ->
        val moved = Caret(
            caret.anchorRow,
            shifts[caret.anchorRow]?.apply(caret.anchorCol) ?: caret.anchorCol,
            caret.headRow,
            shifts[caret.headRow]?.apply(caret.headCol) ?: caret.headCol,
        )
        if (caret == primary) newPrimary = moved
        moved
    }
    applyEdits(edits, carets, newPrimary ?: carets.last())
    return true
}

/**
 * The block-comment half, for the languages that have no line comment:
 * Markdown's `<!--` … `-->` and CSS's C-style pair. The delimiters wrap the
 * range the carets cover — the opener at the shallowest indent of the first
 * row, the closer past the last row's content — and come off again when they
 * are already there.
 *
 * The space either side goes in with them and comes off tolerantly, so
 * `<!-- note -->` round-trips and a hand-written `<!--note-->` still
 * uncomments.
 */
private fun EditorState.toggleBlockComment(comment: BlockComment): Boolean {
    val edits = ArrayList<EditorState.CaretEdit>()
    val shifts = HashMap<Int, MutableList<ColumnShift>>()
    fun note(row: Int, shift: ColumnShift) {
        shifts.getOrPut(row) { mutableListOf() }.add(shift)
    }

    for ((rows, _) in rowGroups()) {
        // A blank row inside a multi-row range is passed over, as it is for
        // line comments; a range that is only a blank row still comments.
        val affected = rows.filter { rows.first == rows.last || line(it).isNotBlank() }
        if (affected.isEmpty()) continue
        val firstRow = affected.first()
        val lastRow = affected.last()
        val head = line(firstRow)
        val tail = line(lastRow)
        val open = head.indexOfFirst { it != ' ' && it != '\t' }
            .let { if (it < 0) head.length else it }
        val close = tail.trimEnd().length
        // On one row the two delimiters have to fit side by side before the
        // text can be said to be commented at all.
        val roomForBoth = firstRow != lastRow ||
            close - open >= comment.start.length + comment.end.length
        val commented = roomForBoth &&
            head.startsWith(comment.start, open) &&
            tail.trimEnd().endsWith(comment.end)

        if (commented) {
            var from = open + comment.start.length
            if (head.getOrNull(from) == ' ') from++
            var to = close - comment.end.length
            if (tail.getOrNull(to - 1) == ' ' && (firstRow != lastRow || to - 1 >= from)) to--
            edits.add(
                EditorState.CaretEdit(
                    start = byteOffsetOf(firstRow, open),
                    end = byteOffsetOf(firstRow, from),
                    replacement = "",
                )
            )
            edits.add(
                EditorState.CaretEdit(
                    start = byteOffsetOf(lastRow, to),
                    end = byteOffsetOf(lastRow, close),
                    replacement = "",
                )
            )
            note(firstRow, ColumnShift(open, from - open, 0))
            note(lastRow, ColumnShift(to, close - to, 0))
        } else if (open == close) {
            // Nothing to wrap: an empty pair with a space either side, and
            // the caret between them ready to type into — so that what is
            // typed there uncomments again cleanly. The shift is counted from
            // one column earlier than the insertion because a caret standing
            // exactly at an insertion point does not move.
            val at = byteOffsetOf(firstRow, open)
            edits.add(
                EditorState.CaretEdit(
                    start = at,
                    end = at,
                    replacement = "${comment.start}  ${comment.end}",
                )
            )
            note(firstRow, ColumnShift(open - 1, 0, comment.start.length + 1))
        } else {
            val opener = byteOffsetOf(firstRow, open)
            val closer = byteOffsetOf(lastRow, close)
            edits.add(
                EditorState.CaretEdit(start = opener, end = opener, replacement = "${comment.start} ")
            )
            edits.add(
                EditorState.CaretEdit(start = closer, end = closer, replacement = " ${comment.end}")
            )
            note(firstRow, ColumnShift(open, 0, comment.start.length + 1))
            note(lastRow, ColumnShift(close, 0, comment.end.length + 1))
        }
    }
    if (edits.isEmpty()) return true

    // Two shifts can land on one row, and each is measured against the row as
    // it stands now — so they are applied from the right, where an earlier
    // one cannot have moved the column a later one is counted from.
    fun moved(row: Int, col: Int): Int =
        shifts[row]?.sortedByDescending { it.at }?.fold(col) { at, shift -> shift.apply(at) } ?: col

    val primary = primaryCaret()
    var newPrimary: Caret? = null
    val carets = caretsInOrder().map { caret ->
        val moved = Caret(
            caret.anchorRow,
            moved(caret.anchorRow, caret.anchorCol),
            caret.headRow,
            moved(caret.headRow, caret.headCol),
        )
        if (caret == primary) newPrimary = moved
        moved
    }
    applyEdits(edits, carets, newPrimary ?: carets.last())
    return true
}

// ---- Typing: auto-close, surround, auto-indent ---------------------------

/**
 * Type [text] at every caret, honouring the bracket and quote pairs.
 *
 * Four behaviours, in the order they are tested, because the order is what
 * makes them bearable:
 *
 * 1. A pair the language disables here does not apply at all: `not_in =
 *    ["string", "comment"]` is on every quote pair Zed ships, and it is why a
 *    `"` typed inside a comment stays a lone `"`. That question needs the
 *    syntax tree, so it is the engine's — see [EditorState.enabledPairsAt] —
 *    and it is asked once for every caret, only when a pair character was
 *    typed, and only when some candidate pair actually carries a `not_in`.
 * 2. With something selected, an opener wraps the selection instead of
 *    replacing it, and the selection survives (Zed's `auto_surround`).
 * 3. Typing a closer that is already sitting in front of the caret steps
 *    over it rather than doubling it.
 * 4. An opener brings its closer with it, but only where the closer would
 *    land somewhere sensible: at the end of the line, before whitespace, or
 *    before one of the language's `autoclose_before` characters. A quote
 *    additionally refuses to open right after a word character, so the
 *    apostrophe in `don't` stays an apostrophe.
 *
 * Openers can be more than one character — Python's `f"`, Rust's `r#"`, the
 * two that open a C-style block comment — so what counts as an opener is
 * "the longest pair whose start ends with what was typed, and whose earlier
 * characters are already behind the caret".
 *
 * Anything else is a plain insert, and single-caret plain inserts go back
 * through [EditorState.insertAtCursor] so typing keeps costing exactly what
 * it did before.
 */
internal fun EditorState.typeCharacter(text: String) {
    val config = languageConfig
    val candidates = config.pairsTriggeredBy(text)
    if (candidates.isEmpty()) {
        insertAtCursor(text)
        return
    }
    val carets = caretsInOrder()

    /** Whether the rest of [pair]'s opener is already on the line at [col]. */
    fun opensHere(pair: BracketPair, lineText: String, col: Int): Boolean {
        val alreadyTyped = pair.open.length - text.length
        return pair.openedByTyping(text) &&
            col >= alreadyTyped &&
            lineText.startsWith(pair.open.dropLast(text.length), col - alreadyTyped)
    }

    // The scope is only worth a bridge call for a pair that could actually
    // open here *and* carries a `not_in`. That is a narrow set: no plain
    // bracket has one, and Rust's block-comment pair — the reason typing `*`
    // reaches this at all — needs the `/` in front of it before it counts.
    val needsScope = carets.any { caret ->
        val lineText = line(caret.headRow)
        candidates.any { index ->
            val pair = config.brackets[index]
            pair.notIn.isNotEmpty() && opensHere(pair, lineText, caret.startCol)
        }
    }
    val masks = if (needsScope) {
        enabledPairsAt(
            LongArray(carets.size) { byteOffsetOf(carets[it].startRow, carets[it].startCol) }
        )
    } else {
        null
    }

    val allowedBefore = config.autocloseBefore
    val primary = primaryCaret()
    val edits = carets.mapIndexed { index, caret ->
        val isPrimary = caret == primary
        val start = byteOffsetOf(caret.startRow, caret.startCol)
        val end = byteOffsetOf(caret.endRow, caret.endCol)
        val lineText = line(caret.headRow)
        val live = { pair: Int ->
            val mask = masks?.getOrNull(index)
            mask == null || (mask ushr pair) and 1L == 1L
        }
        // The longest opener whose earlier characters are already typed:
        // `f"` in Python only opens when the `f` is right there.
        // Against the *start* row's line, not the head's: an opener goes in
        // front of the selection's start, and for a selection spanning rows
        // those are different lines. Asking the head's line about the start's
        // column found no opener whenever the head row was the shorter of the
        // two — and the selection was then replaced by the typed character
        // instead of being wrapped in it.
        val startLine = if (caret.startRow == caret.headRow) lineText else line(caret.startRow)
        val opener = candidates
            .filter { live(it) }
            .map { config.brackets[it] }
            .filter { opensHere(it, startLine, caret.startCol) }
            .maxByOrNull { it.open.length }
        // Deliberately *not* filtered by the scope: `not_in` says where a pair
        // may be opened, and Zed applies it to the opening half alone. A `"`
        // typed in front of the one that ends the string you are inside must
        // still step over it rather than doubling it.
        val closer = candidates.map { config.brackets[it] }.firstOrNull { it.close == text }

        if (!caret.isEmpty && opener != null && opener.surround) {
            val inner = textIn(caret)
            // Only what was typed goes in front: the earlier characters of a
            // multi-character opener are already on the line, which is the
            // precondition for it having matched at all.
            return@mapIndexed EditorState.CaretEdit(
                start = start,
                end = end,
                replacement = text + inner + opener.close,
                anchor = utf8Length(text),
                head = utf8Length(text) + utf8Length(inner),
                isPrimary = isPrimary,
            )
        }
        if (caret.isEmpty && closer != null &&
            lineText.startsWith(closer.close, caret.headCol)
        ) {
            // Step over: no edit at all, just a caret that moved.
            return@mapIndexed EditorState.CaretEdit(
                start = start,
                end = start,
                replacement = "",
                head = utf8Length(closer.close),
                isPrimary = isPrimary,
            )
        }
        if (caret.isEmpty && opener != null && opener.autoClose &&
            closesWellHere(lineText, caret.headCol, opener, allowedBefore)
        ) {
            return@mapIndexed EditorState.CaretEdit(
                start = start,
                end = end,
                // Only the character just typed goes in; the rest of a
                // multi-character opener is already on the line.
                replacement = text + opener.close,
                head = utf8Length(text),
                isPrimary = isPrimary,
            )
        }
        EditorState.CaretEdit(start = start, end = end, replacement = text, isPrimary = isPrimary)
    }
    applyCaretEdits(edits)
}

private fun closesWellHere(
    text: String,
    col: Int,
    pair: BracketPair,
    allowedBefore: String,
): Boolean {
    val following = text.getOrNull(col)
    if (following != null && !following.isWhitespace() && following !in allowedBefore) return false
    // Zed's rule, and only for a pair that is its own closer: a quote right
    // after a word is an apostrophe or a suffix, not an opening quote, and one
    // right after its own kind is closing a string. `f"` is exempt by
    // construction — its halves differ — which is what lets the `f` in front
    // of it be a word character.
    if (!pair.isQuote) return true
    val preceding = text.getOrNull(col - 1) ?: return true
    return !preceding.isLetterOrDigit() && preceding != '_' && !text.startsWith(pair.open, col - 1)
}

/**
 * Enter, with the indent carried over: the new row starts at the current
 * row's indent, one level deeper after a pair or a pattern that opens a
 * block, and an opener whose closer is waiting on the far side of the caret
 * gets a row of its own with the closer pushed down below it.
 *
 * Both rules are the language's own. `newline` on a bracket pair is exactly
 * "an extra newline belongs between these two", which is `true` for every
 * bracket and `false` for every quote in Zed's configs — that flag is why
 * `x = "hello"` + Enter does not indent. `increase_indent_pattern` is the
 * rest: Python's trailing colon, a shell `do`/`then`, a YAML key with nothing
 * after it. Neither is spelled out here.
 *
 * The indent width is the `tab_size` setting; whether it is tabs or spaces
 * is [EditorState.indentUnit]'s question, because the settings file has no
 * hard-tabs key yet and the file in front of you is better evidence than the
 * language's `hard_tabs` would be.
 */
internal fun EditorState.insertNewline() {
    val config = languageConfig
    val primary = primaryCaret()
    val edits = caretsInOrder().map { caret ->
        val text = line(caret.startRow)
        val indentEnd = text.indexOfFirst { it != ' ' && it != '\t' }
            .let { if (it < 0) text.length else it }
        val indent = text.take(minOf(indentEnd, caret.startCol))
        val unit = indentUnit(indent)

        val before = text.take(caret.startCol).trimEnd()
        val opener = config.openerBefore(before)?.takeIf { it.newline }
        val after = line(caret.endRow).drop(caret.endCol).trimStart()
        val opensBlock = opener != null || config.opensBlock(before)
        // An opener whose closer is waiting on the other side of the caret
        // gets a line of its own, with the closer pushed down below it.
        val splitsPair = opener != null && after.startsWith(opener.close.trimStart())

        val replacement = when {
            splitsPair -> "\n$indent$unit\n$indent"
            opensBlock -> "\n$indent$unit"
            else -> "\n$indent"
        }
        val head = if (splitsPair) utf8Length("\n$indent$unit") else null
        EditorState.CaretEdit(
            start = byteOffsetOf(caret.startRow, caret.startCol),
            end = byteOffsetOf(caret.endRow, caret.endCol),
            replacement = replacement,
            head = head,
            isPrimary = caret == primary,
        )
    }
    applyCaretEdits(edits)
}

// ---- Motion the arrow keys cannot express ---------------------------------
//
// Zed binds all of these (assets/keymaps/default-linux.json), and without them
// a paired keyboard — which is how this app is used on DeX and on a foldable
// with a case — can only walk a file one character at a time. Each is
// multi-caret aware for the same reason the arrows are: a column of cursors
// that moves as one is the point of having it.

/** Where a caret lands, given a new row and column. */
private fun EditorState.moveCarets(extend: Boolean, place: (Caret) -> Pair<Int, Int>) {
    val primary = primaryCaret()
    fun moved(caret: Caret): Caret {
        val (row, col) = place(caret)
        return if (extend) {
            // Keep the anchor where it was, or plant one if there was none.
            val anchorRow = if (caret.isEmpty) caret.headRow else caret.anchorRow
            val anchorCol = if (caret.isEmpty) caret.headCol else caret.anchorCol
            Caret(anchorRow, anchorCol, row, col)
        } else {
            Caret(row, col)
        }
    }
    setCarets(caretsInOrder().map(::moved), moved(primary))
}

/**
 * Home, and Zed's "smart" version of it: the first press goes to the first
 * character that is not whitespace, the second to column zero. Landing on the
 * indent is what you want nine times in ten, and the plain column is one more
 * press away rather than gone.
 */
fun EditorState.moveToLineStart(extend: Boolean = false) = moveCarets(extend) { caret ->
    val text = line(caret.headRow)
    val firstText = text.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
    val col = if (caret.headCol == firstText) 0 else firstText
    caret.headRow to col
}

fun EditorState.moveToLineEnd(extend: Boolean = false) = moveCarets(extend) { caret ->
    caret.headRow to line(caret.headRow).length
}

fun EditorState.moveToDocumentStart(extend: Boolean = false) = moveCarets(extend) { 0 to 0 }

fun EditorState.moveToDocumentEnd(extend: Boolean = false) = moveCarets(extend) {
    val last = (lineCount - 1).coerceAtLeast(0)
    last to line(last).length
}

/**
 * A screenful, measured from the viewport rather than a constant: the whole
 * point of Page Down is that it moves by what you can see, and on a foldable
 * that is a different number folded and unfolded.
 */
fun EditorState.movePage(down: Boolean, extend: Boolean = false) {
    val rows = (viewportRows() - 1).coerceAtLeast(1)
    val delta = if (down) rows else -rows
    moveCarets(extend) { caret ->
        val row = (caret.headRow + delta).coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        row to caret.headCol.coerceAtMost(line(row).length)
    }
}

/**
 * Ctrl+arrow: to the far end of the run the caret is in — a word, a run of
 * punctuation, or a run of spaces — which is the rule every editor uses and
 * the one that makes the key predictable. Crossing a line boundary lands at
 * the neighbouring line's near end rather than skipping a whole word of it.
 */
fun EditorState.moveByWord(forward: Boolean, extend: Boolean = false) = moveCarets(extend) { caret ->
    val text = line(caret.headRow)
    var col = caret.headCol.coerceIn(0, text.length)
    if (forward) {
        if (col >= text.length) {
            val row = (caret.headRow + 1).coerceAtMost((lineCount - 1).coerceAtLeast(0))
            return@moveCarets if (row == caret.headRow) caret.headRow to col else row to 0
        }
        val kind = characterClass(text[col])
        while (col < text.length && characterClass(text[col]) == kind) col++
        while (col < text.length && text[col].isWhitespace()) col++
    } else {
        if (col <= 0) {
            val row = (caret.headRow - 1).coerceAtLeast(0)
            return@moveCarets if (row == caret.headRow) caret.headRow to 0 else row to line(row).length
        }
        while (col > 0 && text[col - 1].isWhitespace()) col--
        if (col > 0) {
            val kind = characterClass(text[col - 1])
            while (col > 0 && characterClass(text[col - 1]) == kind) col--
        }
    }
    caret.headRow to col
}

/** Word / punctuation / whitespace — the three runs a word motion stops at. */
private fun characterClass(char: Char): Int = when {
    char.isLetterOrDigit() || char == '_' -> 0
    char.isWhitespace() -> 1
    else -> 2
}
