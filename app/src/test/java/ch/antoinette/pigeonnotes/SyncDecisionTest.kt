package ch.antoinette.pigeonnotes

import ch.antoinette.pigeonnotes.data.local.db.LocalState.CLEAN
import ch.antoinette.pigeonnotes.data.local.db.LocalState.DELETED
import ch.antoinette.pigeonnotes.data.local.db.LocalState.MODIFIED
import ch.antoinette.pigeonnotes.data.local.db.LocalState.NEW
import ch.antoinette.pigeonnotes.data.sync.Action
import ch.antoinette.pigeonnotes.data.sync.SyncDecision.decide
import ch.antoinette.pigeonnotes.data.sync.SyncEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDecisionTest {
    private val base = "e1"
    private val changed = "e2"

    @Test
    fun `unknown remote file is downloaded`() = assertEquals(Action.Download, decide(null, null, changed))

    @Test
    fun `clean rows`() {
        assertEquals(Action.None, decide(CLEAN, base, base))
        assertEquals(Action.Download, decide(CLEAN, base, changed))
        assertEquals(Action.DeleteLocal, decide(CLEAN, base, null))
        assertEquals(Action.None, decide(CLEAN, base, changed, isDir = true))
    }

    @Test
    fun `modified rows`() {
        assertEquals(Action.Upload(ifMatch = base, ifNoneMatch = false), decide(MODIFIED, base, base))
        assertEquals(Action.Conflict, decide(MODIFIED, base, changed))
        assertEquals(Action.Upload(ifMatch = null, ifNoneMatch = true), decide(MODIFIED, base, null))
    }

    @Test
    fun `new rows`() {
        assertEquals(Action.Upload(ifMatch = null, ifNoneMatch = true), decide(NEW, null, null))
        assertEquals(Action.Conflict, decide(NEW, null, changed))
        assertEquals(Action.CreateRemoteDir, decide(NEW, null, null, isDir = true))
        assertEquals(Action.None, decide(NEW, null, changed, isDir = true))
        assertEquals(Action.MoveRemote("old.md"), decide(NEW, null, null, movedFrom = "old.md"))
        assertEquals(Action.Conflict, decide(NEW, null, changed, movedFrom = "old.md"))
    }

    @Test
    fun `deleted rows`() {
        assertEquals(Action.DeleteRemote(ifMatch = base), decide(DELETED, base, base))
        assertEquals(Action.KeepRemote, decide(DELETED, base, changed))
        assertEquals(Action.DropEntry, decide(DELETED, base, null))
        assertEquals(Action.DeleteRemote(ifMatch = null), decide(DELETED, base, base, isDir = true))
    }

    @Test
    fun `conflict names keep folder and extension`() {
        val n = SyncEngine.conflictName("ETH/My note.md")
        assertTrue(n, n.startsWith("ETH/My note (conflict "))
        assertTrue(n, n.endsWith(").md"))
        assertTrue(SyncEngine.conflictName("photo.png").endsWith(").png"))
    }
}
