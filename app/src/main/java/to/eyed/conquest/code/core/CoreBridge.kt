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
     * Whole buffer contents. Placeholder-era convenience for the interim
     * text-field editor; real rendering must use [bufferLines].
     */
    external fun bufferText(bufferId: Long): String?
}
