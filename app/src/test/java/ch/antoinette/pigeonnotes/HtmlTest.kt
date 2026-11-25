package ch.antoinette.pigeonnotes

import ch.antoinette.pigeonnotes.domain.markdown.BlockKind
import ch.antoinette.pigeonnotes.domain.markdown.BlockParser
import ch.antoinette.pigeonnotes.domain.markdown.InlineStyle
import ch.antoinette.pigeonnotes.domain.markdown.InlineStyler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Raw HTML in notes: known tags are hidden and style their content; unknown ones stay visible. */
class HtmlTest {

    private fun render(src: String, kind: BlockKind = BlockKind.PARAGRAPH) =
        InlineStyler.project(InlineStyler.analyze(src, kind), renderMath = true)

    private fun styled(src: String, style: InlineStyle, kind: BlockKind = BlockKind.PARAGRAPH): List<String> {
        val p = render(src, kind)
        return p.styles.filter { it.style == style }.map { p.text.substring(it.start, it.end) }
    }

    @Test
    fun `underline tags are hidden and underline their text`() {
        assertEquals("For each: go", render("<u>For each:</u> go").text)
        assertEquals(listOf("For each:"), styled("<u>For each:</u> go", InlineStyle.UNDERLINE))
    }

    @Test
    fun `underline inside a list item, as in the measurement note`() {
        val src = "5. Lock to some peaks.\n   <u>For 3-5 locks:</u>\n   1. Expose for mode"
        val p = render(src, BlockKind.LIST)
        assertTrue(p.text, !p.text.contains("<u>") && !p.text.contains("</u>"))
        assertTrue(p.text, p.text.contains("For 3-5 locks:"))
        assertEquals(listOf("For 3-5 locks:"), styled(src, InlineStyle.UNDERLINE, BlockKind.LIST))
    }

    @Test
    fun `formatting tags map to the matching styles`() {
        assertEquals(listOf("b"), styled("a <b>b</b> c", InlineStyle.BOLD))
        assertEquals(listOf("i"), styled("a <em>i</em> c", InlineStyle.ITALIC))
        assertEquals(listOf("s"), styled("a <del>s</del> c", InlineStyle.STRIKE))
        assertEquals(listOf("m"), styled("a <mark>m</mark> c", InlineStyle.HIGHLIGHT))
        assertEquals(listOf("2"), styled("H<sub>2</sub>O", InlineStyle.SUBSCRIPT))
        assertEquals(listOf("2"), styled("x<sup>2</sup>", InlineStyle.SUPERSCRIPT))
        assertEquals(listOf("Ctrl"), styled("press <kbd>Ctrl</kbd>", InlineStyle.CODE))
        assertEquals("H2O", render("H<sub>2</sub>O").text)
    }

    @Test
    fun `markdown inside a tag still renders`() {
        val p = render("<u>**bold** text</u>")
        assertEquals("bold text", p.text)
        assertEquals(listOf("bold text"), styled("<u>**bold** text</u>", InlineStyle.UNDERLINE))
        assertEquals(listOf("bold"), styled("<u>**bold** text</u>", InlineStyle.BOLD))
    }

    @Test
    fun `tags show while the cursor is inside the element`() {
        val src = "x <u>under</u> y"
        val a = InlineStyler.analyze(src, BlockKind.PARAGRAPH)
        assertEquals(src, InlineStyler.project(a, cursor = src.indexOf("nd")).text)
        assertEquals("x under y", InlineStyler.project(a, cursor = 0).text)
    }

    @Test
    fun `links, line breaks, images and comments`() {
        val p = render("see <a href=\"https://x.y\">site</a>")
        assertEquals("see site", p.text)
        assertEquals(listOf("https://x.y"), p.links.map { it.url })
        assertEquals(listOf("site"), styled("see <a href=\"https://x.y\">site</a>", InlineStyle.LINK))

        assertEquals("one\ntwo", render("one<br>two").text)
        assertEquals("one\ntwo", render("one<br/>\ntwo").text)
        assertEquals("a  b", render("a <!-- hidden --> b").text)
        assertEquals("a 🖼 pic b", render("a <img src=\"p.png\" alt=\"pic\"> b").text)
    }

    @Test
    fun `text that only looks like a tag stays`() {
        assertEquals("a List<String> here", render("a List<String> here").text)
        // A tag that is never closed is hidden but styles nothing.
        assertEquals("a b", render("a <u>b").text)
        assertTrue(styled("a <u>b", InlineStyle.UNDERLINE).isEmpty())
    }

    @Test
    fun `simple html blocks render as text, others keep their source`() {
        val doc = "<div align=\"center\">\n<b>Hi</b> there\n</div>\n\n<table>\n<tr><td>x</td></tr>\n</table>\n"
        val blocks = BlockParser.parse(doc)
        assertEquals(listOf(BlockKind.HTML_TEXT, BlockKind.HTML), blocks.map { it.kind })
        assertEquals(doc, BlockParser.join(blocks))
        val p = render(blocks[0].source, BlockKind.HTML_TEXT)
        assertEquals("Hi there", p.text)
        assertEquals(listOf("Hi"), styled(blocks[0].source, InlineStyle.BOLD, BlockKind.HTML_TEXT))
        assertEquals(blocks[1].source, render(blocks[1].source, BlockKind.HTML).text)
        // Only a comment: nothing would be left to see or tap.
        assertEquals(BlockKind.HTML, BlockParser.parse("<!-- note -->\n").single().kind)
    }

    @Test
    fun `details blocks show summary and body without the tag lines`() {
        val src = "<details>\n<summary>More</summary>\nHidden text\n</details>"
        assertEquals(BlockKind.HTML_TEXT, BlockParser.parse(src + "\n").single().kind)
        assertEquals("More\nHidden text", render(src, BlockKind.HTML_TEXT).text)
        assertEquals(listOf("More"), styled(src, InlineStyle.BOLD, BlockKind.HTML_TEXT))
    }
}
