package ch.antoinette.pigeonnotes

import ch.antoinette.pigeonnotes.domain.markdown.BlockParser
import ch.antoinette.pigeonnotes.domain.markdown.Toc
import org.junit.Assert.assertEquals
import org.junit.Test

class TocTest {

    @Test
    fun `lists the headings with their level and block index`() {
        val doc = "# Title\n\nIntro\n\n## First *part*\n\n- a\n\n### Deep ###\n\nSetext\n======\n\nOther\n---\n"
        val toc = Toc.of(BlockParser.parse(doc))
        assertEquals(listOf("Title", "First part", "Deep", "Setext", "Other"), toc.map { it.text })
        assertEquals(listOf(1, 2, 3, 1, 2), toc.map { it.level })
        assertEquals(listOf(0, 2, 4, 5, 6), toc.map { it.index })
    }

    @Test
    fun `heading text drops inline markup`() {
        assertEquals("A link and code", Toc.headingText("## A [link](http://x) and `code`"))
        assertEquals("snake_case stays", Toc.headingText("# snake_case stays"))
        assertEquals("Bold", Toc.headingText("# **Bold**"))
        assertEquals("", Toc.headingText("#"))
    }

    @Test
    fun `no headings, no entries`() {
        assertEquals(emptyList<Any>(), Toc.of(BlockParser.parse("Just text\n\n- and a list\n")))
    }
}
