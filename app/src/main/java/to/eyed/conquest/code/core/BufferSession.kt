package to.eyed.conquest.code.core

/**
 * Handle for one open engine buffer. Thin: raw byte-range edits, undo/redo
 * and version tracking. Reading is done by callers through the line-window
 * API ([CoreBridge.bufferLines]) — the UI layer never holds the whole
 * buffer.
 */
class BufferSession(initialText: String) {
    val id: Long = CoreBridge.createBuffer(initialText)

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

    /** Undo the last edit transaction. Returns false if nothing to undo. */
    fun undo(): Boolean = applyHistory(CoreBridge.undoBuffer(id))

    /** Redo the last undone transaction. Returns false if nothing to redo. */
    fun redo(): Boolean = applyHistory(CoreBridge.redoBuffer(id))

    fun close(): Boolean = CoreBridge.closeBuffer(id)

    private fun applyHistory(newVersion: Long): Boolean {
        if (newVersion < 0) return false
        version = newVersion
        return true
    }
}
