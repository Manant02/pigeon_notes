package ch.antoinette.pigeonnotes

import ch.antoinette.pigeonnotes.data.local.db.SyncDao
import ch.antoinette.pigeonnotes.data.local.db.SyncEntry
import ch.antoinette.pigeonnotes.data.local.db.LocalState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tiny in-memory WebDAV server: enough of PROPFIND/GET/PUT/MKCOL/MOVE/DELETE semantics
 * (including ETags, If-Match, If-None-Match and Nextcloud-style directory ETag propagation)
 * to drive the sync engine end to end.
 */
class FakeDav(private val prefix: String = "/remote.php/dav/files/me/Notes") : Dispatcher() {
    class Node(var content: ByteArray?, var etag: String) {
        val isDir get() = content == null
    }

    val nodes = sortedMapOf<String, Node>()
    private val counter = AtomicInteger(1)
    var requests = ArrayList<String>()
    val propfinds get() = requests.count { it.startsWith("PROPFIND") }
    /** Runs while a PUT is being served, to simulate a client editing during the upload. */
    var duringPut: ((String) -> Unit)? = null

    init {
        nodes[""] = Node(null, nextEtag())
    }

    private fun nextEtag() = "e" + counter.getAndIncrement()

    fun putFile(path: String, text: String) {
        parents(path).forEach { d -> if (d !in nodes) nodes[d] = Node(null, nextEtag()) }
        nodes[path] = Node(text.toByteArray(), nextEtag())
        touch(path)
    }

    fun mkdir(path: String) {
        parents(path).forEach { d -> if (d !in nodes) nodes[d] = Node(null, nextEtag()) }
        nodes[path] = Node(null, nextEtag()); touch(path)
    }

    fun delete(path: String) {
        nodes.keys.filter { it == path || it.startsWith("$path/") }.toList().forEach { nodes.remove(it) }
        touch(path)
    }

    fun text(path: String) = nodes[path]?.content?.decodeToString()

    private fun parents(path: String): List<String> {
        val out = ArrayList<String>()
        var p = path
        while ('/' in p) {
            p = p.substringBeforeLast('/'); out += p
        }
        return out.reversed()
    }

    /** Nextcloud changes every ancestor directory's ETag when something below changes. */
    private fun touch(path: String) {
        (parents(path) + "").distinct().forEach { d -> nodes[d]?.etag = nextEtag() }
    }

    private fun relPath(request: RecordedRequest): String {
        val raw = request.url.encodedPath
        val decoded = URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        return decoded.removePrefix(prefix).trim('/')
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = relPath(request)
        requests += "${request.method} $path"
        val ifMatch = request.headers["If-Match"]?.trim('"')
        val ifNoneMatch = request.headers["If-None-Match"]
        return when (request.method) {
            "PROPFIND" -> {
                val node = nodes[path] ?: return MockResponse(code = 404)
                val depth = request.headers["Depth"] ?: "1"
                val listed = if (depth == "0" || !node.isDir) listOf(path)
                else listOf(path) + nodes.keys.filter { it != path && it.startsWith(if (path.isEmpty()) "" else "$path/") && !it.removePrefix(if (path.isEmpty()) "" else "$path/").contains('/') }
                val body = buildString {
                    append("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">")
                    for (p in listed) {
                        val n = nodes[p]!!
                        val href = prefix + (if (p.isEmpty()) "" else "/" + p.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }) + (if (n.isDir) "/" else "")
                        append("<d:response><d:href>$href</d:href><d:propstat><d:prop>")
                        append("<d:getetag>\"${n.etag}\"</d:getetag>")
                        append("<d:getlastmodified>Wed, 17 Sep 2026 10:00:00 GMT</d:getlastmodified>")
                        if (n.isDir) append("<d:resourcetype><d:collection/></d:resourcetype>")
                        else append("<d:resourcetype/><d:getcontentlength>${n.content!!.size}</d:getcontentlength>")
                        append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
                    }
                    append("</d:multistatus>")
                }
                MockResponse(code = 207, body = body)
            }

            "GET" -> {
                val node = nodes[path] ?: return MockResponse(code = 404)
                MockResponse.Builder().code(200).body(node.content!!.decodeToString()).addHeader("ETag", "\"${node.etag}\"").build()
            }

            "PUT" -> {
                duringPut?.invoke(path)
                val existing = nodes[path]
                if (ifNoneMatch == "*" && existing != null) return MockResponse(code = 412)
                if (ifMatch != null && (existing == null || existing.etag != ifMatch)) return MockResponse(code = 412)
                if (parents(path).any { it !in nodes }) return MockResponse(code = 409)
                val node = Node(request.body!!.utf8().toByteArray(), nextEtag())
                nodes[path] = node; touch(path)
                MockResponse.Builder().code(if (existing == null) 201 else 204).addHeader("OC-ETag", "\"${node.etag}\"").build()
            }

            "MKCOL" -> {
                if (path in nodes) return MockResponse(code = 405)
                nodes[path] = Node(null, nextEtag()); touch(path)
                MockResponse(code = 201)
            }

            "DELETE" -> {
                val existing = nodes[path] ?: return MockResponse(code = 404)
                if (ifMatch != null && existing.etag != ifMatch) return MockResponse(code = 412)
                delete(path)
                MockResponse(code = 204)
            }

            "MOVE" -> {
                val dest = request.headers["Destination"]!!
                val destPath = URLDecoder.decode(java.net.URI(dest).rawPath.replace("+", "%2B"), "UTF-8").removePrefix(prefix).trim('/')
                if (path !in nodes) return MockResponse(code = 404)
                if (destPath in nodes) return MockResponse(code = 412)
                val moved = nodes.keys.filter { it == path || it.startsWith("$path/") }.toList()
                for (p in moved) {
                    val n = nodes.remove(p)!!
                    nodes[destPath + p.removePrefix(path)] = n
                }
                touch(path); touch(destPath)
                MockResponse(code = 201)
            }

            else -> MockResponse(code = 405)
        }
    }
}

class FakeSyncDao : SyncDao {
    val rows = MutableStateFlow<Map<String, SyncEntry>>(emptyMap())

    override suspend fun all() = rows.value.values.toList()
    override suspend fun get(path: String) = rows.value[path]
    override fun observe(path: String): Flow<SyncEntry?> = rows.map { it[path] }
    override suspend fun underPrefix(prefix: String) = rows.value.values.filter { it.path.startsWith(prefix) }
    override fun observeNotes(): Flow<List<SyncEntry>> =
        rows.map { m -> m.values.filter { it.isNote && it.localState != LocalState.DELETED }.sortedByDescending { it.modified } }
    override fun observeDirs(): Flow<List<SyncEntry>> =
        rows.map { m -> m.values.filter { it.isDir && it.localState != LocalState.DELETED } }
    override fun observeFiles(): Flow<List<SyncEntry>> =
        rows.map { m -> m.values.filter { !it.isDir && it.localState != LocalState.DELETED } }
    override fun observePendingCount(): Flow<Int> = rows.map { m -> m.values.count { it.localState != LocalState.CLEAN } }
    override fun observeErrorCount(): Flow<Int> = rows.map { m -> m.values.count { it.syncError != null } }
    override suspend fun upsert(entry: SyncEntry) { rows.value = rows.value + (entry.path to entry) }
    override suspend fun markEdited(path: String, state: LocalState, title: String, preview: String, modified: Long, size: Long): Int {
        val e = rows.value[path] ?: return 0
        upsert(e.copy(localState = state, title = title, preview = preview, modified = modified, size = size))
        return 1
    }
    override suspend fun markUploaded(path: String, etag: String?, seenModified: Long) {
        val e = rows.value[path] ?: return
        val state = if (e.modified == seenModified) LocalState.CLEAN else e.localState
        upsert(e.copy(baseEtag = etag, movedFrom = null, syncError = null, localState = state))
    }
    override suspend fun upsertAll(entries: List<SyncEntry>) = entries.forEach { upsert(it) }
    override suspend fun delete(path: String) { rows.value = rows.value - path }
    override suspend fun clear() { rows.value = emptyMap() }
}
