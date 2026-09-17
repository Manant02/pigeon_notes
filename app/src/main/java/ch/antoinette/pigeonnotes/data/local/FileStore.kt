package ch.antoinette.pigeonnotes.data.local

import java.io.File

/**
 * The on-disk mirror of the server's notes tree, under app-private storage.
 * Paths are relative, `/`-separated, no leading slash.
 */
class FileStore(val root: File) {

    init {
        root.mkdirs()
    }

    fun file(path: String): File = if (path.isEmpty()) root else File(root, path)

    fun exists(path: String): Boolean = file(path).exists()

    fun read(path: String): String = file(path).readText()

    fun readBytes(path: String): ByteArray = file(path).readBytes()

    /** Writes atomically (temp file + rename) and returns the resulting mtime. */
    fun write(path: String, text: String): Long = writeBytes(path, text.toByteArray())

    fun writeBytes(path: String, bytes: ByteArray): Long {
        val target = file(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true); tmp.delete()
        }
        return target.lastModified()
    }

    fun mkdir(path: String) {
        file(path).mkdirs()
    }

    /** Deletes a file or a whole directory tree. */
    fun delete(path: String) {
        file(path).deleteRecursively()
    }

    fun move(from: String, to: String) {
        val src = file(from)
        val dst = file(to)
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) {
            src.copyRecursively(dst, overwrite = true); src.deleteRecursively()
        }
    }

    /** Removes empty parent directories up to (not including) the root, after a delete/move. */
    fun pruneEmptyParents(path: String) {
        var dir = file(path).parentFile
        while (dir != null && dir != root && dir.isDirectory && dir.list().isNullOrEmpty()) {
            dir.delete(); dir = dir.parentFile
        }
    }
}
