package ch.antoinette.pigeonnotes.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.antoinette.pigeonnotes.AppGraph
import ch.antoinette.pigeonnotes.data.local.db.LocalState
import ch.antoinette.pigeonnotes.data.local.db.SyncEntry
import ch.antoinette.pigeonnotes.data.sync.SyncStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A category pinned to the home screen, with the few notes shown under its title. */
data class PinnedSection(
    val category: String,
    val accent: CategoryAccent,
    val notes: List<NoteRow>,
    val total: Int,
)

data class HomeUiState(
    val recent: List<NoteRow> = emptyList(),
    /** Categories that are not pinned, shown as chips. */
    val chips: List<String> = emptyList(),
    val pinned: List<PinnedSection> = emptyList(),
    val status: SyncStatus = SyncStatus.Idle,
    val pendingCount: Int = 0,
    val configured: Boolean = true,
    val hasUncategorized: Boolean = false,
)

class HomeViewModel(private val graph: AppGraph) : ViewModel() {

    val state: StateFlow<HomeUiState> = combine(
        graph.notes.notes,
        graph.notes.categories,
        combine(graph.settings.pinnedCategories, graph.settings.categoryColors, graph.settings.categoryOrder) { pinned, colors, order ->
            Triple(pinned, colors, order)
        },
        combine(graph.syncEngine.status, graph.notes.pendingCount, graph.settings.account) { status, pending, account ->
            Triple(status, pending, account != null)
        },
    ) { notes, categories, prefs, sync ->
        val (pinnedNames, colors, order) = prefs
        val (status, pending, configured) = sync

        val visible = notes.filter { !it.parent.isArchive() }
        val ordered = order.filter { it in categories } + categories.filter { it !in order }
        val pinnedHere = pinnedNames.filter { it in categories }

        HomeUiState(
            recent = visible.take(RECENT_COUNT).map { it.toRow() },
            chips = ordered.filter { it !in pinnedHere },
            pinned = pinnedHere.map { category ->
                val own = visible.filter { it.parent == category }
                PinnedSection(
                    category = category,
                    accent = CategoryAccent.of(colors[category]),
                    notes = own.take(SECTION_COUNT).map { it.toRow() },
                    total = own.size,
                )
            },
            status = status,
            pendingCount = pending,
            configured = configured,
            hasUncategorized = visible.any { it.parent.isEmpty() },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun refresh() = graph.syncEngine.requestSync()

    fun createNote(onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(graph.notes.create("")) }
    }

    private fun SyncEntry.toRow() =
        NoteRow(path, title, preview, modified, localState != LocalState.CLEAN, syncError)

    private fun String.isArchive() = this == ARCHIVE_DIR || startsWith("$ARCHIVE_DIR/")

    companion object {
        const val RECENT_COUNT = 4
        const val SECTION_COUNT = 3
    }
}
