package to.eyed.conquest.code.ui.editor

/**
 * A pair the editor closes for you, and wraps a selection in.
 *
 * [close] is Zed's flag of the same name: `<` opens a generic in Rust but is
 * far too often a comparison to close automatically, so it may still surround
 * a selection while never auto-closing on its own.
 */
internal data class BracketPair(
    val open: String,
    val close: String,
    val autoClose: Boolean = true,
) {
    /** Quotes are their own closer, which changes how they may be typed. */
    val isQuote: Boolean get() = open == close
}

/**
 * The per-language editing tokens: the line-comment prefix, the pairs that
 * auto-close, and the characters an opener may be closed in front of.
 *
 * **This table is a stopgap and should not grow.** The engine already carries
 * every value in it — `line_comments`, `brackets` and `autoclose_before` in
 * the vendored `grammars/src/<language>/config.toml` files that
 * `engine::highlight` loads to build its registry — so the right home is a
 * JNI accessor that hands the buffer's language config to the UI, and this
 * object then becomes a deletion rather than a rewrite. The values below are
 * copied from those configs verbatim for exactly that reason.
 *
 * Keys are grammar names as `BufferSession.language` reports them, which is
 * the engine's registry name: a `.js` file parses with the `tsx` grammar and
 * therefore answers "tsx", not "javascript".
 */
internal object EditorLanguage {

    /**
     * The line-comment prefix, trailing space included the way Zed writes it
     * in `line_comments`. Null for languages that have no line comment —
     * CSS, Markdown and diffs toggle nothing rather than corrupting the file.
     */
    fun lineComment(language: String?): String? = when (language) {
        "rust", "c", "cpp", "go", "gomod", "gowork", "json", "jsonc",
        "tsx", "typescript", "javascript", "jsdoc" -> "// "
        "bash", "python", "yaml", "gitcommit" -> "# "
        else -> null
    }

    /**
     * Characters an opener is allowed to auto-close in front of, on top of
     * whitespace and the end of the line. Closing `(` when a word follows
     * would push the closer into the middle of that word.
     */
    fun autocloseBefore(language: String?): String = when (language) {
        else -> ";:.,=}])>"
    }

    /**
     * The token that, at the end of a line, opens an indented block in a
     * language whose blocks are indentation rather than braces. Python's
     * colon is the case that matters; without it, Enter after `def f():`
     * would leave you at column zero.
     */
    fun blockOpener(language: String?): String? = when (language) {
        "python" -> ":"
        else -> null
    }

    /**
     * The pairs to close and to surround with.
     *
     * Rust is the one language that has to differ: `'` starts a lifetime far
     * more often than a character literal, so Zed's Rust config has no `'`
     * pair at all and neither do we.
     */
    fun pairs(language: String?): List<BracketPair> = when (language) {
        "rust" -> RustPairs
        "python", "bash", "yaml", "gitcommit" -> ScriptPairs
        else -> DefaultPairs
    }

    private val Brackets = listOf(
        BracketPair("(", ")"),
        BracketPair("[", "]"),
        BracketPair("{", "}"),
    )

    private val DefaultPairs = Brackets + listOf(
        BracketPair("\"", "\""),
        BracketPair("'", "'"),
        BracketPair("`", "`"),
    )

    private val ScriptPairs = Brackets + listOf(
        BracketPair("\"", "\""),
        BracketPair("'", "'"),
    )

    private val RustPairs = Brackets + listOf(
        BracketPair("\"", "\""),
        BracketPair("<", ">", autoClose = false),
    )

    /** The pair [text] opens, if any. */
    fun opener(language: String?, text: String): BracketPair? =
        pairs(language).firstOrNull { it.open == text }

    /** The pair [text] closes, if any. Quotes answer to both. */
    fun closer(language: String?, text: String): BracketPair? =
        pairs(language).firstOrNull { it.close == text }

    /** Whether [text] is either half of any pair. */
    fun isPairCharacter(language: String?, text: String): Boolean =
        pairs(language).any { it.open == text || it.close == text }
}
