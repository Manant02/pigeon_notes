package ch.antoinette.pigeonnotes.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.antoinette.pigeonnotes.AppGraph
import ch.antoinette.pigeonnotes.data.local.db.LocalState
import ch.antoinette.pigeonnotes.data.local.db.SyncEntry
import ch.antoinette.pigeonnotes.data.sync.SyncStatus
import ch.antoinette.pigeonnotes.domain.NotesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Notes under this folder live in the archive space, not in "All". */
const val ARCHIVE_DIR = NotesRepository.ARCHIVE_DIR

/** `null` = all notes; `""` = root folder; [ARCHIVE_DIR] = archive; otherwise a folder path. */
typealias Category = String?

data class NoteRow(val path: String, val title: String, val preview: String, val modified: Long, val pending: Boolean, val error: String?)

data class NoteListUiState(
    val rows: List<NoteRow> = emptyList(),
    val categories: List<String> = emptyList(),
    val selected: Category = null,
    val query: String = "",
    val status: SyncStatus = SyncStatus.Idle,
    val pendingCount: Int = 0,
    val configured: Boolean = true,
    val tileView: Boolean = false,
    /** Paths picked in selection mode; non-empty means the list is in selection mode. */
    val picked: Set<String> = emptySet(),
)

class NoteListViewModel(private val graph: AppGraph) : ViewModel() {
    private val selected = MutableStateFlow<Category>(null)
    private val query = MutableStateFlow("")

    private val picked = MutableStateFlow<Set<String>>(emptySet())

    val state: StateFlow<NoteListUiState> = combine(
        combine(graph.notes.notes, graph.notes.categories, selected, query) { notes, categories, cat, q ->
            Triple(notes, categories, cat to q)
        },
        combine(graph.syncEngine.status, graph.notes.pendingCount, graph.settings.account) { status, pending, account ->
            Triple(status, pending, account != null)
        },
        combine(graph.settings.tileView, graph.settings.categoryOrder, picked) { tiles, order, picks ->
            Triple(tiles, order, picks)
        },
    ) { notes, sync, view ->
        val (allNotes, allCategories, catQuery) = notes
        val (cat, q) = catQuery
        val (status, pending, configured) = sync
        val (tiles, order, picks) = view

        val categories = order.filter { it in allCategories } + allCategories.filter { it !in order }
        val rows = allNotes.asSequence()
            .filter {
                when (cat) {
                    null -> !it.parent.isArchive()
                    ARCHIVE_DIR -> it.parent.isArchive()
                    else -> it.parent == cat
                }
            }
            .filter { q.isEmpty() || it.title.contains(q, true) || it.preview.contains(q, true) }
            .map { NoteRow(it.path, it.title, it.preview, it.modified, it.localState != LocalState.CLEAN, it.syncError) }
            .toList()
        // Keep only picks that are still on screen, so a synced-away note cannot stay selected.
        val visible = rows.mapTo(HashSet()) { it.path }
        NoteListUiState(rows, categories, cat, q.trim(), status, pending, configured, tiles, picks intersect visible)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteListUiState())

    private fun String.isArchive() = this == ARCHIVE_DIR || startsWith("$ARCHIVE_DIR/")

    fun select(category: Category) { selected.value = category; picked.value = emptySet() }
    fun search(text: String) { query.value = text }

    // ---- selection mode -------------------------------------------------------------------------

    fun togglePick(path: String) {
        picked.value = if (path in picked.value) picked.value - path else picked.value + path
    }

    fun clearPicks() { picked.value = emptySet() }

    fun archivePicked(archived: Boolean) = withPicks { graph.notes.move(it, newParent = if (archived) ARCHIVE_DIR else "") }

    fun deletePicked() = withPicks { graph.notes.delete(it) }

    fun movePicked(category: String) = withPicks { graph.notes.move(it, newParent = category) }

    private fun withPicks(action: suspend (String) -> Unit) {
        val paths = picked.value
        picked.value = emptySet()
        viewModelScope.launch { paths.forEach { action(it) } }
    }
    fun refresh() = graph.syncEngine.requestSync()

    fun createNote(onCreated: (String) -> Unit) {
        val folder = selected.value ?: ""
        viewModelScope.launch { onCreated(graph.notes.create(folder)) }
    }

    fun delete(path: String) {
        viewModelScope.launch { graph.notes.delete(path) }
    }

    fun archive(path: String, archived: Boolean) {
        viewModelScope.launch { graph.notes.move(path, newParent = if (archived) ARCHIVE_DIR else "") }
    }
}
