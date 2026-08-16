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

    /**
     * Hands the engine the app's private files directory and brings it up.
     * Call this before anything else touches the bridge.
     *
     * Android runs apps without `$HOME`, which the vendored Zed crates assume
     * exists — a worktree scan panics without one. The engine points `HOME`
     * (and the trash) at this directory.
     */
    external fun initialize(filesDir: String)

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

    // -----------------------------------------------------------------------
    // Projects. Opening and expanding are asynchronous: they hand work to the
    // engine's gpui runtime and return at once. Watch [projectVersion] to know
    // when there is something new to read; every other call reads a mirrored
    // snapshot and never waits on the runtime.
    // -----------------------------------------------------------------------

    /** Starts scanning [path] as a project. Returns its id (always > 0). */
    external fun openProject(path: String): Long

    external fun closeProject(projectId: Long): Boolean

    /**
     * Monotonic version of the mirrored worktree snapshot; 0 while there is
     * nothing to show. Poll it to know when to re-read entries.
     */
    external fun projectVersion(projectId: Long): Long

    /** Whether the initial scan has finished. Entries are readable before it. */
    external fun projectScanComplete(projectId: Long): Boolean

    /** Why the project failed to open, or null if it did not fail. */
    external fun projectError(projectId: Long): String?

    /** Display name of the project root; null for an unknown project. */
    external fun projectRootName(projectId: Long): String?

    /**
     * Direct children of a project-relative directory ("" for the root), as a
     * JSON array of objects with `path`, `name`, `is_dir`, `is_ignored`,
     * `is_hidden`, `is_unloaded` and `size`. One call per expanded directory —
     * never one per entry. Unknown projects and unscanned directories give
     * `[]`, never null.
     */
    external fun projectEntries(projectId: Long, dir: String): String

    /**
     * Scans a directory the worktree deferred (gitignored, hidden, or past
     * Zed's `file_scan_depth`). Asynchronous: results show up as a version
     * bump. False if the project or path is unknown.
     */
    external fun expandDirectory(projectId: Long, dir: String): Boolean

    /**
     * Absolute path of a project-relative entry; null if the project is
     * unknown or the path tries to escape the root.
     */
    external fun projectEntryPath(projectId: Long, path: String): String?

    /**
     * Reads a file into a new buffer, choosing the language from its name.
     * Returns the buffer id, or -1 if the file could not be read. **Blocking**
     * — call it off the main thread.
     */
    external fun openFile(path: String): Long
}
