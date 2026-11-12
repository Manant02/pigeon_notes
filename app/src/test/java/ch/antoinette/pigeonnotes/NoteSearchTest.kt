package ch.antoinette.pigeonnotes

import ch.antoinette.pigeonnotes.data.local.db.LocalState
import ch.antoinette.pigeonnotes.data.local.db.SyncEntry
import ch.antoinette.pigeonnotes.ui.notes.NoteSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSearchTest {
    private fun note(path: String, title: String, preview: String = "") =
        SyncEntry(path, isDir = false, baseEtag = "e", localState = LocalState.CLEAN, title = title, preview = preview)

    private fun search(texts: Map<SyncEntry, String>, query: String) =
        NoteSearch.search(texts.keys.toList(), query) { texts.getValue(it) }

    @Test
    fun findsWordsDeepInTheText() {
        val n = note("note-261101-0900_Groceries.md", "Groceries", "Milk")
        val text = "# Groceries\n\nMilk\n\nEggs\n\n- [ ] Sourdough **bread** from the bakery\n"
        val hit = search(mapOf(n to text), "bread").single()
        assertEquals("Sourdough bread from the bakery", hit.snippet)
        assertEquals(listOf(10..14), hit.snippetHits)
        assertEquals("Quick note", hit.location)
    }

    @Test
    fun everyWordMustAppearAnywhere() {
        val n = note("Pages/Recipes/Curry.md", "Curry", "")
        val text = "# Curry\n\nCoconut milk\n\nLots of ginger"
        assertEquals(1, search(mapOf(n to text), "ginger curry").size)
        assertTrue(search(mapOf(n to text), "ginger lime").isEmpty())
    }

    @Test
    fun ignoresCaseAndAccents() {
        val n = note("Tags/Travel/Zurich.md", "Zürich café list", "")
        val hit = search(mapOf(n to "# Zürich café list\n"), "zurich CAFE").single()
        assertEquals(listOf(0..5, 7..10), hit.titleHits)
        assertEquals("#Travel", hit.location)
    }

    @Test
    fun titleMatchesComeFirstOtherwiseOrderIsKept() {
        val newer = note("a.md", "Shopping", "")
        val titled = note("b.md", "Bread recipe", "")
        val older = note("c.md", "Misc", "")
        val texts = linkedMapOf(newer to "Need bread", titled to "# Bread recipe\nflour", older to "bread again")
        assertEquals(listOf("b.md", "a.md", "c.md"), search(texts, "bread").map { it.row.path })
    }

    @Test
    fun titleOnlyMatchShowsThePreview() {
        val n = note("Pages/Ideas.md", "Project ideas", "A list of things")
        val hit = search(mapOf(n to "# Project ideas\n\nA list of things"), "ideas").single()
        assertEquals("A list of things", hit.snippet)
        assertEquals("Page", hit.location)
    }

    @Test
    fun markdownAndLinkTargetsDoNotMatch() {
        val n = note("x.md", "X", "")
        val text = "---\ntags: secret\n---\nSee [the docs](https://example.com/path) and [[Other note|that note]]\n![img](photo.png)"
        assertTrue(search(mapOf(n to text), "example").isEmpty())
        assertTrue(search(mapOf(n to text), "secret").isEmpty())
        assertTrue(search(mapOf(n to text), "photo").isEmpty())
        assertEquals("See the docs and that note", search(mapOf(n to text), "docs").single().snippet)
    }

    @Test
    fun longLinesAreCutAroundTheMatch() {
        val n = note("x.md", "X", "")
        val line = "word ".repeat(60) + "needle " + "tail ".repeat(60)
        val hit = search(mapOf(n to line), "needle").single()
        assertTrue(hit.snippet.startsWith("…") && hit.snippet.endsWith("…"))
        val r = hit.snippetHits.single()
        assertEquals("needle", hit.snippet.substring(r.first, r.last + 1))
    }

    @Test
    fun archivedNotesAreMarked() {
        val n = note("_archive/Old.md", "Old", "")
        val hit = search(mapOf(n to "something old"), "old").single()
        assertTrue(hit.archived)
        assertEquals("Archive", hit.location)
    }

    @Test
    fun locations() {
        assertEquals("Recipes › Asian", NoteSearch.location("Pages/Recipes/Asian/Pho.md"))
        assertEquals("Untagged", NoteSearch.location("Tags/x.md"))
        assertEquals("Other › Dir", NoteSearch.location("Other/Dir/x.md"))
    }

    @Test
    fun overlappingMatchesMerge() {
        assertEquals(listOf(0..5), NoteSearch.ranges("banana", listOf("ban", "nana")))
        assertNull(NoteSearch.terms("   ").firstOrNull())
    }
}
