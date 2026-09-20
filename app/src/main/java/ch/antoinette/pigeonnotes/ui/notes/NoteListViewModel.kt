package ch.antoinette.pigeonnotes.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.antoinette.pigeonnotes.AppGraph
import ch.antoinette.pigeonnotes.data.local.db.LocalState
import ch.antoinette.pigeonnotes.data.local.db.SyncEntry
import ch.antoinette.pigeonnotes.data.sync.SyncStatus
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
    val tileView: Boolean = false,
    val structure: Structure = Structure(),
    /** Paths picked in selection mode; non-empty means the list is in selection mode. */
    val picked: Set<String> = emptySet(),
)

class NoteListViewModel(private val graph: AppGraph, val scope: ListScope) : ViewModel() {
    private val query = MutableStateFlow("")
    val selection = NoteSelection(graph, viewModelScope)

    val state: StateFlow<NoteListUiState> = combine(
        combine(graph.notes.notes, graph.notes.structure, query) { notes, structure, q -> Triple(notes, structure, q) },
        combine(graph.syncEngine.status, graph.notes.pendingCount) { status, pending -> status to pending },
        combine(graph.settings.tileView, graph.settings.pageColors, selection.picked) { tiles, colors, picks -> Triple(tiles, colors, picks) },
    ) { (allNotes, structure, q), (status, pending), (tiles, colors, picks) ->
        val rows = allNotes.asSequence()
            .filter {
                when (scope) {
                    ListScope.All -> !it.parent.isArchive()
                    ListScope.Archive -> it.parent.isArchive()
                    is ListScope.Folder -> it.parent == scope.path
                }
            }
            .filter { q.isEmpty() || it.title.contains(q, true) || it.preview.contains(q, true) }
            .map { it.toRow() }
            .toList()
        val (title, accent) = when (scope) {
            ListScope.All -> "All notes" to CategoryAccent.DEFAULT
            ListScope.Archive -> "Archive" to CategoryAccent.DEFAULT
            is ListScope.Folder -> scope.path.substringAfterLast('/') to CategoryAccent.of(colors[scope.path])
        }
        // Keep only picks that are still on screen, so a synced-away note cannot stay selected.
        val visible = rows.mapTo(HashSet()) { it.path }
        NoteListUiState(rows, title, accent, q.trim(), status, pending, tiles, structure, picks intersect visible)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteListUiState())

    private fun String.isArchive() = this == ARCHIVE_DIR || startsWith("$ARCHIVE_DIR/")

    fun search(text: String) { query.value = text }

    fun refresh() = graph.syncEngine.requestSync()

    /** New note in this folder; in "All" it is a quick note. */
    fun createNote(onCreated: (String) -> Unit) {
        val folder = (scope as? ListScope.Folder)?.path ?: ""
        viewModelScope.launch { onCreated(graph.notes.create(folder)) }
    }

    fun delete(path: String) {
        viewModelScope.launch { graph.notes.delete(path) }
    }
}
