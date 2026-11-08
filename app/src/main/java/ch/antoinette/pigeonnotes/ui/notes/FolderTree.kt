package ch.antoinette.pigeonnotes.ui.notes

import ch.antoinette.pigeonnotes.data.local.db.SyncEntry
import ch.antoinette.pigeonnotes.domain.NoteText

/**
 * One line of a folder shown as a tree: a sub-folder (which may be expanded in place), a note, or
 * the placeholder of an expanded folder that holds nothing. [depth] 0 is the folder on screen.
 */
sealed interface TreeItem {
    val key: String
    val depth: Int

    data class Folder(
        val path: String,
        override val depth: Int,
        val expanded: Boolean,
        /** Notes and sub-folders directly inside. */
        val noteCount: Int,
        val folderCount: Int,
    ) : TreeItem {
        val name: String get() = path.substringAfterLast('/')
        override val key get() = "dir:$path"
    }

    data class Note(
        val row: NoteRow,
        /** The file name without `.md`; while searching, the path below the folder on screen. */
        val fileName: String,
        override val depth: Int,
    ) : TreeItem {
        override val key get() = row.path
    }

    data class Empty(val parent: String, override val depth: Int) : TreeItem {
        override val key get() = "empty:$parent"
    }
}

/** Builds the lines of a folder tree; pure, so it can be unit-tested. */
object FolderTree {
    /**
     * The contents of [root]: sub-folders first (by name), then notes as given (newest first).
     * Folders in [expanded] show their own contents right below them, one level deeper.
     * [notes] and [folders] may hold the whole notes tree; only what is under [root] is used.
     */
    fun build(root: String, notes: List<SyncEntry>, folders: List<String>, expanded: Set<String>): List<TreeItem> {
        val notesByParent = notes.groupBy { it.parent }
        val foldersByParent = folders.groupBy { parentOf(it) }
        val out = ArrayList<TreeItem>()
        fun walk(dir: String, depth: Int) {
            val subs = foldersByParent[dir].orEmpty().sortedBy { it.substringAfterLast('/').lowercase() }
            val here = notesByParent[dir].orEmpty()
            if (depth > 0 && subs.isEmpty() && here.isEmpty()) out += TreeItem.Empty(dir, depth)
            for (sub in subs) {
                val open = sub in expanded
                out += TreeItem.Folder(
                    path = sub, depth = depth, expanded = open,
                    noteCount = notesByParent[sub].orEmpty().size,
                    folderCount = foldersByParent[sub].orEmpty().size,
                )
                if (open) walk(sub, depth + 1)
            }
            for (note in here) out += TreeItem.Note(note.toRow(), NoteText.title(note.path), depth)
        }
        walk(root, 0)
        return out
    }

    /**
     * Notes anywhere below [root] matching [query] in their title, preview or file name, as a flat
     * list. Each one's file name carries its path below [root], so the folder it is in shows.
     */
    fun search(root: String, notes: List<SyncEntry>, query: String): List<TreeItem> =
        notes.asSequence()
            .filter { it.path.startsWith("$root/") }
            .map { it to it.path.removePrefix("$root/").removeSuffix(".md").removeSuffix(".MD") }
            .filter { (note, rel) ->
                note.title.contains(query, true) || note.preview.contains(query, true) || rel.contains(query, true)
            }
            .map { (note, rel) -> TreeItem.Note(note.toRow(), rel, 0) }
            .toList()

    /**
     * The breadcrumb for [path]: each folder from the top down with its full path, e.g.
     * `Pages/A/B` → (Pages, Pages), (A, Pages/A), (B, Pages/A/B).
     */
    fun crumbs(path: String): List<Pair<String, String>> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        return parts.indices.map { i -> parts[i] to parts.take(i + 1).joinToString("/") }
    }

    private fun parentOf(path: String) = if ('/' in path) path.substringBeforeLast('/') else ""
}
