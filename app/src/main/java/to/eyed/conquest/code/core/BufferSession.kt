package to.eyed.conquest.code.core

/**
 * Handle for one open engine buffer. Thin: raw byte-range edits, undo/redo
 * and version tracking. Reading is done by callers through the line-window
 * API ([CoreBridge.bufferLines]) — the UI layer never holds the whole
 * buffer.
 */
class BufferSession private constructor(val id: Long) {
    constructor(initialText: String) : this(CoreBridge.createBuffer(initialText))

    var version: Long = CoreBridge.bufferVersion(id)
        private set

    val lineCount: Int
        get() = CoreBridge.bufferLineCount(id).toInt().coerceAtLeast(1)

    /**
     * Replace the byte range [start, end) with [replacement]. Offsets must
     * lie on UTF-8 code-point boundaries. Returns false if the engine
     * rejected the edit.
     */
    fun editBytes(start: Long, end: Long, replacement: String): Boolean {
        val newVersion = CoreBridge.applyEdit(id, start, end, replacement)
        if (newVersion < 0) return false
        version = newVersion
        return true
    }

    /**
     * Assign a tree-sitter language (grammar name, e.g. "rust"). Returns
     * false for unknown language names.
     */
    fun setLanguage(language: String): Boolean = CoreBridge.bufferSetLanguage(id, language)

    /** Grammar name for the status bar; null when no language is assigned. */
    val language: String?
        get() = CoreBridge.bufferLanguage(id)

    /** Absolute path of the backing file; null for scratch buffers. */
    val path: String?
        get() = CoreBridge.bufferPath(id)

    /** Edits not yet written to disk. Always false without a backing file. */
    val isDirty: Boolean
        get() = CoreBridge.bufferIsDirty(id)

    /**
     * The file changed on disk since this buffer last synced with it, as
     * reported by the engine's file watcher. Cleared by [save] or [reload].
     */
    val hasDiskChange: Boolean
        get() = CoreBridge.bufferHasDiskChange(id)

    /** The backing file has been deleted from disk. */
    val isFileDeleted: Boolean
        get() = CoreBridge.bufferFileDeleted(id)

    /**
     * Write to the backing file. Returns false if there is none or the write
     * failed. **Blocking** — call from [kotlinx.coroutines.Dispatchers.IO].
     */
    fun save(): Boolean = applyVersion(CoreBridge.saveBuffer(id))

    /**
     * Re-read the backing file, discarding local edits (undoably). Returns
     * false on failure. **Blocking** — call from
     * [kotlinx.coroutines.Dispatchers.IO].
     */
    fun reload(): Boolean = applyVersion(CoreBridge.reloadBuffer(id))

    /** Undo the last edit transaction. Returns false if nothing to undo. */
    fun undo(): Boolean = applyVersion(CoreBridge.undoBuffer(id))

    /** Redo the last undone transaction. Returns false if nothing to redo. */
    fun redo(): Boolean = applyVersion(CoreBridge.redoBuffer(id))

    fun close(): Boolean = CoreBridge.closeBuffer(id)

    /** Adopt a version the engine returned, or report the -1 failure. */
    private fun applyVersion(newVersion: Long): Boolean {
        if (newVersion < 0) return false
        version = newVersion
        return true
    }

    companion object {
        /**
         * Read a file from disk into a new engine buffer, with the language
         * chosen from its name. Returns null if it could not be read.
         *
         * **Blocking** (file I/O in the engine) — call it off the main thread.
         */
        fun openFile(absolutePath: String): BufferSession? {
            val id = CoreBridge.openFile(absolutePath)
            return if (id < 0) null else BufferSession(id)
        }
    }
}
