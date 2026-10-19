package ch.antoinette.pigeonnotes

import ch.antoinette.pigeonnotes.domain.NoteText
import ch.antoinette.pigeonnotes.domain.markdown.BlockKind
import ch.antoinette.pigeonnotes.domain.markdown.BlockParser
import ch.antoinette.pigeonnotes.domain.markdown.InlineStyle
import ch.antoinette.pigeonnotes.domain.markdown.InlineStyler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun `blocks cover the document with exact ranges`() {
        val doc = "# Title\n\nSome *text* here.\n\n- a\n- b\n\n```kt\nval x = 1\n```\n\n![img](a.png)\n"
        val blocks = BlockParser.parse(doc)
        assertEquals(
            listOf(BlockKind.HEADING, BlockKind.PARAGRAPH, BlockKind.LIST, BlockKind.CODE, BlockKind.IMAGE),
            blocks.map { it.kind },
        )
        for (b in blocks) assertEquals(b.source, doc.substring(b.start, b.end))
        assertEquals(1, blocks[0].level)
        assertEquals("- a\n- b", blocks[2].source)
        assertEquals("```kt\nval x = 1\n```", blocks[3].source)
    }

    @Test
    fun `front matter is one block`() {
        val doc = "---\ntitle: x\ntags: [a]\n---\n\nBody\n"
        val blocks = BlockParser.parse(doc)
        assertEquals(BlockKind.FRONTMATTER, blocks[0].kind)
        assertEquals("---\ntitle: x\ntags: [a]\n---\n", blocks[0].source)
        assertEquals(BlockKind.PARAGRAPH, blocks[1].kind)
        assertEquals("Body", blocks[1].source)
    }

    @Test
    fun `bold markers hide unless cursor inside`() {
        val a = InlineStyler.analyze("say **hi** there", BlockKind.PARAGRAPH)
        val rendered = InlineStyler.project(a)
        assertEquals("say hi there", rendered.text)
        assertEquals(InlineStyle.BOLD, rendered.styles.single().style)
        assertEquals(4, rendered.styles.single().start)
        assertEquals(6, rendered.styles.single().end)

        val editing = InlineStyler.project(a, cursor = 7) // between h and i
        assertEquals("say **hi** there", editing.text)

        val outside = InlineStyler.project(a, cursor = 2)
        assertEquals("say hi there", outside.text)
    }

    @Test
    fun `a selection keeps the syntax of the elements it touches`() {
        val src = "see ![alt](a.png) and **b**"
        val a = InlineStyler.analyze(src, BlockKind.PARAGRAPH)
        assertEquals("see 🖼 alt and b", InlineStyler.project(a).text)
        val alt = src.indexOf("alt")
        // Selecting the alt text must not collapse the image syntax under the selection.
        assertEquals("see ![alt](a.png) and b", InlineStyler.project(a, alt, cursorEnd = alt + 3).text)
        // A selection over both elements shows both.
        assertEquals(src, InlineStyler.project(a, 0, cursorEnd = src.length).text)
    }

    @Test
    fun `unclosed fences are found`() {
        assertEquals("```", BlockParser.unclosedFence("```"))
        assertEquals("~~~~", BlockParser.unclosedFence("text\n~~~~ js\ncode"))
        assertEquals(null, BlockParser.unclosedFence("```kt\ncode\n```"))
        assertEquals("````", BlockParser.unclosedFence("````\n```\n"))
        assertEquals(null, BlockParser.unclosedFence("inline ```x``` code"))
        assertEquals(null, BlockParser.unclosedFence("```x```"))
        assertTrue(BlockParser.isClosedFence("```\ncode\n```"))
        assertTrue(!BlockParser.isClosedFence("```\ncode"))
        assertTrue(!BlockParser.isClosedFence("```"))
    }

    @Test
    fun `offset mapping round trips across hidden markers`() {
        val a = InlineStyler.analyze("a **b** c [d](http://x) e", BlockKind.PARAGRAPH)
        val p = InlineStyler.project(a)
        assertEquals("a b c d e", p.text)
        // source index of 'c' is 8, output is 4
        assertEquals(4, p.toOutput(8))
        assertEquals(8, p.toSource(4))
        // 'e' at source 24 -> output 8
        assertEquals(8, p.toOutput(a.source.indexOf('e')))
        assertEquals(a.source.indexOf('e'), p.toSource(8))
        assertEquals("http://x", p.links.single().url)
    }

    @Test
    fun `heading hides hashes and styles whole line`() {
        val a = InlineStyler.analyze("## Hello *world*", BlockKind.HEADING, level = 2)
        val p = InlineStyler.project(a)
        assertEquals("Hello world", p.text)
        assertTrue(p.styles.any { it.style == InlineStyle.HEADING && it.level == 2 && it.start == 0 && it.end == p.text.length })
        assertTrue(p.styles.any { it.style == InlineStyle.ITALIC })
    }

    @Test
    fun `lists get bullets and checkboxes`() {
        val a = InlineStyler.analyze("- one\n- [x] two\n  - nested\n1. num", BlockKind.LIST)
        val p = InlineStyler.project(a)
        // A task item shows only its box, no bullet, and checked text is not struck through.
        assertEquals("•  one\n☑ two\n  •  nested\n1. num", p.text)
        assertTrue(p.styles.none { it.style == InlineStyle.STRIKE })
        val box = p.checkboxes.single()
        assertTrue(box.checked)
        assertEquals("☑ ", p.text.substring(box.start, box.end))
        // cursor on line 1 shows its raw bullet only
        val editing = InlineStyler.project(a, cursor = 2)
        assertEquals("- one\n☑ two\n  •  nested\n1. num", editing.text)
    }

    @Test
    fun `list lines expose their content start for hanging indents`() {
        val a = InlineStyler.analyze("- one\n- [ ] two", BlockKind.LIST)
        val p = InlineStyler.project(a)
        assertEquals(listOf("•  " to "one", "☐ " to "two"), p.listLines.map {
            p.text.substring(it.start, it.contentStart) to p.text.substring(it.contentStart, it.end)
        })
    }

    @Test
    fun `inline math collapses to a placeholder outside the cursor`() {
        val a = InlineStyler.analyze("energy ${'$'}E=mc^2${'$'} here", BlockKind.PARAGRAPH)
        val p = InlineStyler.project(a, renderMath = true)
        assertEquals("energy \uFFFC here", p.text)
        val hit = p.math.single()
        assertEquals("E=mc^2", hit.latex)
        assertEquals(7, hit.start)
        // source offset of "here" maps through the placeholder
        assertEquals(a.source.indexOf("here"), p.toSource(p.text.indexOf("here")))
        // with the cursor inside, the source shows again
        assertEquals("energy ${'$'}E=mc^2${'$'} here", InlineStyler.project(a, cursor = 10, renderMath = true).text)
    }

    @Test
    fun `extra blank lines become editable empty blocks`() {
        val doc = "One\n\n\n\nTwo\n"
        val blocks = BlockParser.parse(doc)
        assertEquals(
            listOf(BlockKind.PARAGRAPH, BlockKind.EMPTY, BlockKind.EMPTY, BlockKind.PARAGRAPH),
            blocks.map { it.kind },
        )
        // Empty blocks are zero-length insertion points, and normal separation adds none.
        assertTrue(blocks.filter { it.kind == BlockKind.EMPTY }.all { it.start == it.end })
        assertEquals(listOf(BlockKind.PARAGRAPH, BlockKind.PARAGRAPH), BlockParser.parse("One\n\nTwo\n").map { it.kind })
        assertEquals(listOf(BlockKind.EMPTY, BlockKind.PARAGRAPH), BlockParser.parse("\nOne\n").map { it.kind })
    }

    @Test
    fun `code fence hides fences`() {
        val a = InlineStyler.analyze("```kotlin\nval a = 1\n```", BlockKind.CODE)
        assertEquals("val a = 1", InlineStyler.project(a).text)
        assertEquals("```kotlin\nval a = 1\n```", InlineStyler.project(a, cursor = 12).text)
    }

    @Test
    fun `inline image and code span`() {
        val a = InlineStyler.analyze("see ![alt text](../img/x.png) and `code`", BlockKind.PARAGRAPH)
        val p = InlineStyler.project(a)
        assertEquals("see 🖼 alt text and code", p.text)
        assertEquals("../img/x.png", p.links.single().url)
    }

    @Test
    fun `block quote hides chevrons`() {
        val a = InlineStyler.analyze("> quoted\n> more", BlockKind.QUOTE)
        assertEquals("quoted\nmore", InlineStyler.project(a).text)
    }

    @Test
    fun `preview strips markup, bullets and task markers`() {
        assertEquals("Latex Rendering, like Typora", NoteText.preview("## Feature requests\n\n- [ ] Latex Rendering, like Typora\n"))
        assertEquals("Boox Note mini C?", NoteText.preview("# eInk Tablet\n\nBoox Note mini C?\n"))
        assertEquals("a link here", NoteText.preview("- [a link](http://x) here\n"))
        assertEquals("quoted", NoteText.preview("> quoted\n"))
    }

    @Test
    fun `display math on its own lines is one block, blank lines inside included`() {
        val doc = "Intro\n\n$$\n\n\\frac{d}{dt} H = -ih\n\n$$\n\nAfter\n\n```\n$$\nnot math\n$$\n```\n"
        val blocks = BlockParser.parse(doc)
        assertEquals(listOf(BlockKind.PARAGRAPH, BlockKind.MATH, BlockKind.PARAGRAPH, BlockKind.CODE), blocks.map { it.kind })
        assertEquals("$$\n\n\\frac{d}{dt} H = -ih\n\n$$", blocks[1].source)
        assertEquals("After", blocks[2].source)
        // An unclosed $$ stays ordinary text.
        assertTrue(BlockParser.parse("$$\nx\n\ntext\n").none { it.kind == BlockKind.MATH })
    }
}
