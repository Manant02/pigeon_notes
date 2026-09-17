package ch.antoinette.pigeonnotes

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
        assertEquals("•  one\n•  ☑ two\n  •  nested\n1. num", p.text)
        // cursor on line 1 shows its raw bullet only
        val editing = InlineStyler.project(a, cursor = 2)
        assertEquals("- one\n•  ☑ two\n  •  nested\n1. num", editing.text)
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
}
