package ch.antoinette.pigeonnotes.data.sync

import android.util.Log
import ch.antoinette.pigeonnotes.data.local.FileStore
import ch.antoinette.pigeonnotes.data.local.AccountSource
import ch.antoinette.pigeonnotes.data.local.db.LocalState
import ch.antoinette.pigeonnotes.data.local.db.SyncDao
import ch.antoinette.pigeonnotes.data.local.db.SyncEntry
import ch.antoinette.pigeonnotes.data.remote.DavEntry
import ch.antoinette.pigeonnotes.data.remote.DavException
import ch.antoinette.pigeonnotes.data.remote.WebDavClient
import ch.antoinette.pigeonnotes.domain.NoteText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

sealed interface SyncStatus {
    data object NotConfigured : SyncStatus
    data object Idle : SyncStatus
    data class Syncing(val detail: String) : SyncStatus
    data object Offline : SyncStatus
    data class Error(val message: String) : SyncStatus
}

/**
 * Offline-first, ETag-driven synchronisation of the local mirror with the server.
 *
 * Walk: PROPFIND Depth 1 from the root, descending only into directories whose ETag differs from
 * the one we last recorded (Nextcloud propagates ETag changes upward). Entries under unwalked
 * directories are assumed unchanged remotely, so local changes there still get pushed.
 */
class SyncEngine(
    private val settings: AccountSource,
    private val dao: SyncDao,
    private val files: FileStore,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var rerunRequested = false
    private var debounceJob: Job? = null

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    /** Schedule a sync; repeated calls within [delayMillis] collapse into one run. */
    fun requestSync(delayMillis: Long = 0) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            sync()
        }
    }

    /** Runs one full sync. Safe to call concurrently: a second caller queues one re-run. */
    suspend fun sync(): SyncStatus {
        if (mutex.isLocked) {
            rerunRequested = true
            return _status.value
        }
        return mutex.withLock {
            do {
                rerunRequested = false
                runOnce()
            } while (rerunRequested)
            _status.value
        }
    }

    private suspend fun runOnce() {
        val account = settings.currentAccount()
        if (account == null) {
            _status.value = SyncStatus.NotConfigured; return
        }
        val dav = WebDavClient(http, account)
        _status.value = SyncStatus.Syncing("Checking server…")
        try {
            val stats = Pass(dav).run()
            settings.markSynced()
            _status.value = SyncStatus.Idle
            log("done: ${stats.downloaded}↓ ${stats.uploaded}↑ ${stats.conflicts} conflicts, ${stats.propfinds} PROPFIND")
        } catch (e: DavException) {
            val msg = if (e.code == 401) "Unauthorized – log in again" else e.message ?: "HTTP ${e.code}"
            _status.value = SyncStatus.Error(msg)
            log("error: $msg")
        } catch (e: IOException) {
            _status.value = SyncStatus.Offline
            log("offline: ${e.message}")
        } catch (e: Exception) {
            _status.value = SyncStatus.Error(e.message ?: e.javaClass.simpleName)
            log("error: ${e.message}")
            runCatching { Log.e(TAG, "sync failed", e) }
        }
    }

    private class Stats {
        var downloaded = 0; var uploaded = 0; var conflicts = 0; var propfinds = 0
    }

    private inner class Pass(private val dav: WebDavClient) {
        private val stats = Stats()
        private val local = HashMap<String, SyncEntry>()
        private val remote = HashMap<String, DavEntry>()
        private val walkedDirs = HashSet<String>()
        /** Directories with a failed entry beneath them; their ETag is not advanced so the next pass revisits them. */
        private val errorDirs = HashSet<String>()

        suspend fun run(): Stats {
            dao.all().forEach { local[it.path] = it }
            walk("")

            // Every path either side knows about; directories first (shallow to deep) so MKCOL precedes PUT,
            // then files, then directory deletions last.
            val paths = (local.keys + remote.keys).distinct()
            val dirSet = paths.filter { (local[it]?.isDir ?: remote[it]?.isDir) == true }.toSet()
            val dirs = dirSet.sortedBy { it.count { c -> c == '/' } }
            val fileset = paths.filter { it !in dirSet }.sorted()

            for (p in dirs) if (local[p]?.localState != LocalState.DELETED) process(p)
            for (p in fileset) process(p)
            for (p in dirs.asReversed()) if (local[p]?.localState == LocalState.DELETED) process(p)

            // Only now record the walked directories' ETags: an ETag is a promise that everything
            // beneath it is in sync, which a failed child would break.
            for (d in walkedDirs) {
                val e = local[d] ?: continue
                val rEtag = remote[d]?.etag ?: continue
                if (e.localState == LocalState.CLEAN && e.baseEtag != rEtag && d !in errorDirs) save(e.copy(baseEtag = rEtag))
            }
            return stats
        }

        private fun markError(path: String) {
            var p = path
            while (true) {
                p = if ('/' in p) p.substringBeforeLast('/') else ""
                errorDirs += p
                if (p.isEmpty()) break
            }
        }

        private suspend fun walk(dir: String) {
            _status.value = SyncStatus.Syncing("Listing ${dir.ifEmpty { "/" }}")
            stats.propfinds++
            val entries = dav.propfind(dir, depth = 1)
            walkedDirs += dir
            for (e in entries) {
                if (e.path.isNotEmpty() && e.name.startsWith(".")) continue // .obsidian, .ztr-directory, …
                remote[e.path] = e
            }
            for (e in entries) {
                if (!e.isDir || e.path == dir || e.path.isEmpty() || e.name.startsWith(".")) continue
                val known = local[e.path]
                val unchanged = known != null && known.localState != LocalState.NEW && known.baseEtag == e.etag
                if (!unchanged) walk(e.path)
            }
        }

        /** Remote ETag for [path] as far as this pass can tell (assumed unchanged under unwalked dirs). */
        private fun remoteEtag(path: String, entry: SyncEntry?): String? {
            val parent = if ('/' in path) path.substringBeforeLast('/') else ""
            return if (parent in walkedDirs || path in walkedDirs || path.isEmpty()) remote[path]?.etag else entry?.baseEtag
        }

        private suspend fun process(path: String) {
            val entry = local[path]
            val isDir = entry?.isDir ?: remote[path]?.isDir ?: false
            val rEtag = remoteEtag(path, entry)
            val action = SyncDecision.decide(entry?.localState, entry?.baseEtag, rEtag, isDir, entry?.movedFrom)
            if (action == Action.None) return
            try {
                execute(path, entry, rEtag, isDir, action)
                entry?.let { if (it.syncError != null) local[path]?.let { cur -> save(cur.copy(syncError = null)) } }
            } catch (e: DavException) {
                if (e.code == 401 || e.code >= 500) throw e
                log("$path: ${e.message}")
                markError(path)
                (local[path] ?: entry)?.let { save(it.copy(syncError = e.message)) }
            }
        }

        private suspend fun execute(path: String, entry: SyncEntry?, rEtag: String?, isDir: Boolean, action: Action) {
            when (action) {
                Action.None -> Unit

                Action.Download -> if (isDir) {
                    files.mkdir(path)
                    // ETag recorded at the end of the pass, once the children are known to be in sync.
                    save(SyncEntry(path, isDir = true, baseEtag = null, localState = LocalState.CLEAN))
                } else download(path, rEtag)

                Action.DeleteLocal -> {
                    log("$path: removed remotely, deleting local copy")
                    files.delete(path); files.pruneEmptyParents(path)
                    drop(path)
                    if (isDir) local.keys.filter { it.startsWith("$path/") }.forEach { drop(it) }
                }

                Action.DropEntry -> drop(path)

                is Action.Upload -> upload(path, entry!!, action.ifMatch, action.ifNoneMatch)

                Action.CreateRemoteDir -> {
                    dav.mkcol(path)
                    val etag = dav.propfind(path, depth = 0).firstOrNull()?.etag
                    save(entry!!.copy(localState = LocalState.CLEAN, baseEtag = etag))
                }

                Action.Conflict -> conflict(path, entry!!, rEtag)

                is Action.DeleteRemote -> {
                    _status.value = SyncStatus.Syncing("Deleting $path")
                    try {
                        dav.delete(path, ifMatch = action.ifMatch, isDir = isDir)
                        drop(path)
                        if (isDir) local.keys.filter { it.startsWith("$path/") }.forEach { drop(it) }
                        log("$path: deleted remotely")
                    } catch (e: DavException) {
                        if (e.isPreconditionFailed) keepRemote(path, entry!!, isDir) else throw e
                    }
                }

                Action.KeepRemote -> keepRemote(path, entry!!, isDir)

                is Action.MoveRemote -> moveRemote(path, entry!!, action.from)
            }
        }

        private suspend fun download(path: String, etag: String?) {
            _status.value = SyncStatus.Syncing("Downloading $path")
            val newEtag = dav.download(path, files.file(path)) ?: etag
            stats.downloaded++
            save(indexed(SyncEntry(path, isDir = false, baseEtag = newEtag, localState = LocalState.CLEAN)))
        }

        private suspend fun upload(path: String, entry: SyncEntry, ifMatch: String?, ifNoneMatch: Boolean) {
            val f = files.file(path)
            if (!f.exists()) {
                drop(path); return
            }
            _status.value = SyncStatus.Syncing("Uploading $path")
            try {
                val etag = dav.upload(path, f, ifMatch, ifNoneMatch, f.lastModified(), contentTypeFor(path))
                stats.uploaded++
                save(entry.copy(localState = LocalState.CLEAN, baseEtag = etag ?: fetchEtag(path), movedFrom = null, syncError = null))
                log("$path: uploaded")
            } catch (e: DavException) {
                if (e.isPreconditionFailed) conflict(path, entry, fetchEtag(path)) else throw e
            }
        }

        private suspend fun fetchEtag(path: String): String? = dav.propfind(path, depth = 0).firstOrNull()?.etag

        /** Keep both: local content moves to a "(conflict …)" sibling that is uploaded as new; remote wins the original name. */
        private suspend fun conflict(path: String, entry: SyncEntry, rEtag: String?) {
            _status.value = SyncStatus.Syncing("Resolving conflict $path")
            val localFile = files.file(path)
            val tmp = File(localFile.parentFile, ".${localFile.name}.remote")
            val newEtag = dav.download(path, tmp) ?: rEtag
            if (localFile.exists() && tmp.readBytes().contentEquals(localFile.readBytes())) {
                tmp.delete()
                save(entry.copy(localState = LocalState.CLEAN, baseEtag = newEtag, movedFrom = null, syncError = null))
                return
            }
            stats.conflicts++
            val conflictPath = conflictName(path)
            if (localFile.exists()) files.move(path, conflictPath)
            tmp.renameTo(localFile)
            save(indexed(SyncEntry(path, isDir = false, baseEtag = newEtag, localState = LocalState.CLEAN)))
            val copy = indexed(SyncEntry(conflictPath, isDir = false, baseEtag = null, localState = LocalState.NEW))
            save(copy)
            log("$path: conflict, local copy kept as ${copy.name}")
            upload(conflictPath, copy, ifMatch = null, ifNoneMatch = true)
        }

        private suspend fun keepRemote(path: String, entry: SyncEntry, isDir: Boolean) {
            log("$path: changed remotely, cancelling local delete")
            if (isDir) {
                files.mkdir(path)
                save(entry.copy(localState = LocalState.CLEAN, baseEtag = remote[path]?.etag, syncError = null))
            } else download(path, remote[path]?.etag)
        }

        /**
         * Local rename. The repository already re-keyed the bookkeeping: [entry] is NEW at [path],
         * the row at [from] is DELETED, and children of a renamed directory are NEW at their new paths.
         * A remote MOVE is only attempted when the source is untouched on the server; otherwise we fall
         * back to plain creation here and let the DELETED row at [from] resolve on its own.
         */
        private suspend fun moveRemote(path: String, entry: SyncEntry, from: String) {
            val old = local[from]
            val oldRemote = remoteEtag(from, old)
            val sourceUntouched = old != null && old.localState == LocalState.DELETED &&
                old.baseEtag != null && oldRemote == old.baseEtag
            var moved = false
            if (sourceUntouched) {
                _status.value = SyncStatus.Syncing("Moving $from → $path")
                try {
                    dav.move(from, path, isDir = entry.isDir); moved = true
                } catch (e: DavException) {
                    if (!(e.isNotFound || e.isPreconditionFailed || e.isConflict)) throw e
                }
            }
            if (!moved) {
                if (entry.isDir) {
                    dav.mkcol(path)
                    save(entry.copy(localState = LocalState.CLEAN, baseEtag = fetchEtag(path), movedFrom = null, syncError = null))
                } else {
                    upload(path, entry.copy(movedFrom = null), ifMatch = null, ifNoneMatch = true)
                }
                return
            }
            drop(from)
            if (entry.isDir) {
                val listing = dav.propfind(path, depth = 1)
                stats.propfinds++
                for (child in listing) {
                    if (child.path == path) continue
                    local[child.path]?.let { save(it.copy(localState = LocalState.CLEAN, baseEtag = child.etag, movedFrom = null, syncError = null)) }
                }
                local.keys.filter { it.startsWith("$from/") }.forEach { drop(it) }
                val dirEtag = listing.firstOrNull { it.path == path }?.etag
                save(entry.copy(localState = LocalState.CLEAN, baseEtag = dirEtag, movedFrom = null, syncError = null))
            } else {
                save(entry.copy(localState = LocalState.CLEAN, baseEtag = fetchEtag(path), movedFrom = null, syncError = null))
            }
            log("$from → $path: moved remotely")
        }

        private fun indexed(e: SyncEntry): SyncEntry {
            val f = files.file(e.path)
            if (!e.isNote) return e.copy(modified = f.lastModified(), size = f.length())
            val text = runCatching { f.readText() }.getOrDefault("")
            return e.copy(
                title = NoteText.title(e.path),
                preview = NoteText.preview(text),
                modified = f.lastModified(),
                size = f.length(),
            )
        }

        private suspend fun save(e: SyncEntry) {
            local[e.path] = e; dao.upsert(e)
        }

        private suspend fun drop(path: String) {
            local.remove(path); dao.delete(path)
        }
    }

    private fun log(line: String) {
        runCatching { Log.d(TAG, line) } // android.util.Log is a stub in JVM unit tests
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        _log.update { (listOf("$stamp $line") + it).take(200) }
    }

    companion object {
        private const val TAG = "SyncEngine"

        fun conflictName(path: String): String {
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm"))
            val dir = if ('/' in path) path.substringBeforeLast('/') + "/" else ""
            val name = path.substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
            return "$dir$stem (conflict $stamp)$ext"
        }

        fun contentTypeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "md" -> "text/markdown; charset=utf-8"
            "txt" -> "text/plain; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }
}
