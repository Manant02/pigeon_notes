package ch.antoinette.pigeonnotes.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.antoinette.pigeonnotes.AppGraph
import ch.antoinette.pigeonnotes.data.local.db.LocalState
import ch.antoinette.pigeonnotes.data.local.db.SyncEntry
import ch.antoinette.pigeonnotes.data.sync.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Notes under this folder live in the archive space, not in "All". */
const val ARCHIVE_DIR = "_archive"

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
)

class NoteListViewModel(private val graph: AppGraph) : ViewModel() {
    private val selected = MutableStateFlow<Category>(null)
    private val query = MutableStateFlow("")

    val state: StateFlow<NoteListUiState> = combine(
        graph.notes.notes, selected, query, graph.syncEngine.status, graph.notes.pendingCount,
        graph.settings.account, graph.settings.tileView, graph.settings.categoryOrder,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val notes = values[0] as List<SyncEntry>
        val cat = values[1] as Category
        val q = (values[2] as String).trim()
        val status = values[3] as SyncStatus
        val pending = values[4] as Int
        val configured = values[5] != null
        val tiles = values[6] as Boolean
        @Suppress("UNCHECKED_CAST")
        val order = values[7] as List<String>

        val present = notes.map { it.parent }.filter { it.isNotEmpty() && !it.isArchive() }.distinct()
        val categories = order.filter { it in present } + present.filter { it !in order }.sortedBy { it.lowercase() }
        val rows = notes.asSequence()
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
        NoteListUiState(rows, categories, cat, q, status, pending, configured, tiles)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteListUiState())

    private fun String.isArchive() = this == ARCHIVE_DIR || startsWith("$ARCHIVE_DIR/")

    fun select(category: Category) { selected.value = category }
    fun search(text: String) { query.value = text }
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
