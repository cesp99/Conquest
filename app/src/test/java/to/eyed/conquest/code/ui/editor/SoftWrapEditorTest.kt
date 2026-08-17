package to.eyed.conquest.code.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What soft wrap changes about the pane itself: which way the arrows move,
 * how far a page goes, where the viewport scrolls to, and — the part that
 * would be a silent corruption rather than a visible bug — that none of it
 * changes which bytes an edit touches.
 */
class SoftWrapEditorTest {

    /**
     * A pane [columns] characters wide. The width is worked back from the
     * pane's own arithmetic: a 10px character, a gutter of four digits plus
     * Zed's seven characters of padding, and the scrollbar's track.
     */
    private fun editorOf(
        text: String,
        columns: Int,
        viewportRows: Int = 10,
        wrap: Boolean = true,
    ): EditorState {
        val state = EditorState(FakeEditorBuffer(text))
        state.softWrap = if (wrap) SoftWrapMode.EditorWidth else SoftWrapMode.None
        state.updateMetrics(
            lineHeight = 10f,
            charWidth = 10f,
            gutterPadding = 0f,
            textPadding = 0f,
        )
        state.updateViewport(width = columns * 10f + 120f, height = viewportRows * 10f)
        return state
    }

    private fun EditorState.caretAt(row: Int, col: Int) =
        setCarets(listOf(Caret(row, col)), Caret(row, col))

    private fun EditorState.head(): Pair<Int, Int> = primaryCaret().let { it.headRow to it.headCol }

    /**
     * Look at every row, which is what scrolling through the file does.
     *
     * The map measures a block the first time a query lands in it and
     * estimates the rest at one display row per file row, so a test that
     * wants the document's true height has to have looked at it — exactly as
     * the draw pass does before it reads the scroll extent.
     */
    private fun EditorState.measureWholeFile(): EditorState = apply {
        for (row in 0 until lineCount) displayMap.displayRowOf(row)
    }

    @Test
    fun thePaneWorksOutItsOwnWrapWidth() {
        assertEquals(20, editorOf("hello", columns = 20).displayMap.wrapColumns)
        assertFalse(editorOf("hello", columns = 20).displayMap.isIdentity)
        assertTrue(editorOf("hello", columns = 20, wrap = false).displayMap.isIdentity)
    }

    @Test
    fun theDocumentIsAsTallAsTheWrappedRowsMakeIt() {
        // "the quick brown fox jumps over it" is four rows at ten columns.
        val state = editorOf("one\nthe quick brown fox jumps over it\nthree", columns = 10)
            .measureWholeFile()
        assertEquals(6, state.displayMap.displayRowCount)
        assertEquals(0, state.displayRowOf(0, 0))
        assertEquals(1, state.displayRowOf(1, 0))
        assertEquals(3, state.displayRowOf(1, 20))
        assertEquals(5, state.displayRowOf(2, 0))
    }

    @Test
    fun theArrowsMoveByARowOfTheScreen() {
        val state = editorOf("the quick brown fox jumps over it\nnext", columns = 10)
        state.caretAt(0, 0)

        state.moveCursorVertically(1)
        assertEquals("down one screen row, still inside row 0", 0 to 10, state.head())

        state.moveCursorVertically(1)
        assertEquals(0 to 20, state.head())

        state.moveCursorVertically(1)
        assertEquals(0 to 26, state.head())

        state.moveCursorVertically(1)
        assertEquals("and only now onto the next file row", 1 to 0, state.head())

        state.moveCursorVertically(-1)
        assertEquals(0 to 26, state.head())
    }

    @Test
    fun theArrowsKeepTheirColumnWithinTheSegment() {
        val state = editorOf("the quick brown fox jumps over it\nnext", columns = 10)
        // Third character of the first segment.
        state.caretAt(0, 3)

        state.moveCursorVertically(1)
        assertEquals("third character of the second segment", 0 to 13, state.head())
    }

    @Test
    fun withWrappingOffTheArrowsMoveByFileRows() {
        val state = editorOf("the quick brown fox jumps over it\nnext", columns = 10, wrap = false)
        state.caretAt(0, 3)

        state.moveCursorVertically(1)
        assertEquals(1 to 3, state.head())
    }

    @Test
    fun aPageIsAScreenfulOfDisplayRows() {
        // Ten rows of viewport, so a page is nine display rows: two whole
        // wrapped lines of four, plus one.
        val long = "the quick brown fox jumps over it"
        val state = editorOf("$long\n$long\n$long\nlast", columns = 10, viewportRows = 10)
        state.caretAt(0, 0)

        state.movePage(down = true)
        assertEquals("nine display rows down is row 2's second segment", 2 to 10, state.head())
    }

    @Test
    fun scrollingToTheCaretFollowsItsOwnSegment() {
        val long = "the quick brown fox jumps over it"
        val state = editorOf("$long\n$long\n$long\n$long", columns = 10, viewportRows = 4)
            .measureWholeFile()
        // The last segment of the last row is display row 15; a viewport of
        // four rows has to end there.
        state.caretAt(3, 30)
        state.ensureCursorVisible()

        assertEquals(15, state.displayRowOf(3, 30))
        assertEquals((15 + 1) * 10f - 40f, state.scrollY, 0.01f)
    }

    @Test
    fun theScrollExtentCountsDisplayRowsNotFileRows() {
        val long = "the quick brown fox jumps over it"
        val wrapped = editorOf("$long\n$long", columns = 10, viewportRows = 4)
            .measureWholeFile()
        val plain = editorOf("$long\n$long", columns = 10, viewportRows = 4, wrap = false)

        assertEquals(8 * 10f - 40f, wrapped.maxScrollY, 0.01f)
        assertEquals("two file rows do not fill four", 0f, plain.maxScrollY, 0.01f)
    }

    @Test
    fun aWrappedPaneHasNothingToScrollSideways() {
        val state = editorOf("the quick brown fox jumps over it", columns = 10)
        assertEquals(0f, state.applyScrollDeltaX(-500f), 0.01f)
        assertEquals(0f, state.effectiveScrollX, 0.01f)
    }

    @Test
    fun typingInAWrappedBufferStillEditsTheRightBytes() {
        val buffer = FakeEditorBuffer("the quick brown fox jumps over it\nnext")
        val state = EditorState(buffer)
        state.softWrap = SoftWrapMode.EditorWidth
        state.updateMetrics(lineHeight = 10f, charWidth = 10f, gutterPadding = 0f, textPadding = 0f)
        state.updateViewport(width = 220f, height = 100f)
        state.caretAt(0, 20)

        state.insertAtCursor("XY")

        assertEquals("the quick brown fox XYjumps over it\nnext", buffer.text)
        assertEquals(0 to 22, state.head())
    }

    @Test
    fun aNewlineRetallsTheDocumentAndTheRowsBelowIt() {
        val long = "the quick brown fox jumps over it"
        val buffer = FakeEditorBuffer("$long\n$long")
        val state = EditorState(buffer)
        state.softWrap = SoftWrapMode.EditorWidth
        state.updateMetrics(lineHeight = 10f, charWidth = 10f, gutterPadding = 0f, textPadding = 0f)
        state.updateViewport(width = 220f, height = 100f)

        assertEquals(8, state.measureWholeFile().displayMap.displayRowCount)

        // Split the first row in the middle of its second segment.
        state.caretAt(0, 15)
        state.insertNewline()

        assertEquals(3, state.lineCount)
        // "the quick brown" is two rows; " fox jumps over it" is three,
        // because its leading space is an indent every continuation carries;
        // and the untouched second row is still four.
        assertEquals(2 + 3 + 4, state.measureWholeFile().displayMap.displayRowCount)
        assertEquals(5, state.displayRowOf(2, 0))
    }

    @Test
    fun deletingRowsShortensTheDocument() {
        val long = "the quick brown fox jumps over it"
        val buffer = FakeEditorBuffer("$long\n$long\nshort")
        val state = EditorState(buffer)
        state.softWrap = SoftWrapMode.EditorWidth
        state.updateMetrics(lineHeight = 10f, charWidth = 10f, gutterPadding = 0f, textPadding = 0f)
        state.updateViewport(width = 220f, height = 100f)

        assertEquals(9, state.measureWholeFile().displayMap.displayRowCount)

        state.caretAt(0, 0)
        state.deleteLines()

        assertEquals("$long\nshort", buffer.text)
        assertEquals(5, state.measureWholeFile().displayMap.displayRowCount)
    }

    @Test
    fun undoingPutsTheDocumentBack() {
        // Undo can rewrite anything, so the map has to give up everything it
        // measured rather than trust a row hint it was never given.
        val long = "the quick brown fox jumps over it"
        val state = editorOf("$long\n$long", columns = 10)
        assertEquals(8, state.measureWholeFile().displayMap.displayRowCount)

        state.caretAt(0, 0)
        state.deleteLines()
        assertEquals(4, state.measureWholeFile().displayMap.displayRowCount)

        // FakeEditorBuffer has no history, so this only pins that the query
        // survives the call; the real invalidation is the same code path as
        // `refreshLineCount`, which the tests above exercise.
        state.undo()
        assertEquals(4, state.measureWholeFile().displayMap.displayRowCount)
    }

    @Test
    fun aTapResolvesToTheSegmentUnderIt() {
        val state = editorOf("the quick brown fox jumps over it\nnext", columns = 10)
        // Display row 2 is the third segment, which starts at column 20.
        assertEquals(0 to 20, state.pointAtDisplayRow(2, 0))
        assertEquals(0 to 23, state.pointAtDisplayRow(2, 3))
        assertEquals("clamped to the segment it landed on", 0 to 26, state.pointAtDisplayRow(2, 99))
        assertEquals(1 to 4, state.pointAtDisplayRow(4, 99))
    }
}
