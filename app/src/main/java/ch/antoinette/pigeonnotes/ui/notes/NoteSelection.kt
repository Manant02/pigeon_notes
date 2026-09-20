package ch.antoinette.pigeonnotes.ui.notes

import ch.antoinette.pigeonnotes.AppGraph
import ch.antoinette.pigeonnotes.domain.NotesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Multi-select over notes (long-press) and the actions offered on the selection. */
class NoteSelection(private val graph: AppGraph, private val scope: CoroutineScope) {
    val picked = MutableStateFlow<Set<String>>(emptySet())

    fun toggle(path: String) {
        picked.value = if (path in picked.value) picked.value - path else picked.value + path
    }

    fun clear() { picked.value = emptySet() }

    fun archive(archived: Boolean) = withPicks { graph.notes.move(it, newParent = if (archived) NotesRepository.ARCHIVE_DIR else "") }

    fun delete() = withPicks { graph.notes.delete(it) }

    /** `""` = untagged (directly in `Tags/`). */
    fun tag(tag: String) = withPicks { graph.notes.move(it, newParent = tagFolder(tag)) }

    /** `""` = standalone page (directly in `Pages/`). */
    fun addToPage(folder: String) = withPicks { graph.notes.move(it, newParent = pageFolder(folder)) }

    /** Creates the tag folder and tags the selection with it. */
    fun createTagAndApply(name: String) {
        scope.launch {
            val path = graph.notes.createFolder(NotesRepository.TAGS_DIR, name) ?: return@launch
            tag(path.substringAfterLast('/'))
        }
    }

    fun createPageFolderAndApply(name: String) {
        scope.launch {
            val path = graph.notes.createFolder(NotesRepository.PAGES_DIR, name) ?: return@launch
            addToPage(path.substringAfterLast('/'))
        }
    }

    private fun withPicks(action: suspend (String) -> Unit) {
        val paths = picked.value
        picked.value = emptySet()
        scope.launch { paths.forEach { action(it) } }
    }
}
