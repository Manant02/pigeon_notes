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

/** `null` = all notes; `""` = root folder; otherwise a folder path. */
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
)

class NoteListViewModel(private val graph: AppGraph) : ViewModel() {
    private val selected = MutableStateFlow<Category>(null)
    private val query = MutableStateFlow("")

    val state: StateFlow<NoteListUiState> = combine(
        graph.notes.notes, selected, query, graph.syncEngine.status, graph.notes.pendingCount, graph.settings.account,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val notes = values[0] as List<SyncEntry>
        val cat = values[1] as Category
        val q = (values[2] as String).trim()
        val status = values[3] as SyncStatus
        val pending = values[4] as Int
        val configured = values[5] != null
        val categories = notes.map { it.parent }.filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() }
        val rows = notes.asSequence()
            .filter { cat == null || it.parent == cat }
            .filter { q.isEmpty() || it.title.contains(q, true) || it.preview.contains(q, true) }
            .map { NoteRow(it.path, it.title, it.preview, it.modified, it.localState != LocalState.CLEAN, it.syncError) }
            .toList()
        NoteListUiState(rows, categories, cat, q, status, pending, configured)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteListUiState())

    fun select(category: Category) { selected.value = category }
    fun search(text: String) { query.value = text }
    fun refresh() = graph.syncEngine.requestSync()

    fun createNote(onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(graph.notes.create(selected.value ?: "")) }
    }

    fun delete(path: String) {
        viewModelScope.launch { graph.notes.delete(path) }
    }
}
