package to.eyed.conquest.code.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader, against what a README actually contains.
 *
 * The cases here are the ones that go wrong quietly rather than loudly: a
 * `---` that is a rule in one place and a heading underline in another,
 * `snake_case` that must not turn italic, a nested list that must not flatten,
 * and a fence whose contents must survive being anything at all.
 */
class MarkdownDocumentTest {

    private fun text(spans: List<InlineSpan>): String = spans.joinToString("") { it.text }

    private fun paragraphs(source: String): List<MarkdownBlock.Paragraph> =
        parseMarkdown(source).filterIsInstance<MarkdownBlock.Paragraph>()

    @Test
    fun `headings come in both spellings`() {
        val blocks = parseMarkdown(
            """
            # Title
            ### Third
            Setext
            ======
            Second
            ------
            """.trimIndent()
        )
        val headings = blocks.filterIsInstance<MarkdownBlock.Heading>()
        assertEquals(listOf(1, 3, 1, 2), headings.map { it.level })
        assertEquals(listOf("Title", "Third", "Setext", "Second"), headings.map { text(it.content) })
    }

    @Test
    fun `a dashed line is a rule with no paragraph open and a heading with one`() {
        assertEquals(
            listOf(MarkdownBlock.Rule),
            parseMarkdown("---")
        )
        val blocks = parseMarkdown("Heading\n---\n")
        assertEquals(1, blocks.size)
        assertEquals(2, (blocks[0] as MarkdownBlock.Heading).level)
    }

    @Test
    fun `a paragraph's lines join and a hard break survives`() {
        val paragraph = paragraphs("one\ntwo").single()
        assertEquals("one two", text(paragraph.content))
        val broken = paragraphs("one  \ntwo").single()
        assertEquals("one\ntwo", text(broken.content))
    }

    @Test
    fun `emphasis, strong, strike and code spans`() {
        val spans = paragraphs("*a* **b** ~~c~~ `d`").single().content
        val styled = spans.filter { it.styles.isNotEmpty() }
        assertEquals(
            listOf(
                setOf(InlineStyle.Italic),
                setOf(InlineStyle.Bold),
                setOf(InlineStyle.Strikethrough),
                setOf(InlineStyle.Code),
            ),
            styled.map { it.styles },
        )
        assertEquals(listOf("a", "b", "c", "d"), styled.map { it.text })
    }

    @Test
    fun `nested emphasis keeps both styles`() {
        val spans = paragraphs("**bold *and italic***").single().content
        assertTrue(
            spans.any {
                it.styles == setOf(InlineStyle.Bold, InlineStyle.Italic) && it.text == "and italic"
            }
        )
    }

    @Test
    fun `an underscore inside a word is not emphasis`() {
        val spans = paragraphs("call snake_case_name here").single().content
        assertEquals("call snake_case_name here", text(spans))
        assertTrue(spans.none { InlineStyle.Italic in it.styles })
    }

    @Test
    fun `a lone asterisk with spaces around it stays literal`() {
        val spans = paragraphs("2 * 3 * 4").single().content
        assertEquals("2 * 3 * 4", text(spans))
    }

    @Test
    fun `markup inside a code span is left alone`() {
        val spans = paragraphs("use `a_*b*_c` here").single().content
        val code = spans.single { InlineStyle.Code in it.styles }
        assertEquals("a_*b*_c", code.text)
    }

    @Test
    fun `links inline, by reference and bare`() {
        val source = """
            See [docs](https://example.com/a), [other][ref] and https://example.com/b.

            [ref]: https://example.com/c
        """.trimIndent()
        val spans = paragraphs(source).single().content
        val links = spans.filter { it.link != null }
        assertEquals(
            listOf("https://example.com/a", "https://example.com/c", "https://example.com/b"),
            links.map { it.link },
        )
        // The full stop belongs to the sentence, not to the bare URL.
        assertEquals("https://example.com/b", links.last().text)
    }

    @Test
    fun `an image becomes a run that names itself and fetches nothing`() {
        val spans = paragraphs("![a badge](https://img.example/x.svg)").single().content
        val image = spans.single()
        assertTrue(image.isImage)
        assertEquals("a badge", image.text)
        assertEquals("https://img.example/x.svg", image.link)
    }

    @Test
    fun `html tags are dropped and a br becomes a line break`() {
        val spans = paragraphs("<p align=\"center\">one<br/>two</p>").single().content
        assertEquals("one\ntwo", text(spans))
    }

    @Test
    fun `a fenced block keeps its language and its contents verbatim`() {
        val block = parseMarkdown(
            """
            ```rust
            fn main() {
                // # not a heading
            }
            ```
            """.trimIndent()
        ).single() as MarkdownBlock.Code
        assertEquals("rust", block.language)
        assertEquals("fn main() {\n    // # not a heading\n}", block.code)
    }

    @Test
    fun `an unterminated fence still ends at the end of the file`() {
        val block = parseMarkdown("```\nstuff\n").single() as MarkdownBlock.Code
        assertEquals("stuff\n", block.code)
    }

    @Test
    fun `a link definition inside a fence is not a definition`() {
        val spans = paragraphs(
            """
            ```
            [ref]: https://example.com/inside
            ```

            [ref] alone
            """.trimIndent()
        ).single().content
        assertTrue(spans.none { it.link != null })
    }

    @Test
    fun `lists nest instead of flattening`() {
        val list = parseMarkdown(
            """
            - one
              - inner
            - two
            """.trimIndent()
        ).single() as MarkdownBlock.Bullets
        assertEquals(2, list.items.size)
        val nested = list.items[0].blocks.filterIsInstance<MarkdownBlock.Bullets>().single()
        assertEquals(1, nested.items.size)
        assertEquals(
            "inner",
            text((nested.items[0].blocks.single() as MarkdownBlock.Paragraph).content),
        )
    }

    @Test
    fun `an ordered list counts from its own first number`() {
        val list = parseMarkdown("3. c\n4. d").single() as MarkdownBlock.Bullets
        assertTrue(list.ordered)
        assertEquals(listOf("3.", "4."), list.items.map { it.marker })
    }

    @Test
    fun `task items carry their checkbox and lose its text`() {
        val list = parseMarkdown("- [x] done\n- [ ] todo").single() as MarkdownBlock.Bullets
        assertEquals(listOf(true, false), list.items.map { it.checked })
        assertEquals(
            "done",
            text((list.items[0].blocks.single() as MarkdownBlock.Paragraph).content),
        )
    }

    @Test
    fun `a blank line between items makes the list loose but keeps it one list`() {
        val list = parseMarkdown("- one\n\n- two").single() as MarkdownBlock.Bullets
        assertEquals(2, list.items.size)
        assertTrue(!list.tight)
    }

    @Test
    fun `a fence inside a list item stays inside it`() {
        val list = parseMarkdown(
            """
            - run this:

              ```sh
              ls -la
              ```
            """.trimIndent()
        ).single() as MarkdownBlock.Bullets
        val code = list.items.single().blocks.filterIsInstance<MarkdownBlock.Code>().single()
        assertEquals("sh", code.language)
        assertEquals("ls -la", code.code)
    }

    @Test
    fun `a block quote nests its blocks and picks up a GitHub alert`() {
        val quote = parseMarkdown("> [!WARNING]\n> mind the gap").single() as MarkdownBlock.Quote
        assertEquals("WARNING", quote.kind)
        assertEquals(
            "mind the gap",
            text((quote.blocks.single() as MarkdownBlock.Paragraph).content),
        )
    }

    @Test
    fun `a table reads its header, alignments and rows`() {
        val table = parseMarkdown(
            """
            | Shortcut | Action |
            |---|:---:|
            | `Ctrl` `G` | Go to line |
            """.trimIndent()
        ).single() as MarkdownBlock.Table
        assertEquals(listOf("Shortcut", "Action"), table.header.map(::text))
        assertEquals(listOf(ColumnAlignment.Start, ColumnAlignment.Center), table.alignments)
        assertEquals(1, table.rows.size)
        assertEquals("Go to line", text(table.rows[0][1]))
    }

    @Test
    fun `a table straight after a paragraph line still becomes a table`() {
        val blocks = parseMarkdown("intro\n\n| a | b |\n| - | - |\n| 1 | 2 |")
        assertTrue(blocks[1] is MarkdownBlock.Table)
    }

    @Test
    fun `an indented block is code, but an indented continuation line is not`() {
        val code = parseMarkdown("# Title\n\n    indented()").last() as MarkdownBlock.Code
        assertEquals("indented()", code.code)
        assertNull(code.language)
        val paragraph = paragraphs("a sentence\n    that wrapped").single()
        assertEquals("a sentence that wrapped", text(paragraph.content))
    }

    @Test
    fun `a relative link resolves against the file and cannot leave the project`() {
        assertEquals("docs/USERLAND.md", resolveRelativePath("README.md", "docs/USERLAND.md"))
        assertEquals("docs/BUILDING.md", resolveRelativePath("docs/SHORTCUTS.md", "BUILDING.md"))
        assertEquals("README.md", resolveRelativePath("docs/SHORTCUTS.md", "../README.md"))
        assertEquals("etc/passwd", resolveRelativePath("README.md", "../../../etc/passwd"))
        assertEquals("docs/A.md", resolveRelativePath("README.md", "docs/A.md#heading"))
    }

    /**
     * Half-typed markup is the *normal* state of a file being previewed while
     * it is written, so every one of these is a document the parser will see.
     * The only claim is that it comes back with something rather than
     * throwing — a crash here takes the editor down with it.
     */
    @Test
    fun `half-typed markup parses instead of throwing`() {
        val cases = listOf(
            "[", "]", "![", "![]", "[]()", "[a](", "[a][", "[a][b]", "[]: ",
            "`", "``", "```", "~~", "~~~", "*", "**", "***", "****", "_", "__",
            "<", ">", "<>", "</>", "<a", "|", "|-", "|---|", "| a |", "- ", "-",
            "1.", "1. ", "> ", ">", "#", "######", "#######", "    ",
            "\\", "\\\\", " ", "🙂 **bold 🙂**",
            "- a\n  - b\n    - c\n      - d\n        - e",
            "> > > deep\n> > > quote",
            "| a |\n|---|\n| `x|y` |",
            "```\n```\n```\n",
        )
        for (case in cases) {
            // A blank-only document is legitimately no blocks; everything else
            // has to produce something.
            val blocks = parseMarkdown(case)
            assertTrue("threw nothing but produced nothing for <$case>", case.isBlank() || blocks.isNotEmpty())
        }
    }

    @Test
    fun `an empty document is no blocks rather than one empty paragraph`() {
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdown(""))
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdown("\n\n   \n"))
    }
}
