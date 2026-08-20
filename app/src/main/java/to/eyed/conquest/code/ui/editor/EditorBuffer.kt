package to.eyed.conquest.code.ui.editor

import to.eyed.conquest.code.core.BufferSession
import to.eyed.conquest.code.core.CoreBridge

/**
 * Everything the editor asks of the buffer it is editing.
 *
 * There is exactly one implementation in the app — [SessionBuffer], a
 * straight forward of one JNI call per method. The interface exists so the
 * caret arithmetic can be run on the host against an in-memory buffer: the
 * offsets it computes *are* the user's file, an edit at the wrong byte
 * corrupts it silently, and a test that only runs on a device with the
 * engine behind it is a test nobody runs.
 *
 * Rows are 0-based; offsets are UTF-8 bytes, as everywhere the engine is
 * involved.
 */
internal interface EditorBuffer {
    val version: Long
    val highlightVersion: Long
    val lineCount: Int
    val language: String?

    /**
     * The language's editing rules as JSON, for
     * [EditorLanguage.configFor] to parse. Asked at most once per grammar —
     * the answer is the same for every buffer in it.
     */
    val languageConfigJson: String?

    /** Text of rows [firstRow, lastRow), joined by '\n', clipped. */
    fun lines(firstRow: Int, lastRow: Int): String

    /**
     * Per offset, a bitmask of the bracket pairs live there (bit *i* for pair
     * *i* of the language's `brackets`). Answers the `not_in` scopes, which
     * need the syntax tree — see [to.eyed.conquest.code.core.CoreBridge.bufferBracketScopes].
     */
    fun bracketScopes(offsets: LongArray): LongArray

    /** Flat [row, start, end, style] highlight groups for the same range. */
    fun highlights(firstRow: Int, lastRow: Int): IntArray?

    /** Byte offset of the start of [row]. */
    fun rowStart(row: Int): Long

    /** (row, byte column) of [offset], packed as `(row shl 32) or column`. */
    fun pointOf(offset: Long): Long

    /**
     * Replace the byte range [start, end) with [replacement]. Returns the
     * buffer version this edit produced — the engine bumps the version by
     * exactly one per edit, under the buffer's lock, which is what lets
     * [EditorState.applyLineDiff]'s in-place window patch tell a lone edit
     * (returned == checked version + 1) from one that a concurrent writer
     * slipped in ahead of. Returns -1 when the engine refused the edit —
     * an offset off a code-point boundary or past the end of the buffer —
     * in which case nothing changed.
     */
    fun edit(start: Long, end: Long, replacement: String): Long

    fun undo(): Boolean

    fun redo(): Boolean
}

/** The real thing: one open engine buffer, over the JNI bridge. */
internal class SessionBuffer(private val session: BufferSession) : EditorBuffer {
    override val version: Long get() = session.version
    override val highlightVersion: Long get() = session.highlightVersion
    override val lineCount: Int get() = session.lineCount
    override val language: String? get() = session.language

    override val languageConfigJson: String?
        get() = session.language?.let(CoreBridge::languageConfig)

    override fun lines(firstRow: Int, lastRow: Int): String =
        CoreBridge.bufferLines(session.id, firstRow.toLong(), lastRow.toLong()).orEmpty()

    override fun bracketScopes(offsets: LongArray): LongArray =
        CoreBridge.bufferBracketScopes(session.id, offsets)

    override fun highlights(firstRow: Int, lastRow: Int): IntArray? =
        CoreBridge.bufferHighlights(session.id, firstRow.toLong(), lastRow.toLong())

    override fun rowStart(row: Int): Long = CoreBridge.pointToOffset(session.id, row.toLong(), 0)

    override fun pointOf(offset: Long): Long = CoreBridge.offsetToPoint(session.id, offset)

    override fun edit(start: Long, end: Long, replacement: String): Long =
        session.editBytes(start, end, replacement)

    override fun undo(): Boolean = session.undo()

    override fun redo(): Boolean = session.redo()
}
