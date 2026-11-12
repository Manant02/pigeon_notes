package ch.antoinette.pigeonnotes.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.antoinette.pigeonnotes.AppGraph
import ch.antoinette.pigeonnotes.data.local.db.LocalState
import ch.antoinette.pigeonnotes.data.local.db.SyncEntry
import ch.antoinette.pigeonnotes.data.sync.SyncStatus
import ch.antoinette.pigeonnotes.domain.NoteText
import ch.antoinette.pigeonnotes.domain.NotesRepository
import ch.antoinette.pigeonnotes.domain.Structure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Notes under this folder live in the archive space, not in "All". */
const val ARCHIVE_DIR = NotesRepository.ARCHIVE_DIR

/** What a note list shows: every note (search), the archive, or one folder such as `Pages/Recipes`. */
sealed interface ListScope {
    data object All : ListScope
    data object Archive : ListScope
    data class Folder(val path: String) : ListScope
}

data class NoteRow(
    val path: String,
    val title: String,
    val preview: String,
    val modified: Long,
    val pending: Boolean,
    val error: String?,
    /** For notes under `Tags/`: the tag, or null when untagged. */
    val tag: String? = null,
)

fun SyncEntry.toRow() = NoteRow(path, title, preview, modified, localState != LocalState.CLEAN, syncError, NotesRepository.tagOf(path))

data class NoteListUiState(
    val rows: List<NoteRow> = emptyList(),
    val title: String = "",
    val accent: CategoryAccent = CategoryAccent.DEFAULT,
    val query: String = "",
    val status: SyncStatus = SyncStatus.Idle,
    val pendingCount: Int = 0,
    val structure: Structure = Structure(),
    /** Paths picked in selection mode; non-empty means the list is in selection mode. */
    val picked: Set<String> = emptySet(),
    /**
     * For a folder: its contents as a tree (sub-folders, expandable in place, then notes), or the
     * matching notes of the whole folder while searching. Null for "All notes" and the archive,
     * which show [rows].
     */
    val tree: List<TreeItem>? = null,
    /** For a folder: where it is, from the top (`Pages`) down to itself, as (name, path). */
    val crumbs: List<Pair<String, String>> = emptyList(),
    /** Names of the folder's direct sub-folders, to refuse a duplicate when creating one. */
    val subfolders: List<String> = emptyList(),
)

class NoteListViewModel(private val graph: AppGraph, val scope: ListScope) : ViewModel() {
    private val query = MutableStateFlow("")
    /** Sub-folders opened in place in the tree. */
    private val expanded = MutableStateFlow<Set<String>>(emptySet())
    val selection = NoteSelection(graph, viewModelScope)

    /** A folder that could not be deleted, with the files still in it (relative to it). */
    val notEmpty = MutableStateFlow<Pair<String, List<String>>?>(null)

    val state: StateFlow<NoteListUiState> = combine(
        combine(graph.notes.notes, graph.notes.structure, query) { notes, structure, q -> Triple(notes, structure, q) },
        combine(graph.syncEngine.status, graph.notes.pendingCount) { status, pending -> status to pending },
        combine(graph.settings.pageColors, selection.picked) { colors, picks -> colors to picks },
        expanded,
    ) { (allNotes, structure, q), (status, pending), (colors, picks), open ->
        val folder = (scope as? ListScope.Folder)?.path
        val rows = if (folder != null) emptyList() else allNotes.asSequence()
            .filter { if (scope == ListScope.Archive) it.parent.isArchive() else !it.parent.isArchive() }
            .filter { q.isEmpty() || it.title.contains(q, true) || it.preview.contains(q, true) }
            .map { it.toRow() }
            .toList()
        val tree = folder?.let {
            if (q.isBlank()) FolderTree.build(it, allNotes, structure.folders, open)
            else FolderTree.search(it, allNotes, q.trim())
        }
        val (title, accent) = when (scope) {
            ListScope.All -> "All notes" to CategoryAccent.DEFAULT
            ListScope.Archive -> "Archive" to CategoryAccent.DEFAULT
            // Sub-folders take the colour of the page folder they are in.
            is ListScope.Folder -> scope.path.substringAfterLast('/') to CategoryAccent.of(colors[topFolder(scope.path)])
        }
        // Keep only picks that are still on screen, so a synced-away note cannot stay selected.
        val visible = rows.mapTo(HashSet()) { it.path }
        tree?.forEach { if (it is TreeItem.Note) visible += it.row.path }
        NoteListUiState(
            rows, title, accent, q.trim(), status, pending, structure, picks intersect visible,
            tree = tree,
            crumbs = folder?.let(FolderTree::crumbs).orEmpty(),
            subfolders = folder?.let { f -> structure.folders.filter { it.substringBeforeLast('/', "") == f }.map { it.substringAfterLast('/') } }.orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteListUiState())

    /** `Pages/Recipes/Asian` → `Pages/Recipes`: the folder whose colour is set in the settings. */
    private fun topFolder(path: String) = path.split('/').take(2).joinToString("/")

    /** Opens or closes a sub-folder in place. */
    fun toggleExpanded(path: String) {
        expanded.value = if (path in expanded.value) expanded.value - path else expanded.value + path
    }

    /** A new folder inside the one on screen. */
    fun createSubfolder(name: String) {
        val folder = (scope as? ListScope.Folder)?.path ?: return
        viewModelScope.launch { graph.notes.createFolder(folder, name) }
    }

    fun renameFolder(path: String, newName: String) {
        viewModelScope.launch {
            val newPath = graph.notes.renameFolder(path, newName)
            if (newPath != path && path in expanded.value) {
                expanded.value = expanded.value.mapTo(HashSet()) { if (it == path || it.startsWith("$path/")) newPath + it.removePrefix(path) else it }
            }
        }
    }

    /** Deletes an empty sub-folder; otherwise [notEmpty] says what is still in it. */
    fun deleteFolder(path: String) {
        viewModelScope.launch {
            val result = graph.notes.deleteFolder(path)
            if (result is NotesRepository.FolderDeletion.NotEmpty) notEmpty.value = path to result.remaining
        }
    }

    private fun String.isArchive() = this == ARCHIVE_DIR || startsWith("$ARCHIVE_DIR/")

    fun search(text: String) { query.value = text }

    fun refresh() = graph.syncEngine.requestSync()

    /** New note in this folder, opened with its placeholder title selected; in "All" it is a quick note. */
    fun createNote(onCreated: (String) -> Unit) {
        val folder = (scope as? ListScope.Folder)?.path ?: ""
        viewModelScope.launch { onCreated(graph.notes.create(folder, initialText = NoteText.NEW_NOTE_TEXT, selectTitle = true)) }
    }

    fun delete(path: String) {
        viewModelScope.launch { graph.notes.delete(path) }
    }
}
