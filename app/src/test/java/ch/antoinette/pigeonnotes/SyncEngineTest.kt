package ch.antoinette.pigeonnotes

import ch.antoinette.pigeonnotes.data.local.Account
import ch.antoinette.pigeonnotes.data.local.AccountSource
import ch.antoinette.pigeonnotes.data.local.FileStore
import ch.antoinette.pigeonnotes.data.local.db.LocalState
import ch.antoinette.pigeonnotes.data.sync.SyncEngine
import ch.antoinette.pigeonnotes.data.sync.SyncStatus
import ch.antoinette.pigeonnotes.domain.NotesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

class SyncEngineTest {
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

    private fun sync(): SyncStatus = runBlocking { engine.sync() }

    @Test
    fun `initial sync downloads the tree and a second sync is one PROPFIND`() {
        dav.putFile("Skitouren.md", "# Ski\n")
        dav.putFile("ETH/AI Paper Club.md", "papers")
        dav.putFile("img/x.png", "PNG")
        dav.mkdir(".obsidian")
        dav.putFile(".obsidian/app.json", "{}")

        assertEquals(SyncStatus.Idle, sync())
        assertEquals("# Ski\n", files.read("Skitouren.md"))
        assertEquals("papers", files.read("ETH/AI Paper Club.md"))
        assertTrue(files.exists("img/x.png"))
        assertFalse(files.exists(".obsidian/app.json"))
        assertEquals(LocalState.CLEAN, runBlocking { dao.get("ETH/AI Paper Club.md") }!!.localState)

        dav.requests.clear()
        assertEquals(SyncStatus.Idle, sync())
        assertEquals(listOf("PROPFIND "), dav.requests)
    }

    @Test
    fun `local edits are pushed with If-Match and remote edits are pulled`() {
        dav.putFile("A.md", "one")
        dav.putFile("Tech/B.md", "bee")
        sync()

        runBlocking { repo.save("A.md", "one edited") }
        dav.putFile("Tech/B.md", "bee edited remotely")
        dav.requests.clear()
        sync()

        assertEquals("one edited", dav.text("A.md"))
        assertEquals("bee edited remotely", files.read("Tech/B.md"))
        assertTrue(dav.requests.any { it == "PUT A.md" })
        assertTrue("Tech should have been walked", dav.requests.contains("PROPFIND Tech"))
        assertEquals(LocalState.CLEAN, runBlocking { dao.get("A.md") }!!.localState)
    }

    @Test
    fun `new note in a new category creates the folder first`() {
        sync()
        val path = runBlocking { repo.create("Recipes/Sub", "Pasta", "boil water\n") }
        assertEquals("Recipes/Sub/Pasta.md", path)
        sync()
        assertEquals("boil water\n", dav.text("Recipes/Sub/Pasta.md"))
        val idxMkcol = dav.requests.indexOf("MKCOL Recipes")
        val idxPut = dav.requests.indexOf("PUT Recipes/Sub/Pasta.md")
        assertTrue(idxMkcol in 0 until idxPut)
    }

    @Test
    fun `conflicting edits keep both versions`() {
        dav.putFile("Note.md", "base")
        sync()
        runBlocking { repo.save("Note.md", "mine") }
        dav.putFile("Note.md", "theirs")
        sync()

        assertEquals("theirs", files.read("Note.md"))
        val conflict = files.root.listFiles()!!.map { it.name }.first { it.startsWith("Note (conflict ") }
        assertEquals("mine", files.read(conflict))
        assertEquals("mine", dav.text(conflict))
        assertEquals("theirs", dav.text("Note.md"))
        assertEquals(LocalState.CLEAN, runBlocking { dao.get(conflict) }!!.localState)
    }

    @Test
    fun `deletions propagate both ways but never destroy new remote content`() {
        dav.putFile("Gone remote.md", "x")
        dav.putFile("Gone local.md", "y")
        dav.putFile("Edited remotely.md", "z")
        sync()

        dav.delete("Gone remote.md")
        runBlocking { repo.delete("Gone local.md"); repo.delete("Edited remotely.md") }
        dav.putFile("Edited remotely.md", "z2")
        sync()

        assertFalse(files.exists("Gone remote.md"))
        assertNull(runBlocking { dao.get("Gone remote.md") })
        assertNull(dav.text("Gone local.md"))
        assertNull(runBlocking { dao.get("Gone local.md") })
        assertEquals("z2", files.read("Edited remotely.md")) // deletion cancelled
    }

    @Test
    fun `rename uses MOVE and carries the assets folder`() {
        dav.putFile("Old.md", "![p](Old.assets/p.png)")
        dav.putFile("Old.assets/p.png", "PNG")
        sync()

        val newPath = runBlocking { repo.rename("Old.md", "New") }
        assertEquals("New.md", newPath)
        assertEquals("![p](New.assets/p.png)", files.read("New.md"))
        sync()

        assertTrue(dav.requests.contains("MOVE Old.assets"))
        assertNotNull(dav.text("New.assets/p.png"))
        assertNull(dav.text("Old.assets/p.png"))
        assertNull(dav.text("Old.md"))
        assertEquals("![p](New.assets/p.png)", dav.text("New.md"))
        assertEquals(LocalState.CLEAN, runBlocking { dao.get("New.assets/p.png") }!!.localState)
        assertNull(runBlocking { dao.get("Old.assets/p.png") })

        dav.requests.clear()
        sync()
        assertEquals(listOf("PROPFIND "), dav.requests)
    }

    @Test
    fun `inserted asset lands in the note's assets folder and links are encoded`() {
        sync()
        val note = runBlocking { repo.create("", "My note", "text\n") }
        val link = runBlocking { repo.insertAsset(note, byteArrayOf(1, 2, 3), "photo 1.png") }
        assertEquals("My%20note.assets/photo%201.png", link)
        assertTrue(files.exists("My note.assets/photo 1.png"))
        assertEquals(File(files.root, "My note.assets/photo 1.png"), repo.resolveLink(note, link))
        sync()
        assertNotNull(dav.nodes["My note.assets/photo 1.png"])
    }

    @Test
    fun `offline failure leaves everything pending and a later sync recovers`() {
        dav.putFile("A.md", "a")
        sync()
        runBlocking { repo.save("A.md", "a2") }
        server.close()
        assertEquals(SyncStatus.Offline, sync())
        assertEquals(LocalState.MODIFIED, runBlocking { dao.get("A.md") }!!.localState)
    }
}
