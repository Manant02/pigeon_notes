package ch.antoinette.pigeonnotes

import ch.antoinette.pigeonnotes.domain.markdown.BlockKind
import ch.antoinette.pigeonnotes.domain.markdown.BlockParser
import ch.antoinette.pigeonnotes.domain.markdown.InlineStyle
import ch.antoinette.pigeonnotes.domain.markdown.InlineStyler
import ch.antoinette.pigeonnotes.ui.editor.AlertKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The syntax list from Typora: auto links, inline math, sub/superscript, highlight, alerts. */
class SyntaxSupportTest {

    private fun render(src: String, kind: BlockKind = BlockKind.PARAGRAPH) =
        InlineStyler.project(InlineStyler.analyze(src, kind), renderMath = true)

    private fun styled(src: String, style: InlineStyle): List<String> {
        val p = render(src)
        return p.styles.filter { it.style == style }.map { p.text.substring(it.start, it.end) }
    }

    @Test
    fun `auto links are linkified, bare and bracketed`() {
        val p = render("see https://typora.io and <https://a.b/c> too")
        assertEquals("see https://typora.io and https://a.b/c too", p.text)
        assertEquals(listOf("https://typora.io", "https://a.b/c"), p.links.map { it.url })
    }

    @Test
    fun `subscript and superscript hide their delimiters`() {
        val p = render("H~2~O and X^2^")
        assertEquals("H2O and X2", p.text)
        assertEquals(listOf("2"), styled("H~2~O", InlineStyle.SUBSCRIPT))
        assertEquals(listOf("2"), styled("X^2^", InlineStyle.SUPERSCRIPT))
    }

    @Test
    fun `highlight hides its equals signs`() {
        assertEquals("a key word", render("a ==key== word").text)
        assertEquals(listOf("key"), styled("a ==key== word", InlineStyle.HIGHLIGHT))
    }

    @Test
    fun `strikethrough still wins over subscript`() {
        val p = render("~~gone~~ but H~2~O stays")
        assertEquals("gone but H2O stays", p.text)
        assertEquals(listOf("gone"), styled("~~gone~~", InlineStyle.STRIKE))
    }

    @Test
    fun `the new syntax is literal inside code, math and urls`() {
        // Backticks, formulas and URLs keep their tildes, carets and equals signs.
        assertEquals("H~2~O", render("`H~2~O`").text)
        assertEquals("x^2^", render("`x^2^`").text)
        assertEquals("a==b", render("`a==b`").text)
        assertTrue(render("\$x^2\$ here").math.single().latex == "x^2")
        assertEquals("link", render("[link](http://x/a~b~c)").text)
        assertEquals("http://x/a^b^c", render("http://x/a^b^c").text)
    }

    @Test
    fun `the cursor reveals the source of the new syntax`() {
        val a = InlineStyler.analyze("H~2~O", BlockKind.PARAGRAPH)
        assertEquals("H2O", InlineStyler.project(a).text)
        assertEquals("H~2~O", InlineStyler.project(a, cursor = 2).text)
    }

    @Test
    fun `alerts are their own block with the title hidden`() {
        val doc = "> [!WARNING]\n> Careful here.\n"
        val block = BlockParser.parse(doc).single()
        assertEquals(BlockKind.ALERT, block.kind)
        assertEquals(AlertKind.WARNING, AlertKind.of(block.source))
        // Title line and the quote markers are hidden; the body is plain.
        assertEquals("Careful here.", InlineStyler.project(InlineStyler.analyze(block.source, block.kind)).text)
    }

    @Test
    fun `every alert kind is recognised, unknown ones fall back to note`() {
        for (kind in AlertKind.entries) {
            assertEquals(kind, AlertKind.of("> [!${kind.name}]\n> body"))
            assertEquals(kind, AlertKind.of("> [!${kind.name.lowercase()}]\n> body"))
        }
        assertEquals(AlertKind.NOTE, AlertKind.of("> [!WHATEVER]\n> body"))
    }

    @Test
    fun `a plain block quote is still a quote`() {
        val block = BlockParser.parse("> just quoted\n").single()
        assertEquals(BlockKind.QUOTE, block.kind)
    }
}
