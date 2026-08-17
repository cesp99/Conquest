package to.eyed.conquest.code.ui.preview

import to.eyed.conquest.code.core.BufferSession
import to.eyed.conquest.code.core.CoreBridge
import to.eyed.conquest.code.ui.editor.HighlightSpan

/**
 * Syntax colouring for the preview's fenced code blocks, through the engine we
 * already have rather than a second highlighter written in Kotlin.
 *
 * The bridge has no "highlight this string as Rust" call and this wave does not
 * hold the JNI pair, so the fence is highlighted the way any other text is: a
 * scratch engine buffer is created, given the fence's language, asked for its
 * spans, and closed. That is three existing calls and no new surface. It costs
 * one tree-sitter parse per distinct fence, which is why the answers are cached
 * and why [MAX_HIGHLIGHTED_BYTES] exists — a 200 KB code block in a README is
 * not worth a parse, and it is not worth the memory of its spans either.
 *
 * **Everything here blocks and takes the engine's buffer mutex. Call it off the
 * main thread**, which is what [MarkdownPreview]'s parse coroutine does.
 */
internal object CodeFenceHighlighter {

    /** Past this a fence is treated as plain text; see the class comment. */
    private const val MAX_HIGHLIGHTED_BYTES = 64 * 1024

    /** Distinct fences remembered. A README has a handful; this is generous. */
    private const val CACHE_LIMIT = 64

    private data class Key(val grammar: String, val code: String)

    private val cache = object : LinkedHashMap<Key, List<List<HighlightSpan>>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Key, List<List<HighlightSpan>>>,
        ): Boolean = size > CACHE_LIMIT
    }

    /**
     * Per-line spans for [code] as [language], or null when we cannot colour
     * it — no info string, a language we carry no grammar for, or a fence too
     * large to be worth parsing. Null means "draw it plain", never an error.
     */
    fun highlight(code: String, language: String?): List<List<HighlightSpan>>? {
        val grammar = grammarFor(language) ?: return null
        if (code.length > MAX_HIGHLIGHTED_BYTES) return null
        val key = Key(grammar, code)
        synchronized(cache) { cache[key] }?.let { return it }

        val session = BufferSession(code)
        val spans = try {
            // `setLanguage` parses on the calling thread, so the spans below
            // are the real ones rather than the empty set a background reparse
            // would still be on its way to producing.
            if (!session.setLanguage(grammar)) return null
            val rows = code.count { it == '\n' } + 1
            groupSpans(CoreBridge.bufferHighlights(session.id, 0, rows.toLong()), rows)
        } finally {
            // The engine holds every buffer until it is told not to; a preview
            // that reparses on every keystroke would leak one per fence per
            // keystroke without this.
            session.close()
        }
        synchronized(cache) { cache[key] = spans }
        return spans
    }

    /** Flat [row, start, end, style] groups → one list per row. */
    private fun groupSpans(flat: IntArray?, rows: Int): List<List<HighlightSpan>> {
        val grouped = List(rows) { mutableListOf<HighlightSpan>() }
        if (flat == null) return grouped
        var index = 0
        while (index + 3 < flat.size) {
            val row = flat[index]
            if (row in 0 until rows) {
                grouped[row].add(HighlightSpan(flat[index + 1], flat[index + 2], flat[index + 3]))
            }
            index += 4
        }
        return grouped
    }
}

/**
 * The grammar name behind a fence's info string.
 *
 * The engine names its grammars after the directories they are vendored in
 * (`bash`, `tsx`, `python`); a fence is written the way people write them.
 * Anything not in this table is passed through unchanged and simply refused by
 * `setLanguage` if we do not carry it, so a new grammar needs no entry here to
 * work under its own name.
 */
internal fun grammarFor(language: String?): String? {
    // An info string is not always only a language: `rust,ignore` and
    // `js{1,3}` are both ordinary in a README, and the language is the run of
    // name characters at the front of it.
    val name = language?.trim()?.lowercase()
        ?.takeWhile { it.isLetterOrDigit() || it == '+' || it == '#' || it == '-' }
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return when (name) {
        "sh", "shell", "zsh", "console", "bash" -> "bash"
        "js", "jsx", "javascript", "mjs", "cjs", "tsx" -> "tsx"
        "ts", "typescript" -> "typescript"
        "py", "python", "python3" -> "python"
        "rs", "rust" -> "rust"
        "yml", "yaml" -> "yaml"
        "md", "markdown" -> "markdown"
        "c++", "cc", "cxx", "hpp", "cpp" -> "cpp"
        "h", "c" -> "c"
        "golang", "go" -> "go"
        "patch", "diff" -> "diff"
        "json5", "jsonc" -> "jsonc"
        else -> name
    }
}
