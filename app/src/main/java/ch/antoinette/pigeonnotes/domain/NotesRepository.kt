package ch.antoinette.pigeonnotes.domain

import ch.antoinette.pigeonnotes.data.local.FileStore
import ch.antoinette.pigeonnotes.data.local.db.LocalState
import ch.antoinette.pigeonnotes.data.local.db.SyncDao
import ch.antoinette.pigeonnotes.data.local.db.SyncEntry
import ch.antoinette.pigeonnotes.data.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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

    /**
     * Category folders, sorted alphabetically: those holding notes, plus empty ones the user
     * created. A folder holding only other files (the shared `img/` folder, say) is not a category,
     * and neither is the archive tree or a per-note `.assets` folder.
     */
    val categories: Flow<List<String>> = combine(dao.observeDirs(), dao.observeFiles()) { dirs, files ->
        val noteParents = files.filter { it.isNote }.map { it.parent }.toSet()
        val candidates = (dirs.map { it.path } + noteParents).distinct().filter { it.isCategory() }
        candidates
            .filter { dir -> dir in noteParents || files.none { it.path.startsWith("$dir/") } }
            .sortedBy { it.lowercase() }
    }

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
        val title = NoteText.title(path)
        val preview = NoteText.preview(text)
        val size = text.length.toLong()
        // A partial update: the engine may store a fresh ETag on this row while we type, and a
        // full upsert from a stale copy would put the old one back and manufacture a conflict.
        if (dao.markEdited(path, state, title, preview, mtime, size) == 0) {
            dao.upsert(SyncEntry(path, isDir = false, baseEtag = null, localState = LocalState.NEW, title = title, preview = preview, modified = mtime, size = size))
        }
        sync.requestSync(delayMillis = SAVE_SYNC_DELAY_MS)
    }

    /**
     * Recomputes the list index (title, preview, mtime) for every note from the files on disk.
     * Cheap for a few hundred notes and keeps previews correct after the formatting rules change.
     */
    suspend fun reindexAll() = withContext(Dispatchers.IO) {
        for (e in dao.all()) {
            if (!e.isNote || e.localState == LocalState.DELETED) continue
            val f = files.file(e.path)
            if (!f.exists()) continue
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            val title = NoteText.title(e.path)
            val preview = NoteText.preview(text)
            if (e.title != title || e.preview != preview || e.modified != f.lastModified()) {
                dao.upsert(e.copy(title = title, preview = preview, modified = f.lastModified(), size = f.length()))
            }
        }
    }

    /** Creates the folder for a new category and returns its path, or null if the name is taken. */
    suspend fun createCategory(name: String): String? = withContext(Dispatchers.IO) {
        val path = NoteText.sanitizeFileName(name)
        if (path.isEmpty() || path.isArchive()) return@withContext null
        if (files.exists(path) || dao.get(path) != null) return@withContext null
        files.mkdir(path)
        dao.upsert(SyncEntry(path, isDir = true, baseEtag = null, localState = LocalState.NEW))
        sync.requestSync(delayMillis = SAVE_SYNC_DELAY_MS)
        path
    }

    /** Renames a category folder, carrying every note and asset inside it. Returns the new path. */
    suspend fun renameCategory(path: String, newName: String): String = withContext(Dispatchers.IO) {
        val entry = dao.get(path) ?: return@withContext path
        val clean = NoteText.sanitizeFileName(newName)
        if (clean.isEmpty() || clean == path.substringAfterLast('/')) return@withContext path
        val parent = if ('/' in path) path.substringBeforeLast('/') + "/" else ""
        val newPath = parent + clean
        if (files.exists(newPath) || dao.get(newPath) != null) return@withContext path
        files.move(path, newPath)
        rekey(path, newPath, isDir = true)
        sync.requestSync(delayMillis = SAVE_SYNC_DELAY_MS)
        newPath
    }

    /** What stopped a category from being deleted. */
    sealed interface CategoryDeletion {
        data object Deleted : CategoryDeletion
        /** [remaining] are the files still in the folder, relative to it. */
        data class NotEmpty(val remaining: List<String>) : CategoryDeletion
    }

    /**
     * Deletes a category, but only when its folder holds nothing at all — notes, images and any
     * other file count, so nothing is ever removed from the server unnoticed.
     */
    suspend fun deleteCategory(path: String): CategoryDeletion = withContext(Dispatchers.IO) {
        val dir = files.file(path)
        val onDisk = if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.map {
            it.relativeTo(dir).path
        }.toList() else emptyList()
        val pending = dao.underPrefix("$path/").filter { it.localState != LocalState.DELETED && !it.isDir }.map { it.path.removePrefix("$path/") }
        val remaining = (onDisk + pending).distinct().sorted()
        if (remaining.isNotEmpty()) return@withContext CategoryDeletion.NotEmpty(remaining)
        dao.underPrefix("$path/").forEach { markDeleted(it.path) }
        markDeleted(path)
        files.delete(path)
        sync.requestSync(delayMillis = SAVE_SYNC_DELAY_MS)
        CategoryDeletion.Deleted
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
    suspend fun rename(path: String, newTitle: String): String = move(path, newTitle = newTitle)

    /**
     * Moves a note to [newParent] (`""` = root, null = keep) and/or gives it [newTitle] (null = keep),
     * carrying its assets folder along and rewriting the links inside. Returns the new path.
     */
    suspend fun move(path: String, newParent: String? = null, newTitle: String? = null): String = withContext(Dispatchers.IO) {
        val entry = dao.get(path) ?: return@withContext path
        val parent = newParent ?: entry.parent
        val clean = newTitle?.let { NoteText.sanitizeFileName(it) } ?: NoteText.title(path)
        if (parent == entry.parent && clean == NoteText.title(path)) return@withContext path
        ensureDir(parent)
        val newPath = uniquePath(parent, clean, ".md")

        val oldAssets = assetDir(path)
        val newAssets = assetDir(newPath)
        val original = files.read(path)
        var text = original
        // Another note in the folder (typically a conflict copy) may share the assets: then they stay put.
        if (files.exists(oldAssets) && !referencedByOtherNotes(oldAssets, except = path)) {
            val (oldPrefix, newPrefix) = assetLinkPrefix(oldAssets) to assetLinkPrefix(newAssets)
            text = text
                .replace(encodeLink(oldPrefix), encodeLink(newPrefix))
                .replace(oldPrefix, newPrefix)
            files.move(oldAssets, newAssets)
            rekey(oldAssets, newAssets, isDir = true)
        }
        if (text != original) files.write(path, text)
        files.move(path, newPath)
        // A MOVE would carry the server's old content, so a rewritten note is uploaded fresh instead.
        rekey(path, newPath, isDir = false, allowMove = text == original)
        val moved = dao.get(newPath)!!
        dao.upsert(moved.copy(title = NoteText.title(newPath), preview = NoteText.preview(text), modified = files.file(newPath).lastModified()))
        sync.requestSync(delayMillis = SAVE_SYNC_DELAY_MS)
        newPath
    }

    /**
     * Deletes a note locally; the deletion is propagated on the next sync. Assets folders the note
     * used (its own and any other `*.assets` in the same folder it links to) go with it, unless
     * another note in that folder still references them.
     */
    suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val text = if (files.exists(path)) files.read(path) else ""
        markDeleted(path)
        files.delete(path)
        val candidates = (listOf(assetDir(path)) + assetDirsIn(entryParent(path)).filter { references(text, it) }).distinct()
        for (assets in candidates) {
            if (!files.exists(assets) || referencedByOtherNotes(assets, except = path)) continue
            dao.underPrefix("$assets/").forEach { markDeleted(it.path) }
            markDeleted(assets)
            files.delete(assets)
        }
        sync.requestSync(delayMillis = SAVE_SYNC_DELAY_MS)
    }

    /** `*.assets` folders that exist in [dir] (`""` = root). */
    private fun assetDirsIn(dir: String): List<String> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        return files.file(dir).listFiles().orEmpty()
            .filter { it.isDirectory && it.name.endsWith(".assets") }
            .map { prefix + it.name }
    }

    /** True if any note in the folder of [assetDirPath], other than [except], links into it. */
    private fun referencedByOtherNotes(assetDirPath: String, except: String): Boolean {
        val dir = entryParent(assetDirPath)
        return files.file(dir).listFiles().orEmpty().any { f ->
            val rel = (if (dir.isEmpty()) "" else "$dir/") + f.name
            f.isFile && f.name.endsWith(".md", ignoreCase = true) && rel != except &&
                references(runCatching { f.readText() }.getOrDefault(""), assetDirPath)
        }
    }

    /**
     * True if [text] links into the folder at [assetDirPath], i.e. contains `Name.assets/` (raw or
     * percent-encoded) as the start of a relative path — `../Name.assets/` or `MyName.assets/` do not count.
     */
    private fun references(text: String, assetDirPath: String): Boolean {
        val prefix = assetLinkPrefix(assetDirPath)
        for (needle in listOf(prefix, encodeLink(prefix)).distinct()) {
            var i = text.indexOf(needle)
            while (i >= 0) {
                if (startsPath(text, i)) return true
                i = text.indexOf(needle, i + 1)
            }
        }
        return false
    }

    private fun startsPath(text: String, at: Int): Boolean {
        if (at == 0) return true
        val prev = text[at - 1]
        if (prev == '/') return at >= 2 && text[at - 2] == '.' && (at < 3 || text[at - 3] != '.')
        return !(prev.isLetterOrDigit() || prev == '_' || prev == '-' || prev == '.' || prev == '%')
    }

    private fun entryParent(path: String) = if ('/' in path) path.substringBeforeLast('/') else ""

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

    private fun String.isArchive() = this == ARCHIVE_DIR || startsWith("$ARCHIVE_DIR/")

    private fun String.isCategory() =
        isNotEmpty() && !isArchive() && !endsWith(".assets") && !contains(".assets/")

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
    private suspend fun rekey(from: String, to: String, isDir: Boolean, allowMove: Boolean = true) {
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
        val canMove = allowMove && old.localState == LocalState.CLEAN && old.baseEtag != null
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

        /** Notes under this folder live in the archive space. */
        const val ARCHIVE_DIR = "_archive"

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
