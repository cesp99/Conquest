package to.eyed.conquest.code.core

/**
 * Kotlin side of the JNI boundary to the Rust engine (`core/crates/jni-bridge`).
 *
 * Naming contract: each `external` function here maps to a
 * `Java_to_eyed_conquest_code_core_CoreBridge_<name>` symbol in the Rust
 * crate. Keep the two files in sync — this is the only place the two worlds
 * meet.
 *
 * Calls across this boundary must stay coarse-grained: never loop over
 * per-character calls from Kotlin.
 *
 * Error convention: functions returning [Long] return -1 for unknown
 * buffers / invalid arguments (and, for undo/redo, when there is nothing to
 * undo/redo). Functions returning [String]? return null for unknown buffers.
 */
object CoreBridge {
    init {
        System.loadLibrary("conquestcore")
    }

    external fun engineVersion(): String

    /** Returns the id of the newly created buffer. */
    external fun createBuffer(initialText: String): Long

    external fun closeBuffer(bufferId: Long): Boolean

    /**
     * Replaces the byte range [start, end) with [text]. Offsets are UTF-8
     * byte offsets and must lie on character boundaries. Returns the new
     * buffer version, or -1 on invalid buffer id or range.
     */
    external fun applyEdit(bufferId: Long, start: Long, end: Long, text: String): Long

    /**
     * Undoes the most recent edit transaction. Returns the new buffer
     * version, or -1 if there was nothing to undo.
     */
    external fun undoBuffer(bufferId: Long): Long

    /**
     * Redoes the most recently undone transaction. Returns the new buffer
     * version, or -1 if there was nothing to redo.
     */
    external fun redoBuffer(bufferId: Long): Long

    /**
     * Monotonic content version, bumped by every edit/undo/redo. Cheap
     * staleness check for cached reads.
     */
    external fun bufferVersion(bufferId: Long): Long

    external fun bufferLineCount(bufferId: Long): Long

    /**
     * Text of rows [firstLine, lastLine) — end-exclusive, clipped to the
     * buffer — joined with '\n' and without a trailing newline. This is the
     * read path the editor should use: fetch only the visible window.
     */
    external fun bufferLines(bufferId: Long, firstLine: Long, lastLine: Long): String?

    /**
     * Assigns a tree-sitter language (grammar name, e.g. "rust") to the
     * buffer and parses it. Returns false for unknown buffers/languages.
     */
    external fun bufferSetLanguage(bufferId: Long, language: String): Boolean

    /**
     * Highlight spans for rows [firstLine, lastLine), flattened as groups
     * of four ints: row, UTF-16 start column, UTF-16 end column, style id
     * (index into the engine's style-name list, mirrored by
     * [to.eyed.conquest.code.ui.editor.SyntaxPalette]). Empty when the
     * buffer has no language; null for unknown buffers.
     */
    external fun bufferHighlights(bufferId: Long, firstLine: Long, lastLine: Long): IntArray?

    /**
     * Byte offset of (row, byte column), clipped to the buffer. -1 for an
     * unknown buffer or negative arguments.
     */
    external fun pointToOffset(bufferId: Long, row: Long, column: Long): Long

    /**
     * (row, byte column) of a byte offset, clipped to the buffer, packed as
     * `(row shl 32) or column`. -1 for an unknown buffer or negative offset.
     */
    external fun offsetToPoint(bufferId: Long, offset: Long): Long

    /**
     * Whole buffer contents. Placeholder-era convenience; real rendering
     * must use [bufferLines].
     */
    external fun bufferText(bufferId: Long): String?
}
