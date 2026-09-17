package ch.antoinette.pigeonnotes.data.remote

import ch.antoinette.pigeonnotes.data.local.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException

class DavException(val code: Int, message: String) : IOException("HTTP $code: $message") {
    val isPreconditionFailed get() = code == 412
    val isNotFound get() = code == 404
    val isConflict get() = code == 409
}

/**
 * Minimal WebDAV client for one Nextcloud account, scoped to the notes root.
 * All paths are relative to that root (`""` = root itself). ETags are handled unquoted.
 */
class WebDavClient(private val http: OkHttpClient, private val account: Account) {

    private val root: HttpUrl = (account.davRoot + "/").toHttpUrl()
    private val auth = Credentials.basic(account.loginName, account.appPassword)

    /** Decoded server-side path of the root, used to relativise hrefs in PROPFIND responses. */
    val rootHref: String = DavXml.percentDecode(root.encodedPath).trimEnd('/')

    fun url(relPath: String, trailingSlash: Boolean = false): HttpUrl {
        val b = root.newBuilder()
        relPath.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        if (trailingSlash) b.addPathSegment("")
        return b.build()
    }

    private fun request(relPath: String, trailingSlash: Boolean = false): Request.Builder =
        Request.Builder()
            .url(url(relPath, trailingSlash))
            .header("Authorization", auth)
            .header("User-Agent", USER_AGENT)

    suspend fun propfind(relPath: String, depth: Int = 1): List<DavEntry> = io {
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val req = request(relPath, trailingSlash = true)
            .method("PROPFIND", body)
            .header("Depth", depth.toString())
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code != 207) throw resp.toException()
            DavXml.parseMultistatus(resp.body.string(), rootHref)
        }
    }

    /** Downloads into [dest] (written atomically via a temp file). Returns the ETag. */
    suspend fun download(relPath: String, dest: File): String? = io {
        val req = request(relPath).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw resp.toException()
            val tmp = File(dest.parentFile, dest.name + ".part")
            dest.parentFile?.mkdirs()
            resp.body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true); tmp.delete()
            }
            resp.etag()
        }
    }

    /**
     * Uploads [file]. With [ifMatch] the server refuses (412) if its copy changed; with [ifNoneMatch]
     * it refuses if anything already exists there. [mtimeMillis] becomes the server-side modification time.
     * Returns the new ETag.
     */
    suspend fun upload(
        relPath: String,
        file: File,
        ifMatch: String? = null,
        ifNoneMatch: Boolean = false,
        mtimeMillis: Long = file.lastModified(),
        contentType: String = "application/octet-stream",
    ): String? = io {
        val b = request(relPath)
            .put(file.asRequestBody(contentType.toMediaType()))
            .header("X-OC-Mtime", (mtimeMillis / 1000).toString())
        if (ifMatch != null) b.header("If-Match", "\"$ifMatch\"")
        if (ifNoneMatch) b.header("If-None-Match", "*")
        http.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw resp.toException()
            resp.etag()
        }
    }

    /** Creates a collection; an existing one is not an error. */
    suspend fun mkcol(relPath: String): Unit = io {
        val req = request(relPath, trailingSlash = true).method("MKCOL", null).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 405) throw resp.toException()
        }
    }

    suspend fun move(fromRel: String, toRel: String, isDir: Boolean = false): Unit = io {
        val req = request(fromRel, trailingSlash = isDir)
            .method("MOVE", null)
            .header("Destination", url(toRel, trailingSlash = isDir).toString())
            .header("Overwrite", "F")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw resp.toException()
        }
    }

    suspend fun delete(relPath: String, ifMatch: String? = null, isDir: Boolean = false): Unit = io {
        val b = request(relPath, trailingSlash = isDir).delete()
        if (ifMatch != null) b.header("If-Match", "\"$ifMatch\"")
        http.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 404) throw resp.toException()
        }
    }

    private fun Response.etag(): String? =
        (header("OC-ETag") ?: header("ETag"))?.let(DavXml::normalizeEtag)

    private fun Response.toException(): DavException {
        val text = runCatching { body.string() }.getOrNull().orEmpty().take(300)
        return DavException(code, "$message ${request.method} ${request.url.encodedPath} $text".trim())
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val USER_AGENT = "PigeonNotes/0.1 (Android)"

        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:prop>
                <d:getetag/>
                <d:getlastmodified/>
                <d:getcontentlength/>
                <d:getcontenttype/>
                <d:resourcetype/>
                <oc:fileid/>
              </d:prop>
            </d:propfind>
        """.trimIndent()

        fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .readTimeout(java.time.Duration.ofSeconds(40))
            .writeTimeout(java.time.Duration.ofSeconds(40))
            .retryOnConnectionFailure(true)
            .build()
    }
}
