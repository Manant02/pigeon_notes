package ch.antoinette.pigeonnotes

import ch.antoinette.pigeonnotes.data.local.Account
import ch.antoinette.pigeonnotes.data.local.AccountSource
import ch.antoinette.pigeonnotes.data.local.FileStore
import ch.antoinette.pigeonnotes.data.local.db.LocalState
import ch.antoinette.pigeonnotes.data.sync.SyncEngine
import ch.antoinette.pigeonnotes.domain.NotesRepository
import ch.antoinette.pigeonnotes.domain.NotesRepository.CategoryDeletion
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

class CategoryTest {
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
    private fun categories() = runBlocking { repo.categories.first() }

    @Test
    fun `a new category is an empty folder on the server`() {
        sync()
        val path = runBlocking { repo.createCategory("Recipes") }
        assertEquals("Recipes", path)
        assertTrue(categories().contains("Recipes"))
        sync()
        assertNotNull("the folder exists remotely even while empty", dav.nodes["Recipes"])
        assertEquals(LocalState.CLEAN, runBlocking { dao.get("Recipes") }!!.localState)
    }

    @Test
    fun `duplicate category names are refused`() {
        runBlocking {
            assertEquals("Recipes", repo.createCategory("Recipes"))
            assertNull(repo.createCategory("Recipes"))
        }
    }

    @Test
    fun `renaming a category moves its notes and assets`() {
        dav.putFile("Old/Note.md", "![p](Note.assets/p.png)")
        dav.putFile("Old/Note.assets/p.png", "PNG")
        sync()

        val newPath = runBlocking { repo.renameCategory("Old", "New") }
        assertEquals("New", newPath)
        assertTrue(files.exists("New/Note.md"))
        assertFalse(files.exists("Old/Note.md"))
        assertEquals(listOf("New"), categories())

        sync()
        assertEquals("![p](Note.assets/p.png)", dav.text("New/Note.md"))
        assertNotNull(dav.nodes["New/Note.assets/p.png"])
        assertNull(dav.nodes["Old"])
        // and it settles: nothing left to push
        dav.requests.clear()
        sync()
        assertEquals(listOf("PROPFIND "), dav.requests)
    }

    @Test
    fun `an empty category can be deleted`() {
        sync()
        runBlocking { repo.createCategory("Temp") }
        sync()
        assertEquals(CategoryDeletion.Deleted, runBlocking { repo.deleteCategory("Temp") })
        sync()
        assertNull(dav.nodes["Temp"])
        assertFalse(categories().contains("Temp"))
    }

    @Test
    fun `a category holding notes or other files is kept`() {
        dav.putFile("Keep/Note.md", "hi")
        dav.putFile("Keep/scan.pdf", "%PDF")
        dav.mkdir("Empty")
        sync()

        val result = runBlocking { repo.deleteCategory("Keep") }
        assertTrue(result is CategoryDeletion.NotEmpty)
        // Non-markdown files count too, so nothing is removed unnoticed.
        assertEquals(listOf("Note.md", "scan.pdf"), (result as CategoryDeletion.NotEmpty).remaining)
        assertTrue(files.exists("Keep/Note.md"))
        assertNotNull(dav.nodes["Keep/scan.pdf"])

        // Moving the note out is not enough while the PDF is there.
        runBlocking { repo.move("Keep/Note.md", newParent = "Empty") }
        val second = runBlocking { repo.deleteCategory("Keep") }
        assertEquals(listOf("scan.pdf"), (second as CategoryDeletion.NotEmpty).remaining)
    }

    @Test
    fun `moving a note between categories and back out`() {
        dav.putFile("A/Note.md", "body")
        dav.mkdir("B")
        sync()

        assertEquals("B/Note.md", runBlocking { repo.move("A/Note.md", newParent = "B") })
        sync()
        assertEquals("body", dav.text("B/Note.md"))
        assertNull(dav.text("A/Note.md"))

        assertEquals("Note.md", runBlocking { repo.move("B/Note.md", newParent = "") })
        sync()
        assertEquals("body", dav.text("Note.md"))
        assertNull(dav.text("B/Note.md"))
    }

    @Test
    fun `categories exclude the archive, asset folders and folders without notes`() {
        dav.putFile("Tech/Note.md", "x")
        dav.putFile("Tech/Note.assets/i.png", "PNG")
        dav.putFile("_archive/Old.md", "y")
        dav.putFile("img/shared.png", "PNG") // an image dump, not a category
        sync()
        assertEquals(listOf("Tech"), categories())

        // An empty folder the user made is a category from the start.
        runBlocking { repo.createCategory("Later") }
        assertEquals(listOf("Later", "Tech"), categories())
    }
}
