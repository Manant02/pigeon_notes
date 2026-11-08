package ch.antoinette.pigeonnotes

import ch.antoinette.pigeonnotes.data.local.db.LocalState
import ch.antoinette.pigeonnotes.data.local.db.SyncEntry
import ch.antoinette.pigeonnotes.ui.notes.FolderTree
import ch.antoinette.pigeonnotes.ui.notes.TreeItem
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderTreeTest {
    private fun note(path: String, title: String = path.substringAfterLast('/').removeSuffix(".md"), modified: Long = 0) =
        SyncEntry(path, isDir = false, baseEtag = "e", localState = LocalState.CLEAN, title = title, modified = modified)

    // Newest first, as the database hands them out.
    private val notes = listOf(
        note("Pages/Job/Latest.md", "Continued search", modified = 5),
        note("Pages/Job/Apps/Lab A.md", modified = 4),
        note("Pages/Job/Older.md", modified = 3),
        note("Pages/Job/Apps/Done/Old lab.md", modified = 2),
        note("Pages/Other/X.md", modified = 1),
    )
    private val folders = listOf("Pages", "Pages/Job", "Pages/Job/Apps", "Pages/Job/Apps/Done", "Pages/Job/archive", "Pages/Job/Empty", "Pages/Other")

    private fun List<TreeItem>.describe() = map {
        "  ".repeat(it.depth) + when (it) {
            is TreeItem.Folder -> (if (it.expanded) "v " else "> ") + it.name + " (${it.folderCount}/${it.noteCount})"
            is TreeItem.Note -> it.row.title + " [" + it.fileName + "]"
            is TreeItem.Empty -> "(empty)"
        }
    }

    @Test
    fun `folders come first by name, then notes newest first`() {
        assertEquals(
            listOf("> Apps (1/1)", "> archive (0/0)", "> Empty (0/0)", "Continued search [Latest]", "Older [Older]"),
            FolderTree.build("Pages/Job", notes, folders, expanded = emptySet()).describe(),
        )
    }

    @Test
    fun `expanded folders show their contents in place, one level deeper`() {
        assertEquals(
            listOf(
                "v Apps (1/1)",
                "  v Done (0/1)",
                "    Old lab [Old lab]",
                "  Lab A [Lab A]",
                "> archive (0/0)",
                "v Empty (0/0)",
                "  (empty)",
                "Continued search [Latest]",
                "Older [Older]",
            ),
            FolderTree.build("Pages/Job", notes, folders, expanded = setOf("Pages/Job/Apps", "Pages/Job/Apps/Done", "Pages/Job/Empty")).describe(),
        )
    }

    @Test
    fun `a closed folder hides even its open sub-folders`() {
        assertEquals(
            listOf("> Apps (1/1)", "> archive (0/0)", "> Empty (0/0)", "Continued search [Latest]", "Older [Older]"),
            FolderTree.build("Pages/Job", notes, folders, expanded = setOf("Pages/Job/Apps/Done")).describe(),
        )
    }

    @Test
    fun `search covers the whole folder and shows where each match is`() {
        assertEquals(
            listOf("Lab A [Apps/Lab A]", "Old lab [Apps/Done/Old lab]"),
            FolderTree.search("Pages/Job", notes, "lab").describe(),
        )
        assertEquals("file names match too", listOf("Continued search [Latest]"), FolderTree.search("Pages/Job", notes, "latest").describe())
    }

    @Test
    fun `the breadcrumb names every folder from the top`() {
        assertEquals(
            listOf("Pages" to "Pages", "Job" to "Pages/Job", "Apps" to "Pages/Job/Apps"),
            FolderTree.crumbs("Pages/Job/Apps"),
        )
    }
}
