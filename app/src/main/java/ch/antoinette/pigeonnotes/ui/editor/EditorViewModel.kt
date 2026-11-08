package ch.antoinette.pigeonnotes.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.antoinette.pigeonnotes.AppGraph
import ch.antoinette.pigeonnotes.domain.NoteText
import ch.antoinette.pigeonnotes.domain.NotesRepository
import ch.antoinette.pigeonnotes.ui.notes.pageFolder
import ch.antoinette.pigeonnotes.ui.notes.tagFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Editing one note: the [DocumentModel] plus persistence, renaming, archiving and asset insertion.
 *
 * Sync is held from opening the note until it is closed and settled: saved, given the file name its
 * H1 asks for, or dropped again when it is a new note left as created.
 */
@OptIn(ExperimentalFoundationApi::class)
class EditorViewModel(private val graph: AppGraph, initialPath: String) : ViewModel() {
    val document = DocumentModel(onChanged = ::scheduleSave)

    var path by mutableStateOf(initialPath)
        private set
    /** The note's display title ([NoteText.index]): its H1, else derived from the file name. */
    var title by mutableStateOf(NoteText.title(initialPath))
        private set
    var sourceMode by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var archived by mutableStateOf(initialPath.startsWith(NotesRepository.ARCHIVE_DIR + "/"))
        private set

    /** Tags and page folders, for the "tag" and "add to page" pickers. */
    val structure = graph.notes.structure

    /** The note's tag: `""` when untagged under `Tags/`, null when not under `Tags/` at all. */
    val tag: String? get() = if (path.startsWith(NotesRepository.TAGS_DIR + "/")) NotesRepository.tagOf(path) ?: "" else null

    /**
     * The note's page folder relative to `Pages/` (`Recipes` or `Recipes/Asian`): `""` for a
     * standalone page, null when not under `Pages/`.
     */
    val pageFolder: String? get() = if (path.startsWith(NotesRepository.PAGES_DIR + "/")) NotesRepository.pageFolderOf(path)?.removePrefix(NotesRepository.PAGES_DIR + "/") ?: "" else null

    /** Whole-document field used in source mode. */
    val sourceField = TextFieldState()

    /** The file name, for the rename dialog. */
    val fileName: String get() = NoteText.title(path)

    private var saveJob: Job? = null
    private var lastSaved: String? = null

    /**
     * Just created by [NotesRepository.create]: dropped again on close if still exactly a blank new
     * note, and the only kind of note inside a page folder whose file name follows its H1.
     */
    private var fresh = false
    /** The text changed while open: only then does the file name follow the H1 on close. */
    private var edited = false
    /** A name given by hand while open is kept on close rather than replaced by the H1's. */
    private var renamedByHand = false
    private var deleted = false

    init {
        // No sync while the note is open: a half-typed note is neither uploaded mid-sentence nor
        // overwritten by a download. Released in onCleared, once the note has its final name.
        graph.syncEngine.hold()
        viewModelScope.launch {
            val text = graph.notes.read(initialPath)
            lastSaved = text
            document.load(text)
            title = NoteText.index(initialPath, text).first
            loaded = true
            val selectTitle = graph.notes.takeFresh(initialPath)
            fresh = selectTitle != null
            when {
                selectTitle == true && document.selectTitle() -> Unit
                fresh || text.isBlank() -> document.activateAtEnd()
            }
        }
    }

    // ---- saving ---------------------------------------------------------------------------------

    /** Called on every keystroke in the active field (or in source mode). */
    fun onTextChanged() {
        if (!sourceMode) document.onTyped()
        scheduleSave()
    }

    // ---- undo / redo ----------------------------------------------------------------------------

    val canUndo: Boolean get() = if (sourceMode) sourceField.undoState.canUndo else document.canUndo
    val canRedo: Boolean get() = if (sourceMode) sourceField.undoState.canRedo else document.canRedo

    fun undo() = if (sourceMode) sourceField.undoState.undo() else document.undo()
    fun redo() = if (sourceMode) sourceField.undoState.redo() else document.redo()

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(800)
            flush()
        }
    }

    /** Persists the current document (with the active block folded in) if it changed. */
    fun flush() {
        if (!loaded) return // the empty document before the note is read must not be saved over it
        val text = currentText()
        if (text == lastSaved) return
        lastSaved = text
        edited = true
        title = NoteText.index(path, text).first
        val p = path
        // App scope: this must survive the ViewModel being cleared on back navigation.
        graph.appScope.launch { graph.notes.save(p, text) }
    }

    private fun currentText(): String = if (sourceMode) {
        sourceField.text.toString().let { if (it.isBlank()) "" else it.trimEnd('\n') + "\n" }
    } else {
        document.currentText()
    }

    // ---- source mode ----------------------------------------------------------------------------

    fun toggleSourceMode() {
        if (sourceMode) {
            sourceMode = false
            document.setDoc(sourceField.text.toString())
        } else {
            document.commit()
            sourceField.edit { replace(0, length, document.doc); placeCursorBeforeCharAt(0) }
            sourceMode = true
        }
    }

    // ---- note operations ------------------------------------------------------------------------

    fun rename(newTitle: String) {
        renamedByHand = true
        moveNote(newTitle = newTitle)
    }

    fun archive(archive: Boolean) = moveNote(newParent = if (archive) NotesRepository.ARCHIVE_DIR else "")

    fun applyTag(tag: String) = moveNote(newParent = tagFolder(tag))

    fun addToPage(folder: String) = moveNote(newParent = pageFolder(folder))

    fun createTagAndApply(name: String) {
        viewModelScope.launch {
            val created = graph.notes.createFolder(NotesRepository.TAGS_DIR, name) ?: return@launch
            applyTag(created.substringAfterLast('/'))
        }
    }

    fun createPageFolderAndApply(name: String) {
        viewModelScope.launch {
            val created = graph.notes.createFolder(NotesRepository.PAGES_DIR, name) ?: return@launch
            addToPage(created.substringAfterLast('/'))
        }
    }

    private fun moveNote(newParent: String? = null, newTitle: String? = null) {
        viewModelScope.launch {
            document.commit()
            flush()
            val newPath = graph.notes.move(path, newParent, newTitle)
            path = newPath
            archived = newPath.startsWith(NotesRepository.ARCHIVE_DIR + "/")
            // links to the assets folder may have been rewritten
            val text = graph.notes.read(newPath)
            title = NoteText.index(newPath, text).first
            lastSaved = text
            if (text != currentText()) document.load(text)
        }
    }

    fun delete(onDone: () -> Unit) {
        saveJob?.cancel()
        deleted = true // nothing left to save in onCleared
        viewModelScope.launch { graph.notes.delete(path); onDone() }
    }

    /** Stores the picked image and inserts its markdown at the cursor. */
    fun insertImage(bytes: ByteArray, name: String) {
        viewModelScope.launch {
            val link = graph.notes.insertAsset(path, bytes, name)
            document.insertAtCursor("![${name.substringBeforeLast('.')}]($link)")
        }
    }

    fun resolveLink(link: String): File? = graph.notes.resolveLink(path, link)

    /**
     * The note is closed: save it, then drop it if it is a new note still exactly as created, or
     * else, if it was edited, give it the file name its H1 asks for. Runs in the app scope, which
     * outlives this ViewModel, and only then lets sync run again.
     */
    override fun onCleared() {
        saveJob?.cancel()
        val text = currentText()
        val p = path
        val drop = fresh && text == NoteText.NEW_NOTE_TEXT
        val rename = (edited || text != lastSaved) && !renamedByHand
        val skip = deleted || !loaded // nothing to save, or the note was never read
        graph.appScope.launch {
            try {
                when {
                    skip -> Unit
                    drop -> graph.notes.delete(p)
                    else -> {
                        graph.notes.save(p, text)
                        val renamed = if (rename) graph.notes.renameToTitle(p, fresh) else null
                        // A standalone page's order and colour are kept by path.
                        if (renamed != null && p.substringBeforeLast('/', "") == NotesRepository.PAGES_DIR) {
                            graph.settings.renamePageRefs(p, renamed)
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { graph.syncEngine.release() }
            }
        }
    }
}
