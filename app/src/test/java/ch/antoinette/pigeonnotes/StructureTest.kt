package ch.antoinette.pigeonnotes

import ch.antoinette.pigeonnotes.data.local.Account
import ch.antoinette.pigeonnotes.data.local.AccountSource
import ch.antoinette.pigeonnotes.data.local.FileStore
import ch.antoinette.pigeonnotes.data.local.db.LocalState
import ch.antoinette.pigeonnotes.data.sync.SyncEngine
import ch.antoinette.pigeonnotes.domain.NotesRepository
import ch.antoinette.pigeonnotes.domain.NotesRepository.FolderDeletion
import ch.antoinette.pigeonnotes.domain.NoteText
import ch.antoinette.pigeonnotes.domain.Page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StructureTest {
    private lateinit var server: MockWebServer
    private lateinit var dav: FakeDav
    private lateinit var dir: File
    private lateinit var files: FileStore
    private lateinit var dao: FakeSyncDao
    private lateinit var engine: SyncEngine
    private lateinit var repo: NotesRepository

    @Before
    fun setUp() {
        dav = FakeDav()
        server = MockWebServer().apply { dispatcher = dav; start() }
        dir = Files.createTempDirectory("pigeon").toFile()
        files = FileStore(File(dir, "notes"))
        dao = FakeSyncDao()
        val account = object : AccountSource {
            override suspend fun currentAccount() = Account(server.url("/").toString().trimEnd('/'), "me", "pw", "/Notes")
            override suspend fun markSynced(at: Long) = Unit
        }
        engine = SyncEngine(account, dao, files, OkHttpClient(), CoroutineScope(Dispatchers.Unconfined))
        repo = NotesRepository(files, dao, engine)
    }

    @After
    fun tearDown() {
        server.close(); dir.deleteRecursively()
    }

    private fun sync() = runBlocking { engine.sync() }
    private fun structure() = runBlocking { repo.structure.first() }

    @Test
    fun `a new tag is an empty folder on the server`() {
        sync()
        val path = runBlocking { repo.createFolder(NotesRepository.TAGS_DIR, "Tech") }
        assertEquals("Tags/Tech", path)
        assertEquals(listOf("Tech"), structure().tags)
        sync()
        assertNotNull("the folder exists remotely even while empty", dav.nodes["Tags/Tech"])
        assertNotNull(dav.nodes["Tags"])
        assertEquals(LocalState.CLEAN, runBlocking { dao.get("Tags/Tech") }!!.localState)
    }

    @Test
    fun `duplicate folder names are refused`() {
        runBlocking {
            assertEquals("Pages/Recipes", repo.createFolder(NotesRepository.PAGES_DIR, "Recipes"))
            assertNull(repo.createFolder(NotesRepository.PAGES_DIR, "Recipes"))
        }
    }

    @Test
    fun `renaming a page folder moves its notes and assets`() {
        dav.putFile("Pages/Old/Note.md", "![p](Note.assets/p.png)")
        dav.putFile("Pages/Old/Note.assets/p.png", "PNG")
        sync()

        val newPath = runBlocking { repo.renameFolder("Pages/Old", "New") }
        assertEquals("Pages/New", newPath)
        assertTrue(files.exists("Pages/New/Note.md"))
        assertFalse(files.exists("Pages/Old/Note.md"))
        assertEquals(listOf("New"), structure().pages.map { it.name })

        sync()
        assertEquals("![p](Note.assets/p.png)", dav.text("Pages/New/Note.md"))
        assertNotNull(dav.nodes["Pages/New/Note.assets/p.png"])
        assertNull(dav.nodes["Pages/Old"])
        // and it settles: the parent's ETag moved with the MOVE, so one more look at Pages, then nothing
        sync()
        dav.requests.clear()
        sync()
        assertEquals(listOf("PROPFIND "), dav.requests)
    }

    @Test
    fun `an empty folder can be deleted, a non-empty one is kept`() {
        dav.putFile("Tags/Keep/Note.md", "hi")
        dav.putFile("Tags/Keep/scan.pdf", "%PDF")
        sync()
        runBlocking { repo.createFolder(NotesRepository.TAGS_DIR, "Temp") }
        sync()
        assertEquals(FolderDeletion.Deleted, runBlocking { repo.deleteFolder("Tags/Temp") })
        sync()
        assertNull(dav.nodes["Tags/Temp"])
        assertEquals(listOf("Keep"), structure().tags)

        val result = runBlocking { repo.deleteFolder("Tags/Keep") }
        // Non-markdown files count too, so nothing is removed unnoticed.
        assertEquals(listOf("Note.md", "scan.pdf"), (result as FolderDeletion.NotEmpty).remaining)
        assertNotNull(dav.nodes["Tags/Keep/scan.pdf"])
    }

    @Test
    fun `the structure splits quick notes, pages and tags`() {
        dav.putFile("note-260920-1814.md", "# Buy milk\nand eggs")
        dav.putFile("Pages/Ideas.md", "single page")
        dav.putFile("Pages/Recipes/Orange Chicken.md", "yum")
        dav.putFile("Pages/Recipes/Orange Chicken.assets/p.png", "PNG")
        dav.putFile("Tags/coolsite.md", "untagged")
        dav.putFile("Tags/Tech/releasedates.md", "tagged")
        dav.putFile("_archive/Old.md", "archived")
        dav.putFile("img/shared.png", "PNG")
        dav.mkdir("Tags/Empty")
        sync()

        val s = structure()
        assertEquals(listOf("note-260920-1814.md"), s.quick.map { it.path })
        assertEquals("quick notes are titled by their first line", "Buy milk", s.quick.single().title)
        assertEquals("and eggs", s.quick.single().preview)
        assertEquals(listOf("Ideas", "Recipes"), s.pages.map { it.name })
        assertTrue(s.pages[0] is Page.Single)
        assertEquals(listOf("Orange Chicken"), (s.pages[1] as Page.Folder).notes.map { it.title })
        assertEquals(listOf("Recipes"), s.pageFolders)
        assertEquals(listOf("Empty", "Tech"), s.tags)
        assertEquals(setOf("Tags/coolsite.md", "Tags/Tech/releasedates.md"), s.tagged.map { it.path }.toSet())
        assertNull(NotesRepository.tagOf("Tags/coolsite.md"))
        assertEquals("Tech", NotesRepository.tagOf("Tags/Tech/releasedates.md"))
        assertEquals("Pages/Recipes", NotesRepository.pageFolderOf("Pages/Recipes/Orange Chicken.md"))
        assertNull(NotesRepository.pageFolderOf("Pages/Ideas.md"))
    }

    @Test
    fun `a quick note is named after the moment and tagging moves it`() {
        sync()
        val path = runBlocking { repo.create("", initialText = "Remember this\n") }
        assertTrue(path, Regex("""note-\d{6}-\d{4}\.md""").matches(path))
        assertTrue(NoteText.isQuickNoteName(path))
        val second = runBlocking { repo.create("", initialText = "") }
        assertTrue("same minute gets a suffix", second != path)

        val tagged = runBlocking { repo.move(path, newParent = "Tags/Ideas") }
        assertEquals("Tags/Ideas/" + path, tagged)
        assertEquals("Ideas", NotesRepository.tagOf(tagged))
        val paged = runBlocking { repo.move(tagged, newParent = "Pages") }
        assertEquals("Pages/$path", paged)
        assertTrue(structure().pages.single() is Page.Single)
        dav.requests.clear()
        sync()
        assertEquals("Remember this\n", dav.text(paged))
        assertNull(dav.text(tagged))
        assertTrue("the moved-away source must not be fetched", dav.requests.none { it.startsWith("GET") })
    }

    @Test
    fun `new notes are named after their H1 and follow it when renamed`() {
        sync()
        val quick = runBlocking { repo.create("", initialText = NoteText.NEW_NOTE_TEXT, selectTitle = true) }
        assertTrue(quick, Regex("""note-\d{6}-\d{4}_New-Note\.md""").matches(quick))
        assertEquals("New Note", runBlocking { dao.get(quick) }!!.title)
        assertEquals(true, repo.takeFresh(quick))
        assertNull("fresh only once", repo.takeFresh(quick))

        val stamp = quick.removeSuffix("_New-Note.md")
        runBlocking { repo.save(quick, "# Groceries\n\nmilk\n") }
        val renamed = runBlocking { repo.renameToTitle(quick) }
        assertEquals("${stamp}_Groceries.md", renamed)
        assertEquals("Groceries", runBlocking { dao.get(renamed!!) }!!.title)
        assertNull("already fits", runBlocking { repo.renameToTitle(renamed!!) })

        val page = runBlocking { repo.create("Pages/Recipes", initialText = NoteText.NEW_NOTE_TEXT) }
        assertEquals("Pages/Recipes/New-Note.md", page)
        runBlocking { repo.save(page, "# Lasagne al forno\n") }
        assertEquals("Pages/Recipes/Lasagne-al-forno.md", runBlocking { repo.renameToTitle(page) })

        sync()
        assertEquals("# Groceries\n\nmilk\n", dav.text("${stamp}_Groceries.md"))
        assertNull(dav.text(quick))
        assertEquals("# Lasagne al forno\n", dav.text("Pages/Recipes/Lasagne-al-forno.md"))
    }

    @Test
    fun `renaming to the title carries the assets and moves the note on the server`() {
        dav.putFile("note-261008-1328.md", "Old note\n\n![p](note-261008-1328.assets/p.png)\n")
        dav.putFile("note-261008-1328.assets/p.png", "img")
        sync()
        assertEquals("Old note", structure().quick.single().title)
        runBlocking { repo.save("note-261008-1328.md", "# Trip\n\n![p](note-261008-1328.assets/p.png)\n") }
        val renamed = runBlocking { repo.renameToTitle("note-261008-1328.md") }
        assertEquals("note-261008-1328_Trip.md", renamed)
        sync()
        assertEquals("# Trip\n\n![p](note-261008-1328_Trip.assets/p.png)\n", dav.text("note-261008-1328_Trip.md"))
        assertNotNull(dav.text("note-261008-1328_Trip.assets/p.png"))
        assertNull(dav.text("note-261008-1328.md"))
        assertNull(dav.text("note-261008-1328.assets/p.png"))
    }
}
