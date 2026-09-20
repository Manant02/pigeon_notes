package ch.antoinette.pigeonnotes.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.antoinette.pigeonnotes.AppGraph
import ch.antoinette.pigeonnotes.data.sync.SyncStatus
import ch.antoinette.pigeonnotes.domain.NotesRepository
import ch.antoinette.pigeonnotes.domain.Page
import ch.antoinette.pigeonnotes.domain.Structure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One tile in the pages row. */
data class PageTile(
    val path: String,
    val name: String,
    val accent: CategoryAccent,
    val folder: Boolean,
    /** For a single page: its preview. */
    val preview: String,
    /** For a folder page: the titles of its latest notes. */
    val recent: List<String>,
    val count: Int,
)

/** Which tagged notes are shown: all, only the untagged ones, or one tag. */
sealed interface TagFilter {
    data object Untagged : TagFilter
    data class Tag(val name: String) : TagFilter
}

data class HomeUiState(
    val quick: List<NoteRow> = emptyList(),
    val pages: List<PageTile> = emptyList(),
    val tags: List<String> = emptyList(),
    val tagFilter: TagFilter? = null,
    val tagged: List<NoteRow> = emptyList(),
    val structure: Structure = Structure(),
    val pageRows: Int = 2,
    val status: SyncStatus = SyncStatus.Idle,
    val pendingCount: Int = 0,
    val configured: Boolean = true,
    val picked: Set<String> = emptySet(),
)

class HomeViewModel(private val graph: AppGraph) : ViewModel() {
    private val tagFilter = MutableStateFlow<TagFilter?>(null)
    val selection = NoteSelection(graph, viewModelScope)

    val state: StateFlow<HomeUiState> = combine(
        graph.notes.structure,
        combine(graph.settings.pageOrder, graph.settings.pageColors, graph.settings.pageRows) { order, colors, rows -> Triple(order, colors, rows) },
        combine(graph.syncEngine.status, graph.notes.pendingCount, graph.settings.account) { status, pending, account ->
            Triple(status, pending, account != null)
        },
        combine(tagFilter, selection.picked) { filter, picks -> filter to picks },
    ) { structure, (order, colors, rows), (status, pending, configured), (filter, picks) ->
        val ordered = order.mapNotNull { p -> structure.pages.firstOrNull { it.path == p } } +
            structure.pages.filter { it.path !in order }
        val tagged = structure.tagged
            .filter {
                when (filter) {
                    null -> true
                    TagFilter.Untagged -> NotesRepository.tagOf(it.path) == null
                    is TagFilter.Tag -> NotesRepository.tagOf(it.path) == filter.name
                }
            }
            .map { it.toRow() }
        val quick = structure.quick.map { it.toRow() }
        val visible = (quick + tagged).mapTo(HashSet()) { it.path }
        HomeUiState(
            quick = quick,
            pages = ordered.map { page ->
                val accent = CategoryAccent.of(colors[page.path])
                when (page) {
                    is Page.Single -> PageTile(page.path, page.name, accent, false, page.entry.preview, emptyList(), 1)
                    is Page.Folder -> PageTile(page.path, page.name, accent, true, "", page.notes.take(4).map { it.title }, page.notes.size)
                }
            },
            tags = structure.tags,
            tagFilter = filter,
            tagged = tagged,
            structure = structure,
            pageRows = rows,
            status = status,
            pendingCount = pending,
            configured = configured,
            picked = picks intersect visible,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun filterTags(filter: TagFilter?) {
        tagFilter.value = if (tagFilter.value == filter) null else filter
    }

    fun refresh() = graph.syncEngine.requestSync()

    /** The FAB: a new quick note. */
    fun createQuickNote(onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(graph.notes.create("")) }
    }

    /** A new standalone page, opened right away. */
    fun createPage(title: String, onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(graph.notes.create(NotesRepository.PAGES_DIR, title)) }
    }

    fun createPageFolder(name: String) {
        viewModelScope.launch { graph.notes.createFolder(NotesRepository.PAGES_DIR, name) }
    }

    fun createTag(name: String) {
        viewModelScope.launch { graph.notes.createFolder(NotesRepository.TAGS_DIR, name) }
    }
}
