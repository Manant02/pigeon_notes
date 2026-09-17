package ch.antoinette.pigeonnotes.domain

import ch.antoinette.pigeonnotes.data.local.FileStore
import ch.antoinette.pigeonnotes.data.local.db.LocalState
import ch.antoinette.pigeonnotes.data.local.db.SyncDao
import ch.antoinette.pigeonnotes.data.local.db.SyncEntry
import ch.antoinette.pigeonnotes.data.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * All local note operations. Every change lands on disk immediately and is recorded in the sync
 * bookkeeping; the engine pushes it whenever the server is reachable.
 */
class NotesRepository(
    private val files: FileStore,
    private val dao: SyncDao,
    private val sync: SyncEngine,
) {
    val notes: Flow<List<SyncEntry>> = dao.observeNotes()
    val pendingCount: Flow<Int> = dao.observePendingCount()

    fun observe(path: String): Flow<SyncEntry?> = dao.observe(path)

    suspend fun read(path: String): String = withContext(Dispatchers.IO) {
        if (files.exists(path)) files.read(path) else ""
    }

    /** Writes the note and schedules a push. No-op if the text is unchanged. */
    suspend fun save(path: String, text: String) = withContext(Dispatchers.IO) {
        if (files.exists(path) && files.read(path) == text) return@withContext
        val mtime = files.write(path, text)
        val current = dao.get(path)
        val state = if (current == null || current.localState == LocalState.NEW) LocalState.NEW else LocalState.MODIFIED
        dao.upsert(
            (current ?: SyncEntry(path, isDir = false, baseEtag = null, localState = LocalState.NEW)).copy(
                localState = state,
                title = NoteText.title(path),
                preview = NoteText.preview(text),
                modified = mtime,
                size = text.length.toLong(),
            )
        )
        sync.requestSync(delayMillis = SAVE_SYNC_DELAY_MS)
    }

    /** Creates an empty note in [category] (`""` = root) with a unique name and returns its path. */
    suspend fun create(category: String, title: String = "Untitled", initialText: String = ""): String =
        withContext(Dispatchers.IO) {
            ensureDir(category)
            val path = uniquePath(category, NoteText.sanitizeFileName(title), ".md")
            val mtime = files.write(path, initialText)
            dao.upsert(
                SyncEntry(
                    path, isDir = false, baseEtag = null, localState = LocalState.NEW,
                    title = NoteText.title(path), preview = NoteText.preview(initialText), modified = mtime,
                )
            )
            path
        }

    /** Renames a note (and its assets folder, rewriting the links inside). Returns the new path. */
    suspend fun rename(path: String, newTitle: String): String = withContext(Dispatchers.IO) {
        val entry = dao.get(path) ?: return@withContext path
        val clean = NoteText.sanitizeFileName(newTitle)
        if (clean == NoteText.title(path)) return@withContext path
        val newPath = uniquePath(entry.parent, clean, ".md")

        val oldAssets = assetDir(path)
        val newAssets = assetDir(newPath)
        var text = files.read(path)
        if (files.exists(oldAssets)) {
            val (oldPrefix, newPrefix) = assetLinkPrefix(oldAssets) to assetLinkPrefix(newAssets)
            text = text
                .replace(encodeLink(oldPrefix), encodeLink(newPrefix))
                .replace(oldPrefix, newPrefix)
            files.move(oldAssets, newAssets)
            rekey(oldAssets, newAssets, isDir = true)
        }
        files.write(path, text)
        files.move(path, newPath)
        rekey(path, newPath, isDir = false)
        val moved = dao.get(newPath)!!
        dao.upsert(moved.copy(title = NoteText.title(newPath), preview = NoteText.preview(text), modified = files.file(newPath).lastModified()))
        sync.requestSync(delayMillis = SAVE_SYNC_DELAY_MS)
        newPath
    }

    /** Deletes a note and its assets folder locally; the deletion is propagated on the next sync. */
    suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        markDeleted(path)
        val assets = assetDir(path)
        if (files.exists(assets)) {
            dao.underPrefix("$assets/").forEach { markDeleted(it.path) }
            markDeleted(assets)
            files.delete(assets)
        }
        files.delete(path)
        sync.requestSync(delayMillis = SAVE_SYNC_DELAY_MS)
    }

    /**
     * Stores [bytes] as an asset of the note at [notePath] and returns the relative link to put in
     * the markdown, e.g. `My note.assets/photo.jpg`. The folder is created on first use only.
     */
    suspend fun insertAsset(notePath: String, bytes: ByteArray, suggestedName: String): String =
        withContext(Dispatchers.IO) {
            val dir = assetDir(notePath)
            if (dao.get(dir) == null) {
                files.mkdir(dir)
                dao.upsert(SyncEntry(dir, isDir = true, baseEtag = null, localState = LocalState.NEW))
            }
            val clean = NoteText.sanitizeFileName(suggestedName)
            val dot = clean.lastIndexOf('.')
            val (stem, ext) = if (dot > 0) clean.substring(0, dot) to clean.substring(dot) else clean to ""
            val assetPath = uniquePath(dir, stem, ext)
            val mtime = files.writeBytes(assetPath, bytes)
            dao.upsert(SyncEntry(assetPath, isDir = false, baseEtag = null, localState = LocalState.NEW, modified = mtime, size = bytes.size.toLong()))
            sync.requestSync(delayMillis = SAVE_SYNC_DELAY_MS)
            encodeLink(assetPath.removePrefix(if (notePath.contains('/')) notePath.substringBeforeLast('/') + "/" else ""))
        }

    /** Resolves a relative markdown link (e.g. `../img/x.png`) against the note's directory. */
    fun resolveLink(notePath: String, link: String): File? {
        if (link.startsWith("http://") || link.startsWith("https://") || link.startsWith("data:")) return null
        val noteDir = files.file(notePath).parentFile ?: files.root
        val decoded = link.substringBefore('#').substringBefore('?').let { l ->
            runCatching { java.net.URLDecoder.decode(l.replace("+", "%2B"), "UTF-8") }.getOrDefault(l)
        }
        return File(noteDir, decoded).canonicalFile.takeIf { it.startsWith(files.root.canonicalFile) }
    }

    fun assetDir(notePath: String): String = notePath.removeSuffix(".md") + ".assets"

    private fun assetLinkPrefix(assetDirPath: String) = assetDirPath.substringAfterLast('/') + "/"

    private suspend fun ensureDir(dir: String) {
        if (dir.isEmpty()) return
        ensureDir(if ('/' in dir) dir.substringBeforeLast('/') else "")
        if (dao.get(dir) == null) {
            files.mkdir(dir)
            dao.upsert(SyncEntry(dir, isDir = true, baseEtag = null, localState = LocalState.NEW))
        }
    }

    private suspend fun uniquePath(dir: String, stem: String, ext: String): String {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        var candidate = "$prefix$stem$ext"
        var n = 2
        while (files.exists(candidate) || dao.get(candidate)?.localState?.let { it != LocalState.DELETED } == true) {
            candidate = "$prefix$stem $n$ext"; n++
        }
        return candidate
    }

    private suspend fun markDeleted(path: String) {
        val e = dao.get(path) ?: return
        if (e.localState == LocalState.NEW && e.movedFrom == null) dao.delete(path) // never reached the server
        else dao.upsert(e.copy(localState = LocalState.DELETED, movedFrom = null))
    }

    /**
     * Re-keys bookkeeping after a local move. A CLEAN source becomes a MOVE for the engine
     * (`movedFrom`); a MODIFIED or NEW source is simply re-created at the new path.
     */
    private suspend fun rekey(from: String, to: String, isDir: Boolean) {
        val old = dao.get(from)
        val children = if (isDir) dao.underPrefix("$from/") else emptyList()
        for (c in children) {
            dao.delete(c.path)
            val newChildPath = to + c.path.removePrefix(from)
            dao.upsert(c.copy(path = newChildPath, localState = LocalState.NEW, movedFrom = null, baseEtag = null, syncError = null))
            if (c.baseEtag != null) dao.upsert(c.copy(localState = LocalState.DELETED, movedFrom = null))
        }
        if (old == null) {
            dao.upsert(SyncEntry(to, isDir = isDir, baseEtag = null, localState = LocalState.NEW)); return
        }
        val canMove = old.localState == LocalState.CLEAN && old.baseEtag != null
        dao.upsert(
            old.copy(
                path = to, localState = LocalState.NEW, baseEtag = null, syncError = null,
                movedFrom = if (canMove) from else null,
            )
        )
        if (old.baseEtag != null) dao.upsert(old.copy(localState = LocalState.DELETED, movedFrom = null))
        else dao.delete(from)
    }

    companion object {
        const val SAVE_SYNC_DELAY_MS = 1500L

        /** Percent-encodes the characters that would break a CommonMark link destination. */
        fun encodeLink(relPath: String): String = buildString(relPath.length + 8) {
            for (c in relPath) {
                when (c) {
                    '%' -> append("%25"); ' ' -> append("%20"); '(' -> append("%28"); ')' -> append("%29")
                    '#' -> append("%23"); '?' -> append("%3F"); '<' -> append("%3C"); '>' -> append("%3E")
                    else -> append(c)
                }
            }
        }
    }
}
