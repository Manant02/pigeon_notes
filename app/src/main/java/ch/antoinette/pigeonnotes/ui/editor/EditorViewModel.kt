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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Editing one note: the [DocumentModel] plus persistence, renaming, archiving and asset insertion. */
@OptIn(ExperimentalFoundationApi::class)
class EditorViewModel(private val graph: AppGraph, initialPath: String) : ViewModel() {
    val document = DocumentModel(onChanged = ::scheduleSave)

    var path by mutableStateOf(initialPath)
        private set
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

    /** The note's page folder name: `""` for a standalone page, null when not under `Pages/`. */
    val pageFolder: String? get() = if (path.startsWith(NotesRepository.PAGES_DIR + "/")) NotesRepository.pageFolderOf(path)?.substringAfterLast('/') ?: "" else null

    /** Whole-document field used in source mode. */
    val sourceField = TextFieldState()

    private var saveJob: Job? = null
    private var lastSaved: String? = null

    init {
        viewModelScope.launch {
            val text = graph.notes.read(initialPath)
            lastSaved = text
            document.load(text)
            loaded = true
            if (text.isBlank()) document.activateAtEnd()
        }
    }

    // ---- sync hold ------------------------------------------------------------------------------

    fun holdSync() = graph.syncEngine.hold()

    /** Flushes first so the sync that follows the release carries the latest text. */
    fun releaseSync() {
        flush()
        graph.syncEngine.release()
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
        val text = currentText()
        if (text == lastSaved) return
        lastSaved = text
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

    fun rename(newTitle: String) = moveNote(newTitle = newTitle)

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
            title = NoteText.title(newPath)
            archived = newPath.startsWith(NotesRepository.ARCHIVE_DIR + "/")
            // links to the assets folder may have been rewritten
            val text = graph.notes.read(newPath)
            lastSaved = text
            if (text != currentText()) document.load(text)
        }
    }

    fun delete(onDone: () -> Unit) {
        saveJob?.cancel()
        lastSaved = currentText() // suppress the flush in onCleared
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

    override fun onCleared() {
        flush()
    }
}
