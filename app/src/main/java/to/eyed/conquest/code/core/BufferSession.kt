package to.eyed.conquest.code.core

/**
 * State of one open engine buffer, as seen from the UI layer.
 *
 * Bridges the placeholder text-field editor (whole-string in, whole-string
 * out) to the engine's incremental API: each [update] diffs the new text
 * against the engine's current text and sends only the changed byte range
 * through [CoreBridge.applyEdit]. Reads come back through the line-window
 * API ([CoreBridge.bufferLines]) — the whole-buffer read only because the
 * interim editor widget displays the whole buffer at once.
 */
class BufferSession(initialText: String) {
    val id: Long = CoreBridge.createBuffer(initialText)

    var text: String = readAll()
        private set

    var version: Long = CoreBridge.bufferVersion(id)
        private set

    val lineCount: Int
        get() = CoreBridge.bufferLineCount(id).toInt().coerceAtLeast(1)

    /**
     * Diff [newText] against the engine's content and apply the minimal
     * single-range replacement. Returns true if the engine accepted the edit
     * (or there was nothing to change).
     */
    fun update(newText: String): Boolean {
        val oldBytes = text.encodeToByteArray()
        val newBytes = newText.encodeToByteArray()

        var prefix = 0
        val maxPrefix = minOf(oldBytes.size, newBytes.size)
        while (prefix < maxPrefix && oldBytes[prefix] == newBytes[prefix]) prefix++
        // Never split a UTF-8 code point: back off over continuation bytes.
        while (prefix > 0 && prefix < newBytes.size && isContinuation(newBytes[prefix])) prefix--

        var suffix = 0
        val maxSuffix = minOf(oldBytes.size, newBytes.size) - prefix
        while (suffix < maxSuffix &&
            oldBytes[oldBytes.size - 1 - suffix] == newBytes[newBytes.size - 1 - suffix]
        ) suffix++
        while (suffix > 0 && isContinuation(newBytes[newBytes.size - suffix])) suffix--

        if (prefix == oldBytes.size && prefix == newBytes.size) return true

        val replacement = newBytes.decodeToString(prefix, newBytes.size - suffix)
        val newVersion = CoreBridge.applyEdit(
            id,
            prefix.toLong(),
            (oldBytes.size - suffix).toLong(),
            replacement,
        )
        if (newVersion < 0) return false
        version = newVersion
        text = readAll()
        return true
    }

    fun undo(): Boolean = applyHistory(CoreBridge.undoBuffer(id))

    fun redo(): Boolean = applyHistory(CoreBridge.redoBuffer(id))

    fun close(): Boolean = CoreBridge.closeBuffer(id)

    private fun applyHistory(newVersion: Long): Boolean {
        if (newVersion < 0) return false
        version = newVersion
        text = readAll()
        return true
    }

    private fun readAll(): String =
        CoreBridge.bufferLines(id, 0, CoreBridge.bufferLineCount(id)).orEmpty()

    private fun isContinuation(byte: Byte): Boolean = (byte.toInt() and 0xC0) == 0x80
}
