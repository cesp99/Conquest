package to.eyed.conquest.code.ui.preview

/**
 * A hand-written Markdown reader, deliberately.
 *
 * Zed renders its preview with `pulldown-cmark` behind
 * `crates/markdown/src/parser.rs`; the Rust equivalent is not reachable from
 * here without a new JNI surface, and the Java/Kotlin markdown libraries all
 * cost more than they are worth for this: commonmark-java is ~330 KB of dex
 * before extensions, flexmark closer to 1.5 MB, and neither of them renders
 * anything — they hand back an AST that still has to be walked into Compose
 * exactly as [MarkdownBlock] is. What a README actually uses is the CommonMark
 * core plus GitHub's tables, task lists and alerts, and that is what this file
 * reads.
 *
 * It is *not* a conforming CommonMark parser, and the places it knowingly
 * differs are marked at the code that makes each choice. It is pure Kotlin
 * with no Android or Compose types in it, which is what lets the whole of it
 * be tested on the host.
 */

/** What an inline run is wearing. A run may wear several at once. */
enum class InlineStyle { Bold, Italic, Code, Strikethrough }

/**
 * One run of inline text: the smallest piece with a single appearance.
 *
 * A link's label is one or more runs all carrying the same [link], so
 * `**[bold link](url)**` stays bold *and* clickable rather than having to
 * choose.
 */
data class InlineSpan(
    val text: String,
    val styles: Set<InlineStyle> = emptySet(),
    /** Destination of the link this run belongs to, or null. */
    val link: String? = null,
    /**
     * True when the run stands in for an image, and [text] is its alt text.
     * Nothing here fetches anything — see the placeholder in the renderer.
     */
    val isImage: Boolean = false,
)

/** How a table column is aligned, from its delimiter row's colons. */
enum class ColumnAlignment { Start, Center, End }

/** One entry of a bullet or ordered list. */
data class ListItem(
    /** What the renderer draws in the margin: `•`, `1.`, `2.`… */
    val marker: String,
    /** Checked state for a GitHub task item, or null when it is not one. */
    val checked: Boolean?,
    val blocks: List<MarkdownBlock>,
)

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val content: List<InlineSpan>) : MarkdownBlock

    data class Paragraph(val content: List<InlineSpan>) : MarkdownBlock

    /** A fenced or indented code block. [language] is the fence's info word. */
    data class Code(val language: String?, val code: String) : MarkdownBlock

    /**
     * A block quote. [kind] is GitHub's alert marker — `NOTE`, `TIP`,
     * `IMPORTANT`, `WARNING`, `CAUTION` — when the quote opens with one, which
     * is how a modern README writes a callout.
     */
    data class Quote(val kind: String?, val blocks: List<MarkdownBlock>) : MarkdownBlock

    /**
     * A list. [tight] is CommonMark's looseness: a list whose items are
     * separated by blank lines gets paragraph spacing, a tight one does not.
     */
    data class Bullets(
        val ordered: Boolean,
        val tight: Boolean,
        val items: List<ListItem>,
    ) : MarkdownBlock

    data object Rule : MarkdownBlock

    data class Table(
        val header: List<List<InlineSpan>>,
        val alignments: List<ColumnAlignment>,
        val rows: List<List<List<InlineSpan>>>,
    ) : MarkdownBlock
}

/** GitHub's alert kinds, as they may appear after `>` on a quote's first line. */
private val ALERT_KINDS = setOf("NOTE", "TIP", "IMPORTANT", "WARNING", "CAUTION")

/** CommonMark's limit: four spaces of indent is a code block, not a marker. */
private const val CODE_INDENT = 4

/**
 * Read [source] into blocks.
 *
 * The link reference definitions are collected first, over the whole document,
 * because `[text][ref]` may name a `[ref]:` line further down — which is how
 * every badge-heavy README is written.
 */
fun parseMarkdown(source: String): List<MarkdownBlock> {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    return parseBlocks(lines, collectLinkDefinitions(lines))
}

// ---- Block level ---------------------------------------------------------

private fun parseBlocks(lines: List<String>, links: Map<String, String>): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) {
            index++
            continue
        }
        val indent = line.indentWidth()

        val fence = fenceAt(line)
        if (fence != null) {
            index = readFencedCode(lines, index, fence, blocks)
            continue
        }
        // Indented code, but only where a paragraph cannot be continuing: a
        // wrapped paragraph line indented by four spaces is still that
        // paragraph, and treating it as code is the classic misread.
        if (indent >= CODE_INDENT && blocks.lastOrNull() !is MarkdownBlock.Paragraph) {
            index = readIndentedCode(lines, index, blocks)
            continue
        }
        if (linkDefinitionOf(line) != null) {
            index++
            continue
        }
        val heading = atxHeadingAt(line)
        if (heading != null) {
            blocks.add(MarkdownBlock.Heading(heading.first, parseInline(heading.second, links)))
            index++
            continue
        }
        if (isThematicBreak(line)) {
            blocks.add(MarkdownBlock.Rule)
            index++
            continue
        }
        if (indent < CODE_INDENT && line.trimStart().startsWith('>')) {
            index = readQuote(lines, index, links, blocks)
            continue
        }
        if (markerAt(line) != null) {
            index = readList(lines, index, links, blocks)
            continue
        }
        if (index + 1 < lines.size && '|' in line && tableAlignments(lines[index + 1]) != null) {
            index = readTable(lines, index, links, blocks)
            continue
        }
        index = readParagraph(lines, index, links, blocks)
    }
    return blocks
}

/** The fence's char and length, or null when [line] does not open one. */
private fun fenceAt(line: String): Pair<Char, Int>? {
    if (line.indentWidth() >= CODE_INDENT) return null
    val body = line.trimStart()
    val char = body.firstOrNull() ?: return null
    if (char != '`' && char != '~') return null
    val run = body.takeWhile { it == char }.length
    if (run < 3) return null
    // A backtick fence's info string may not contain a backtick, which is what
    // keeps `` `a` `` from being read as a fence.
    if (char == '`' && '`' in body.drop(run)) return null
    return char to run
}

private fun readFencedCode(
    lines: List<String>,
    start: Int,
    fence: Pair<Char, Int>,
    blocks: MutableList<MarkdownBlock>,
): Int {
    val (char, length) = fence
    val opener = lines[start]
    val indent = opener.indentWidth()
    val info = opener.trimStart().drop(length).trim()
        .substringBefore(' ')
        .takeIf { it.isNotEmpty() }
    val body = mutableListOf<String>()
    var index = start + 1
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        if (line.indentWidth() < CODE_INDENT &&
            trimmed.isNotEmpty() &&
            trimmed.all { it == char } &&
            trimmed.length >= length
        ) {
            index++
            break
        }
        body.add(line.dropIndent(indent))
        index++
    }
    blocks.add(MarkdownBlock.Code(info, body.joinToString("\n")))
    return index
}

private fun readIndentedCode(
    lines: List<String>,
    start: Int,
    blocks: MutableList<MarkdownBlock>,
): Int {
    val body = mutableListOf<String>()
    var index = start
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) {
            // A blank line only stays in the block if code follows it. Scanned
            // by index rather than with `drop`, which would copy the tail of
            // the document once per blank line.
            var next = index + 1
            while (next < lines.size && lines[next].isBlank()) next++
            if (next >= lines.size || lines[next].indentWidth() < CODE_INDENT) break
            body.add("")
            index++
            continue
        }
        if (line.indentWidth() < CODE_INDENT) break
        body.add(line.dropIndent(CODE_INDENT))
        index++
    }
    blocks.add(MarkdownBlock.Code(null, body.joinToString("\n")))
    return index
}

/** `#` … `######`, with its text. */
private fun atxHeadingAt(line: String): Pair<Int, String>? {
    if (line.indentWidth() >= CODE_INDENT) return null
    val body = line.trimStart()
    val hashes = body.takeWhile { it == '#' }.length
    if (hashes !in 1..6) return null
    val rest = body.drop(hashes)
    if (rest.isNotEmpty() && !rest[0].isWhitespace()) return null
    // A closing run of hashes is decoration, not text.
    return hashes to rest.trim().trimEnd('#').trim()
}

private fun isThematicBreak(line: String): Boolean {
    if (line.indentWidth() >= CODE_INDENT) return false
    val body = line.trim().filter { !it.isWhitespace() }
    if (body.length < 3) return false
    val char = body[0]
    return (char == '-' || char == '*' || char == '_') && body.all { it == char }
}

private fun readQuote(
    lines: List<String>,
    start: Int,
    links: Map<String, String>,
    blocks: MutableList<MarkdownBlock>,
): Int {
    val body = mutableListOf<String>()
    var index = start
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trimStart()
        if (line.indentWidth() < CODE_INDENT && trimmed.startsWith('>')) {
            body.add(trimmed.drop(1).removePrefix(" "))
            index++
            continue
        }
        // Lazy continuation: an unprefixed line still belongs to the quote's
        // open paragraph, which is how most people actually wrap a quote.
        if (line.isNotBlank() && body.lastOrNull()?.isNotBlank() == true &&
            !isThematicBreak(line) && atxHeadingAt(line) == null && markerAt(line) == null
        ) {
            body.add(line)
            index++
            continue
        }
        break
    }
    var kind: String? = null
    val first = body.firstOrNull()?.trim().orEmpty()
    if (first.startsWith("[!") && first.endsWith("]")) {
        val name = first.removePrefix("[!").removeSuffix("]").uppercase()
        if (name in ALERT_KINDS) {
            kind = name
            body.removeAt(0)
        }
    }
    blocks.add(MarkdownBlock.Quote(kind, parseBlocks(body, links)))
    return index
}

/**
 * A list marker on one line.
 *
 * [contentIndent] is the *column* an item's continuation lines have to reach
 * to belong to it; [contentOffset] is the character index its own first line's
 * content starts at. The two differ as soon as a tab is involved, and using
 * one for the other eats a character of the item's text.
 */
private class Marker(
    val indent: Int,
    val text: String,
    val contentIndent: Int,
    val contentOffset: Int,
    val ordered: Boolean,
)

private fun markerAt(line: String): Marker? {
    val indent = line.indentWidth()
    if (indent >= CODE_INDENT) return null
    val body = line.trimStart()
    val char = body.firstOrNull() ?: return null
    val text: String
    val ordered: Boolean
    if (char == '-' || char == '*' || char == '+') {
        // `---` is a rule, and `- - -` is one too; neither opens a list.
        if (isThematicBreak(line)) return null
        text = char.toString()
        ordered = false
    } else if (char.isDigit()) {
        val digits = body.takeWhile { it.isDigit() }
        if (digits.length > 9) return null
        val after = body.getOrNull(digits.length) ?: return null
        if (after != '.' && after != ')') return null
        text = digits + after
        ordered = true
    } else {
        return null
    }
    val rest = body.drop(text.length)
    if (rest.isNotEmpty() && !rest[0].isWhitespace()) return null
    val spaces = rest.takeWhile { it == ' ' }.length
    // An empty item, or one whose content starts a code block, indents by one.
    val gap = if (rest.isBlank() || spaces == 0 || spaces > CODE_INDENT) 1 else spaces
    val leading = line.length - body.length
    return Marker(
        indent = indent,
        text = text,
        contentIndent = indent + text.length + gap,
        contentOffset = leading + text.length + gap,
        ordered = ordered,
    )
}

private fun readList(
    lines: List<String>,
    start: Int,
    links: Map<String, String>,
    blocks: MutableList<MarkdownBlock>,
): Int {
    val first = markerAt(lines[start])!!
    val items = mutableListOf<ListItem>()
    var loose = false
    var index = start
    var counter = 0
    while (index < lines.size) {
        // A marker indented as far as the first item's content is a *nested*
        // list, and the inner loop below has already claimed it; only one
        // shallower than that is a sibling.
        val marker = markerAt(lines[index])?.takeIf { it.indent < first.contentIndent } ?: break
        // A different kind of marker starts a different list, as in CommonMark.
        if (marker.ordered != first.ordered) break
        val body = mutableListOf(
            lines[index].substring(marker.contentOffset.coerceAtMost(lines[index].length))
        )
        index++
        var pendingBlanks = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                pendingBlanks++
                index++
                continue
            }
            val continues = line.indentWidth() >= marker.contentIndent
            val nextMarker = markerAt(line)?.takeIf { it.indent < first.contentIndent }
            if (!continues && nextMarker == null) break
            if (pendingBlanks > 0) {
                // A blank anywhere inside a list makes the whole list loose,
                // which is the only thing looseness is used for here.
                loose = true
                if (!continues) break
                repeat(pendingBlanks) { body.add("") }
                pendingBlanks = 0
            }
            if (!continues) break
            body.add(line.dropIndent(marker.contentIndent))
            index++
        }
        // Blanks after the last item belong to the document, not to the item.
        counter++
        var checked: Boolean? = null
        val head = body.firstOrNull().orEmpty()
        if (head.length >= 3 && head[0] == '[' && head[2] == ']' &&
            (head[1] == ' ' || head[1].lowercaseChar() == 'x')
        ) {
            checked = head[1].lowercaseChar() == 'x'
            body[0] = head.drop(3).removePrefix(" ")
        }
        val label = if (first.ordered) {
            "${first.text.dropLast(1).toIntOrNull()?.plus(counter - 1) ?: counter}."
        } else {
            "•"
        }
        items.add(ListItem(label, checked, parseBlocks(body, links)))
    }
    blocks.add(MarkdownBlock.Bullets(first.ordered, tight = !loose, items = items))
    return index
}

/** The alignments a delimiter row declares, or null if it is not one. */
internal fun tableAlignments(line: String): List<ColumnAlignment>? {
    // The pipe is required: without it a plain `---` would read as a
    // one-column delimiter row and swallow every thematic break in the file.
    if ('|' !in line) return null
    val cells = splitTableRow(line)
    if (cells.isEmpty()) return null
    val alignments = cells.map { cell ->
        val text = cell.trim()
        if (text.isEmpty() || !text.all { it == '-' || it == ':' }) return null
        if (text.count { it == '-' } == 0) return null
        when {
            text.startsWith(':') && text.endsWith(':') -> ColumnAlignment.Center
            text.endsWith(':') -> ColumnAlignment.End
            else -> ColumnAlignment.Start
        }
    }
    return alignments
}

/** Split a table row on unescaped pipes, dropping the outer ones. */
internal fun splitTableRow(line: String): List<String> {
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var index = 0
    val body = line.trim()
    while (index < body.length) {
        val char = body[index]
        if (char == '\\' && index + 1 < body.length) {
            current.append(char).append(body[index + 1])
            index += 2
            continue
        }
        if (char == '|') {
            cells.add(current.toString())
            current.clear()
            index++
            continue
        }
        current.append(char)
        index++
    }
    cells.add(current.toString())
    if (cells.isNotEmpty() && cells.first().isBlank()) cells.removeAt(0)
    if (cells.isNotEmpty() && cells.last().isBlank()) cells.removeAt(cells.size - 1)
    return cells
}

private fun readTable(
    lines: List<String>,
    start: Int,
    links: Map<String, String>,
    blocks: MutableList<MarkdownBlock>,
): Int {
    val alignments = tableAlignments(lines[start + 1])!!
    val header = splitTableRow(lines[start]).map { parseInline(it.trim(), links) }
    val rows = mutableListOf<List<List<InlineSpan>>>()
    var index = start + 2
    while (index < lines.size && lines[index].isNotBlank() && '|' in lines[index]) {
        rows.add(splitTableRow(lines[index]).map { parseInline(it.trim(), links) })
        index++
    }
    blocks.add(MarkdownBlock.Table(header, alignments, rows))
    return index
}

private fun readParagraph(
    lines: List<String>,
    start: Int,
    links: Map<String, String>,
    blocks: MutableList<MarkdownBlock>,
): Int {
    val text = StringBuilder()
    var index = start
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) break
        if (index > start) {
            // A setext underline turns everything gathered so far into a
            // heading. It is checked before the block starters below because
            // `---` is also a thematic break, and CommonMark gives the
            // heading precedence while a paragraph is open.
            val underline = setextLevel(line)
            if (underline != null) {
                blocks.add(MarkdownBlock.Heading(underline, parseInline(text.toString(), links)))
                return index + 1
            }
            if (fenceAt(line) != null || atxHeadingAt(line) != null || isThematicBreak(line) ||
                markerAt(line) != null || line.trimStart().startsWith('>')
            ) {
                break
            }
            // GitHub lets a table's header row be the line that would otherwise
            // have continued the paragraph, so the delimiter row underneath it
            // ends the paragraph here.
            if (index + 1 < lines.size && '|' in line && tableAlignments(lines[index + 1]) != null) {
                break
            }
        }
        if (text.isNotEmpty()) text.append(if (endsWithHardBreak(lines[index - 1])) '\n' else ' ')
        text.append(line.trim().let { if (endsWithHardBreak(line)) it.trimEnd('\\') else it })
        index++
    }
    blocks.add(MarkdownBlock.Paragraph(parseInline(text.toString(), links)))
    return index
}

private fun setextLevel(line: String): Int? {
    if (line.indentWidth() >= CODE_INDENT) return null
    val body = line.trim()
    if (body.isEmpty()) return null
    return when {
        body.all { it == '=' } -> 1
        body.all { it == '-' } -> 2
        else -> null
    }
}

/** Markdown's two hard breaks: two trailing spaces, or a trailing backslash. */
private fun endsWithHardBreak(line: String): Boolean =
    line.endsWith("  ") || (line.endsWith("\\") && !line.endsWith("\\\\"))

// ---- Link reference definitions ------------------------------------------

/** `[label]: destination "title"` → label (folded to lower case) → destination. */
private fun linkDefinitionOf(line: String): Pair<String, String>? {
    if (line.indentWidth() >= CODE_INDENT) return null
    val body = line.trimStart()
    if (!body.startsWith('[')) return null
    val close = body.indexOf("]:")
    if (close <= 1) return null
    val label = body.substring(1, close)
    if ('[' in label) return null
    val rest = body.substring(close + 2).trim()
    if (rest.isEmpty()) return null
    val destination = rest.substringBefore(' ').trim('<', '>')
    if (destination.isEmpty()) return null
    return label.lowercase() to destination
}

private fun collectLinkDefinitions(lines: List<String>): Map<String, String> {
    val definitions = mutableMapOf<String, String>()
    var fence: Pair<Char, Int>? = null
    for (line in lines) {
        val open = fence
        if (open != null) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && trimmed.all { it == open.first } &&
                trimmed.length >= open.second
            ) {
                fence = null
            }
            continue
        }
        val opened = fenceAt(line)
        if (opened != null) {
            fence = opened
            continue
        }
        linkDefinitionOf(line)?.let { (label, destination) ->
            definitions.putIfAbsent(label, destination)
        }
    }
    return definitions
}

// ---- Inline level --------------------------------------------------------

/**
 * Read one paragraph's worth of text into styled runs.
 *
 * Written as a left-to-right scan with a recursive call per delimiter rather
 * than CommonMark's delimiter stack: the stack exists to resolve pathological
 * nesting (`*foo**bar*`), which no README contains, and the scan is a tenth of
 * the code. Where a delimiter has no partner the characters stay literal,
 * which is the same answer the stack would give for the cases that matter.
 */
internal fun parseInline(text: String, links: Map<String, String>): List<InlineSpan> {
    val out = mutableListOf<InlineSpan>()
    scanInline(text, emptySet(), null, links, out)
    return out
}

private fun scanInline(
    text: String,
    styles: Set<InlineStyle>,
    link: String?,
    links: Map<String, String>,
    out: MutableList<InlineSpan>,
) {
    val plain = StringBuilder()
    fun flush() {
        if (plain.isEmpty()) return
        out.add(InlineSpan(plain.toString(), styles, link))
        plain.clear()
    }

    var index = 0
    while (index < text.length) {
        val char = text[index]
        when {
            char == '\\' && index + 1 < text.length && !text[index + 1].isLetterOrDigit() -> {
                plain.append(text[index + 1])
                index += 2
            }
            char == '`' -> {
                val run = text.countRun('`', index)
                val close = text.indexOfRun('`', run, index + run)
                if (close < 0) {
                    plain.append(text, index, index + run)
                    index += run
                } else {
                    flush()
                    // One space either side is the fence that lets a code span
                    // hold a backtick; it is not content.
                    val code = text.substring(index + run, close)
                    out.add(
                        InlineSpan(
                            if (code.startsWith(' ') && code.endsWith(' ') && code.isNotBlank()) {
                                code.substring(1, code.length - 1)
                            } else {
                                code
                            },
                            styles + InlineStyle.Code,
                            link,
                        )
                    )
                    index = close + run
                }
            }
            char == '!' && index + 1 < text.length && text[index + 1] == '[' -> {
                val image = readLink(text, index + 1, links)
                if (image == null) {
                    plain.append(char)
                    index++
                } else {
                    flush()
                    out.add(InlineSpan(image.label, styles, image.destination, isImage = true))
                    index = image.end
                }
            }
            char == '[' -> {
                val found = readLink(text, index, links)
                if (found == null) {
                    plain.append(char)
                    index++
                } else {
                    flush()
                    scanInline(found.label, styles, found.destination, links, out)
                    index = found.end
                }
            }
            char == '<' -> {
                val close = text.indexOf('>', index)
                val inner = if (close < 0) "" else text.substring(index + 1, close)
                when {
                    close < 0 -> {
                        plain.append(char)
                        index++
                    }
                    inner.startsWith("http://") || inner.startsWith("https://") ||
                        inner.startsWith("mailto:") -> {
                        flush()
                        out.add(InlineSpan(inner.removePrefix("mailto:"), styles, inner))
                        index = close + 1
                    }
                    // Raw HTML. A README's `<p align="center">` and `<img>` are
                    // markup we cannot draw, and printing the tag is worse than
                    // dropping it; `<br>` is the one that carries meaning.
                    inner.isHtmlTag() -> {
                        if (inner.trim().trimEnd('/').equals("br", ignoreCase = true)) {
                            plain.append('\n')
                        }
                        index = close + 1
                    }
                    else -> {
                        plain.append(char)
                        index++
                    }
                }
            }
            text.startsWith("~~", index) -> {
                val close = text.indexOfDelimiter("~~", index + 2)
                if (close < 0) {
                    plain.append("~~")
                    index += 2
                } else {
                    flush()
                    scanInline(
                        text.substring(index + 2, close),
                        styles + InlineStyle.Strikethrough,
                        link,
                        links,
                        out,
                    )
                    index = close + 2
                }
            }
            char == '*' || char == '_' -> {
                val run = text.countRun(char, index).coerceAtMost(2)
                val close = if (text.canOpenEmphasis(index, run, char)) {
                    text.indexOfCloser(run, index + run, char)
                } else {
                    -1
                }
                if (close < 0) {
                    plain.append(text, index, index + run)
                    index += run
                } else {
                    flush()
                    val style = if (run == 2) InlineStyle.Bold else InlineStyle.Italic
                    scanInline(text.substring(index + run, close), styles + style, link, links, out)
                    index = close + run
                }
            }
            // GitHub's bare autolink. Only at a word boundary, or the `http`
            // inside a longer word would start one.
            (char == 'h') && (index == 0 || !text[index - 1].isLetterOrDigit()) &&
                (text.startsWith("http://", index) || text.startsWith("https://", index)) -> {
                var end = index
                while (end < text.length && !text[end].isWhitespace() && text[end] != '<') end++
                // Trailing punctuation belongs to the sentence, not the URL.
                while (end > index && text[end - 1] in ".,;:!?)]") end--
                flush()
                val url = text.substring(index, end)
                out.add(InlineSpan(url, styles, url))
                index = end
            }
            else -> {
                plain.append(char)
                index++
            }
        }
    }
    flush()
}

/** A resolved `[label](dest)`, `[label][ref]` or `[ref]`. */
private class FoundLink(val label: String, val destination: String, val end: Int)

private fun readLink(text: String, start: Int, links: Map<String, String>): FoundLink? {
    val labelEnd = text.matchingBracket(start) ?: return null
    val label = text.substring(start + 1, labelEnd)
    var index = labelEnd + 1
    if (index < text.length && text[index] == '(') {
        val close = text.matchingParen(index) ?: return null
        val inside = text.substring(index + 1, close).trim()
        // `(url "title")` — the title is not something we draw.
        val destination = inside.substringBefore(' ').trim('<', '>')
        if (destination.isEmpty()) return null
        return FoundLink(label, destination, close + 1)
    }
    if (index < text.length && text[index] == '[') {
        val close = text.matchingBracket(index) ?: return null
        val reference = text.substring(index + 1, close).ifBlank { label }
        val destination = links[reference.lowercase()] ?: return null
        return FoundLink(label, destination, close + 1)
    }
    // Shortcut reference: `[ref]` on its own.
    val destination = links[label.lowercase()] ?: return null
    return FoundLink(label, destination, index)
}

// ---- Relative links -------------------------------------------------------

/**
 * A link's target as a project-relative path, resolved against the previewed
 * file's own directory.
 *
 * `..` is followed, and a walk that would climb above the project root is
 * clamped rather than allowed to escape: whatever comes back is opened inside
 * the project, and `../../../etc/passwd` in a README somebody cloned must not
 * be a way out of it.
 */
internal fun resolveRelativePath(from: String, target: String): String {
    val cleaned = target.substringBefore('#').substringBefore('?')
    val base = if (cleaned.startsWith('/')) emptyList() else from.split('/').dropLast(1)
    val parts = ArrayList(base)
    for (segment in cleaned.trimStart('/').split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts.add(segment)
        }
    }
    return parts.joinToString("/")
}

// ---- Small string helpers -------------------------------------------------

/** Leading whitespace in columns, counting a tab as four. */
internal fun String.indentWidth(): Int {
    var width = 0
    for (char in this) {
        when (char) {
            ' ' -> width++
            '\t' -> width += CODE_INDENT
            else -> return width
        }
    }
    return width
}

/** Drop up to [columns] of leading indent, keeping anything past it. */
private fun String.dropIndent(columns: Int): String {
    var width = 0
    var index = 0
    while (index < length && width < columns) {
        when (this[index]) {
            ' ' -> width++
            '\t' -> width += CODE_INDENT
            else -> break
        }
        index++
    }
    return substring(index)
}

private fun String.countRun(char: Char, from: Int): Int {
    var index = from
    while (index < length && this[index] == char) index++
    return index - from
}

/** The next run of exactly [run] [char]s at or after [from]. */
private fun String.indexOfRun(char: Char, run: Int, from: Int): Int {
    var index = from
    while (index < length) {
        if (this[index] != char) {
            index++
            continue
        }
        val size = countRun(char, index)
        if (size == run) return index
        index += size
    }
    return -1
}

/** The next [delimiter] not inside a code span and not escaped. */
private fun String.indexOfDelimiter(delimiter: String, from: Int): Int {
    var index = from
    while (index < length) {
        when {
            this[index] == '\\' -> index += 2
            this[index] == '`' -> {
                val run = countRun('`', index)
                val close = indexOfRun('`', run, index + run)
                index = if (close < 0) index + run else close + run
            }
            startsWith(delimiter, index) -> return index
            else -> index++
        }
    }
    return -1
}

/**
 * Where an emphasis run of [length] [char]s opened just before [from] closes.
 *
 * Two rules, and both earn their place in a README. A closer may not follow
 * whitespace, so `2 * 3 * 4` is arithmetic rather than emphasis. And a run
 * *longer* than the one being closed closes from its end, so the `***` that
 * ends `**bold *and italic***` gives its first star to the italic and the
 * other two to the bold — matching from the front instead leaves a stray
 * asterisk and un-italicises the middle.
 *
 * Code spans are stepped over: a `*` inside backticks closes nothing.
 */
private fun String.indexOfCloser(length: Int, from: Int, char: Char): Int {
    var index = from
    while (index < this.length) {
        if (this[index] == '\\') {
            index += 2
            continue
        }
        if (this[index] == '`') {
            val ticks = countRun('`', index)
            val close = indexOfRun('`', ticks, index + ticks)
            index = if (close < 0) index + ticks else close + ticks
            continue
        }
        if (this[index] != char) {
            index++
            continue
        }
        val run = countRun(char, index)
        if (run >= length) {
            val at = index + run - length
            val before = getOrNull(index - 1)
            val after = getOrNull(index + run)
            val wordSafe = char != '_' || after == null || !after.isLetterOrDigit()
            if (at > from && before != null && !before.isWhitespace() && wordSafe) return at
        }
        index += run
    }
    return -1
}

/** Whether an emphasis run at [at] can open: it must not be followed by space. */
private fun String.canOpenEmphasis(at: Int, run: Int, char: Char): Boolean {
    val after = getOrNull(at + run) ?: return false
    if (after.isWhitespace()) return false
    if (char != '_') return true
    val before = getOrNull(at - 1)
    return before == null || !before.isLetterOrDigit()
}

private fun String.matchingBracket(open: Int): Int? {
    var depth = 0
    var index = open
    while (index < length) {
        when {
            this[index] == '\\' -> index++
            this[index] == '[' -> depth++
            this[index] == ']' -> {
                depth--
                if (depth == 0) return index
            }
        }
        index++
    }
    return null
}

private fun String.matchingParen(open: Int): Int? {
    var depth = 0
    var index = open
    while (index < length) {
        when {
            this[index] == '\\' -> index++
            this[index] == '(' -> depth++
            this[index] == ')' -> {
                depth--
                if (depth == 0) return index
            }
        }
        index++
    }
    return null
}

/** Whether `<…>` holds something that is plausibly an HTML tag. */
private fun String.isHtmlTag(): Boolean {
    val head = firstOrNull() ?: return false
    if (!head.isLetter() && head != '/' && head != '!') return false
    return trimStart('/', '!').takeWhile { it.isLetterOrDigit() }.isNotEmpty()
}
