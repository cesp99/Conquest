package to.eyed.conquest.code.ui.editor

/**
 * An [EditorBuffer] backed by a string, with the engine's arithmetic rather
 * than a convenient approximation of it: offsets are UTF-8 bytes, and an
 * edit whose ends are not on code-point boundaries — or past the end of the
 * buffer — is *refused*, which is what `Engine::edit` does with
 * `InvalidRange`.
 *
 * Refusing matters as much as editing: a rejected edit is how a caret ends
 * up at an offset the buffer never had, and the batch machinery has to
 * notice.
 */
internal class FakeEditorBuffer(
    text: String,
    override val language: String? = null,
    /**
     * The language config the engine would hand over, verbatim. Tests that
     * care about a language's rules paste the real thing; the rest pass none
     * and get the empty config, which closes nothing and comments nothing.
     */
    override val languageConfigJson: String? = null,
) : EditorBuffer {

    var text: String = text
        private set

    /**
     * The scopes [bracketScopes] reports, as byte-offset ranges in which
     * *no* pair is live — a comment or a string, as far as the caret is
     * concerned. Empty means everything is live everywhere, which is what the
     * engine answers for a buffer with no language.
     *
     * The real answer comes from the syntax tree and is tested in Rust
     * (`engine::tests::bracket_scopes_follow_the_syntax_tree`); what these
     * tests need from it is only that the editor obeys it.
     */
    val deadZones = mutableListOf<LongRange>()

    /** How many times the editor went to the engine for a scope. */
    var scopeQueries = 0
        private set

    override var version: Long = 1L
        private set

    override val highlightVersion: Long get() = 0L

    /** Edits the buffer refused, in the order it refused them. */
    val refusedEdits = mutableListOf<Triple<Long, Long, String>>()

    private val rows: List<String> get() = text.split('\n')

    override val lineCount: Int get() = rows.size

    override fun lines(firstRow: Int, lastRow: Int): String {
        val all = rows
        val first = firstRow.coerceIn(0, all.size)
        val last = lastRow.coerceIn(first, all.size)
        return all.subList(first, last).joinToString("\n")
    }

    override fun highlights(firstRow: Int, lastRow: Int): IntArray? = IntArray(0)

    override fun bracketScopes(offsets: LongArray): LongArray {
        scopeQueries++
        return LongArray(offsets.size) { index ->
            if (deadZones.any { offsets[index] in it }) 0L else -1L
        }
    }

    override fun rowStart(row: Int): Long {
        val all = rows
        var offset = 0
        for (i in 0 until row.coerceIn(0, all.size - 1)) {
            offset += utf8(all[i]).size + 1
        }
        return offset.toLong()
    }

    override fun pointOf(offset: Long): Long {
        val bytes = utf8(text)
        var at = offset.coerceIn(0, bytes.size.toLong()).toInt()
        // `at == bytes.size` is the end of the buffer and a perfectly ordinary
        // answer — it is where the caret sits after appending to the last line
        // — so the walk back to a character boundary must not read there. The
        // engine clips the same way, with `Bias::Left`.
        while (at in 1 until bytes.size && isContinuation(bytes[at])) at--
        var row = 0L
        var rowStart = 0
        for (i in 0 until at) {
            if (bytes[i] == '\n'.code.toByte()) {
                row++
                rowStart = i + 1
            }
        }
        return (row shl 32) or (at - rowStart).toLong()
    }

    override fun edit(start: Long, end: Long, replacement: String): Boolean {
        val bytes = utf8(text)
        if (start < 0 || end < start || end > bytes.size) {
            refusedEdits.add(Triple(start, end, replacement))
            return false
        }
        val from = start.toInt()
        val to = end.toInt()
        if (isContinuation(bytes, from) || isContinuation(bytes, to)) {
            refusedEdits.add(Triple(start, end, replacement))
            return false
        }
        text = String(bytes, 0, from, Charsets.UTF_8) +
            replacement +
            String(bytes, to, bytes.size - to, Charsets.UTF_8)
        version++
        return true
    }

    override fun undo(): Boolean = false

    override fun redo(): Boolean = false

    private fun utf8(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    private fun isContinuation(bytes: ByteArray, at: Int): Boolean =
        at < bytes.size && isContinuation(bytes[at])

    private fun isContinuation(byte: Byte): Boolean = (byte.toInt() and 0xC0) == 0x80
}
