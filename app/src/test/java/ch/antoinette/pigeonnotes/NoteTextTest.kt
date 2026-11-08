package ch.antoinette.pigeonnotes

import ch.antoinette.pigeonnotes.domain.NoteText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class NoteTextTest {

    @Test
    fun `the first H1 is the heading, wherever it is`() {
        assertEquals("Shopping", NoteText.heading("# Shopping\n\n- milk\n"))
        assertEquals("Later", NoteText.heading("Intro text\n\n## Sub\n\n# Later\n"))
        assertEquals("Closed", NoteText.heading("# Closed ##\n"))
        assertEquals("C#", NoteText.heading("# C#\n"))
        assertEquals("Setext title", NoteText.heading("Setext title\n===\n\nbody\n"))
        assertEquals("Big plan", NoteText.heading("# **Big** [plan](http://x)\n"))
        assertEquals("Real", NoteText.heading("---\ntitle: x\n---\n# Real\n"))
    }

    @Test
    fun `headings in code, lower levels and empty headings don't count`() {
        assertNull(NoteText.heading("```bash\n# a comment\n```\n"))
        assertNull(NoteText.heading("## Only a section\n"))
        assertNull(NoteText.heading("#\n"))
        assertNull(NoteText.heading("#hashtag\n"))
        assertNull(NoteText.heading(""))
    }

    @Test
    fun `slugs turn spaces and punctuation into single dashes`() {
        assertEquals("New-Note", NoteText.slug("New Note"))
        assertEquals("Einkauf-Migros-Coop", NoteText.slug("Einkauf: Migros & Coop?"))
        assertEquals("Grüezi-Zürich", NoteText.slug("  Grüezi   Zürich  "))
        assertEquals("Dont-panic", NoteText.slug("Don’t panic!"))
        assertEquals("a-b", NoteText.slug("a / b"))
        assertEquals("", NoteText.slug("?!"))
        val long = NoteText.slug("This title is much too long to fit into a sensible file name at all")
        assertTrue(long, long.length <= 50 && !long.endsWith("-"))
        assertEquals("This-title-is-much-too-long-to-fit-into-a-sensible", long)
    }

    @Test
    fun `new notes are named after their title`() {
        val at = LocalDateTime.of(2026, 10, 8, 13, 28)
        assertEquals("note-261008-1328_New-Note", NoteText.newStem("", "New Note", at))
        assertEquals("note-261008-1328_New-Note", NoteText.newStem("Tags/Work", "New Note", at))
        assertEquals("note-261008-1328", NoteText.newStem("", null, at))
        assertEquals("New-Note", NoteText.newStem("Pages/Recipes", "New Note", at))
        assertEquals("Untitled", NoteText.newStem("Pages", "?", at))
    }

    @Test
    fun `the H1 titles every note, the file name only without one`() {
        assertEquals("Groceries" to "milk", NoteText.index("note-261008-1328_New-Note.md", "# Groceries\n\nmilk\n"))
        assertEquals("Lasagne", NoteText.index("Pages/Recipes/Pasta.md", "# Lasagne\n").first)
        assertEquals("Pasta" to "boil water", NoteText.index("Pages/Recipes/Pasta.md", "boil water\n"))
        // A stamped name's title part, dashes read as spaces.
        assertEquals("Einkauf Migros" to "milk", NoteText.index("Tags/note-261008-1328_Einkauf-Migros.md", "milk\n"))
        // Old quick notes keep showing their first line.
        assertEquals("Remember" to "this", NoteText.index("note-261008-1328.md", "Remember\nthis\n"))
        // Nothing to go by: the moment it was made.
        assertEquals("Thu 08.10.26, 13:28" to "", NoteText.index("note-261008-1328.md", ""))
        assertEquals("Thu 08.10.26, 13:28", NoteText.index("_archive/note-261008-1328 2.md", "\n").first)
    }

    @Test
    fun `stamped names keep the stamp and follow the H1 anywhere`() {
        assertEquals("note-261008-1328_Groceries", NoteText.nameForTitle("note-261008-1328_New-Note.md", "# Groceries\n"))
        assertEquals("note-261008-1328_Groceries", NoteText.nameForTitle("note-261008-1328.md", "# Groceries\n"))
        assertEquals("note-261008-1328_Groceries", NoteText.nameForTitle("Tags/Work/note-261008-1328.md", "# Groceries\n"))
        assertEquals("note-261008-1328_Groceries", NoteText.nameForTitle("_archive/note-261008-1328_X.md", "# Groceries\n"))
        assertEquals("note-261008-1328", NoteText.nameForTitle("note-261008-1328_Old.md", "# ?\n"))
        assertTrue(NoteText.isQuickNoteName("note-261008-1328_Groceries 2.md"))
    }

    @Test
    fun `names that already fit, or must stay, are kept`() {
        assertNull(NoteText.nameForTitle("note-261008-1328_Groceries.md", "# Groceries\n"))
        assertNull("a uniqueness suffix is no reason to rename", NoteText.nameForTitle("note-261008-1328_Groceries 2.md", "# Groceries\n"))
        assertNull("no H1, no rename", NoteText.nameForTitle("note-261008-1328_Groceries.md", "Groceries\n"))
        assertNull(NoteText.nameForTitle("note-261008-1328_A (conflict 2026-10-08 13-30).md", "# B\n"))
        assertNull("only stamped names follow the H1 outside Pages", NoteText.nameForTitle("Ideas.md", "# Plans\n"))
        assertNull(NoteText.nameForTitle("Tags/Work/Meeting.md", "# Standup\n"))
        assertNull("spaces count as dashes", NoteText.nameForTitle("Pages/My Recipe.md", "# My Recipe\n"))
        assertNull(NoteText.nameForTitle("Pages/Recipes/Lasagne 2.md", "# Lasagne\n"))
        assertFalse(NoteText.isQuickNoteName("Pages/Recipes/Lasagne.md"))
    }

    @Test
    fun `standalone pages are named by the H1 alone`() {
        assertEquals("Reading-list", NoteText.nameForTitle("Pages/Books.md", "# Reading list\n"))
        assertEquals("note-261008-1328_Ideas", NoteText.nameForTitle("Pages/note-261008-1328.md", "# Ideas\n"))
        assertNull("an empty slug keeps the name", NoteText.nameForTitle("Pages/Books.md", "# ?\n"))
    }

    @Test
    fun `notes inside page folders keep their name, at any depth`() {
        assertNull(NoteText.nameForTitle("Pages/Recipes/Lasagne.md", "# Lasagne al forno\n"))
        assertNull(NoteText.nameForTitle("Pages/Recipes/Asian/Curry.md", "# Green curry\n"))
        assertNull("not even a stamped name", NoteText.nameForTitle("Pages/Job search/note-261008-1328.md", "# Call Ting\n"))
        assertNull(NoteText.nameForTitle("Pages/Recipes/New-Note.md", "# Lasagne\n"))
    }

    @Test
    fun `a note just created in a page folder is named after its H1 once`() {
        assertEquals("Lasagne", NoteText.nameForTitle("Pages/Recipes/New-Note.md", "# Lasagne\n", fresh = true))
        assertEquals("Curry", NoteText.nameForTitle("Pages/Recipes/Asian/New-Note.md", "# Curry\n", fresh = true))
        assertNull(NoteText.nameForTitle("Pages/Recipes/New-Note.md", "# New Note\n", fresh = true))
    }
}
