package ch.antoinette.pigeonnotes.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.antoinette.pigeonnotes.AppGraph
import ch.antoinette.pigeonnotes.domain.NoteText
import ch.antoinette.pigeonnotes.domain.NotesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Editing one note: the [DocumentModel] plus persistence, renaming, archiving and asset insertion. */
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

    /** Every category, for the "move to" picker. */
    val categories = graph.notes.categories

    /** The folder the note is in right now (`""` = no category). */
    val category: String get() = if ('/' in path) path.substringBeforeLast('/') else ""

    /** Whole-document field used in source mode. */
    val sourceField = TextFieldState()

    private var saveJob: Job? = null
    private var lastSaved: String? = null

    init {
        viewModelScope.launch {
            val text = graph.notes.read(initialPath)
            lastSaved = text
            document.setDoc(text, notify = false)
            loaded = true
            if (text.isBlank()) document.activateAtEnd()
        }
    }

    // ---- saving ---------------------------------------------------------------------------------

    /** Called on every keystroke in the active field (or in source mode). */
    fun onTextChanged() = scheduleSave()

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

    fun moveToCategory(category: String) = moveNote(newParent = category)

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
            if (text != currentText()) document.setDoc(text, notify = false)
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
