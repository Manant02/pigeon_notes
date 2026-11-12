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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
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
    /**
     * Outside folders, while a search is typed: the notes whose text matches it (full text, see
     * [NoteSearch]), shown instead of [rows]. Null when not searching, and in folders.
     */
    val hits: List<SearchHit>? = null,
    /** False until the notes have been read, so "nothing here" isn't flashed before they are. */
    val loaded: Boolean = false,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class NoteListViewModel(private val graph: AppGraph, val scope: ListScope) : ViewModel() {
    private val query = MutableStateFlow("")
    /** The search as typed, for the search field (the list follows it a moment later). */
    val typedQuery: StateFlow<String> = query.asStateFlow()
    private val texts = NoteTextCache(graph.notes::read)
    /** Sub-folders opened in place in the tree. */
    private val expanded = MutableStateFlow<Set<String>>(emptySet())
    val selection = NoteSelection(graph, viewModelScope)

    /** A folder that could not be deleted, with the files still in it (relative to it). */
    val notEmpty = MutableStateFlow<Pair<String, List<String>>?>(null)

    /**
     * Full-text matches in the archive (or in every note, archive included), recomputed a moment
     * after typing stops.
     * Folders search their index instead ([FolderTree.search]), so they get none.
     */
    private val hits: Flow<List<SearchHit>?> =
        if (scope is ListScope.Folder) flowOf(null)
        else combine(graph.notes.notes, query.debounce { if (it.isBlank()) 0L else SEARCH_DELAY_MS }) { notes, q -> notes to q }
            .mapLatest { (notes, q) ->
                if (q.isBlank()) return@mapLatest null
                // Searching every note includes the archive (shown apart); the archive searches only itself.
                val inScope = if (scope == ListScope.All) notes else notes.filter { inScope(it) }
                val text = texts.texts(inScope)
                NoteSearch.search(inScope, q) { text[it.path].orEmpty() }
            }
            .flowOn(Dispatchers.IO)

    val state: StateFlow<NoteListUiState> = combine(
        combine(graph.notes.notes, graph.notes.structure, query, hits) { notes, structure, q, hits -> Search(notes, structure, q, hits) },
        combine(graph.syncEngine.status, graph.notes.pendingCount) { status, pending -> status to pending },
        combine(graph.settings.pageColors, selection.picked) { colors, picks -> colors to picks },
        expanded,
    ) { (allNotes, structure, q, found), (status, pending), (colors, picks), open ->
        val folder = (scope as? ListScope.Folder)?.path
        val rows = if (folder != null) emptyList() else allNotes.asSequence()
            .filter { inScope(it) }
            .map { it.toRow() }
            .toList()
        val hits = found.takeIf { q.isNotBlank() }
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
        val visible = (hits?.map { it.row } ?: rows).mapTo(HashSet()) { it.path }
        tree?.forEach { if (it is TreeItem.Note) visible += it.row.path }
        NoteListUiState(
            rows, title, accent, q.trim(), status, pending, structure, picks intersect visible,
            tree = tree,
            crumbs = folder?.let(FolderTree::crumbs).orEmpty(),
            subfolders = folder?.let { f -> structure.folders.filter { it.substringBeforeLast('/', "") == f }.map { it.substringAfterLast('/') } }.orEmpty(),
            hits = hits,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteListUiState())

    private data class Search(val notes: List<SyncEntry>, val structure: Structure, val query: String, val hits: List<SearchHit>?)

    private fun inScope(note: SyncEntry) = if (scope == ListScope.Archive) note.parent.isArchive() else !note.parent.isArchive()

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

    /** Takes a note out of the archive into the quick notes; [onDone] gets its new path (for undo). */
    fun restore(path: String, onDone: (String) -> Unit = {}) {
        viewModelScope.launch { onDone(graph.notes.move(path, newParent = "")) }
    }

    /** Puts a note (back) into the archive. */
    fun archive(path: String) {
        viewModelScope.launch { graph.notes.move(path, newParent = ARCHIVE_DIR) }
    }

    companion object {
        /** How long typing has to pause before the full-text search runs. */
        const val SEARCH_DELAY_MS = 150L
    }
}
