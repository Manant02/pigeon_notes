package ch.antoinette.pigeonnotes.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.antoinette.pigeonnotes.AppGraph
import ch.antoinette.pigeonnotes.domain.NoteText
import ch.antoinette.pigeonnotes.domain.markdown.Block
import ch.antoinette.pigeonnotes.domain.markdown.BlockKind
import ch.antoinette.pigeonnotes.domain.markdown.BlockParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * The block currently being edited as raw markdown. [index] is its position in the display list;
 * a [virtual] block is an empty paragraph that does not exist in the document yet.
 */
class ActiveBlock(
    val index: Int,
    val kind: BlockKind,
    val level: Int,
    val start: Int,
    val end: Int,
    val field: TextFieldState,
    val virtual: Boolean = false,
)

/**
 * Holds the note text as a list of blocks. Exactly one block can be "active" (shown as an editable
 * text field); committing it splices its source back into the document and re-parses.
 */
class EditorViewModel(private val graph: AppGraph, initialPath: String) : ViewModel() {
    var path by mutableStateOf(initialPath)
        private set
    var title by mutableStateOf(NoteText.title(initialPath))
        private set
    var doc by mutableStateOf("")
        private set
    var blocks by mutableStateOf<List<Block>>(emptyList())
        private set
    var active by mutableStateOf<ActiveBlock?>(null)
        private set
    var sourceMode by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set

    /** Whole-document field used in source mode. */
    val sourceField = TextFieldState()

    private var saveJob: Job? = null
    private var lastSaved: String? = null

    init {
        viewModelScope.launch {
            val text = graph.notes.read(initialPath)
            lastSaved = text
            setDoc(text, save = false)
            loaded = true
            if (text.isBlank()) activateVirtualAt(0, 0)
        }
    }

    // ---- document -------------------------------------------------------------------------------

    private fun setDoc(text: String, save: Boolean = true) {
        doc = text
        blocks = BlockParser.parse(text)
        if (save) scheduleSave()
    }

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

    /** Document text including whatever is typed in the active block, normalised to one trailing newline. */
    private fun currentText(): String {
        val base = if (sourceMode) sourceField.text.toString() else active?.let { spliced(it) } ?: doc
        return if (base.isBlank()) "" else base.trimEnd('\n') + "\n"
    }

    private fun spliced(a: ActiveBlock): String = doc.replaceRange(a.start, a.end, a.field.text.toString())

    // ---- activation -----------------------------------------------------------------------------

    /** Makes block [index] editable with the cursor at [sourceOffset] within the block source. */
    fun activate(index: Int, sourceOffset: Int) {
        val current = active
        if (current != null && !current.virtual && current.index == index) return
        commit()
        val b = blocks.getOrNull(index) ?: return
        val cursor = sourceOffset.coerceIn(0, b.source.length)
        active = ActiveBlock(index, b.kind, b.level, b.start, b.end, TextFieldState(b.source, TextRange(cursor)))
    }

    /** Tap below the last block: start a new paragraph at the end. */
    fun activateAtEnd() {
        val a = active
        if (a != null && a.virtual && a.index == blocks.size) return
        commit()
        val trimmed = doc.trimEnd('\n')
        val newDoc = if (trimmed.isEmpty()) "" else trimmed + "\n\n"
        if (newDoc != doc) setDoc(newDoc, save = false)
        activateVirtualAt(blocks.size, newDoc.length)
    }

    private fun activateVirtualAt(index: Int, position: Int) {
        active = ActiveBlock(index, BlockKind.PARAGRAPH, 0, position, position, TextFieldState(), virtual = true)
    }

    /** Writes the active block back into the document and leaves edit mode. */
    fun commit() {
        val a = active ?: return
        active = null
        val source = a.field.text.toString()
        if (source == doc.substring(a.start, a.end)) {
            if (a.virtual) setDoc(collapseBlankLines(doc, a.start))
            return
        }
        var newDoc = spliced(a)
        if (source.isBlank()) newDoc = collapseBlankLines(newDoc, a.start)
        setDoc(newDoc)
    }

    /**
     * Enter in a paragraph/heading: the text after the cursor becomes a new block right below,
     * which is activated with the cursor at its start.
     */
    fun splitActive(cursor: Int) {
        val a = active ?: return
        val text = a.field.text.toString()
        val at = cursor.coerceIn(0, text.length)
        val before = text.substring(0, at).trimEnd()
        val after = text.substring(at).trimStart()
        if (before.isEmpty() && after.isEmpty()) return
        insertParagraphBetween(a, before, after)
    }

    /** Enter on an empty list item: drop the bullet and continue as a paragraph below the list. */
    fun leaveListActive(lineStart: Int, lineEnd: Int) {
        val a = active ?: return
        val text = a.field.text.toString()
        val kept = text.substring(0, lineStart.coerceIn(0, text.length)).trimEnd()
        val rest = text.substring(lineEnd.coerceIn(0, text.length)).trimStart('\n')
        insertParagraphBetween(a, kept, rest, forceNewParagraph = true)
    }

    /**
     * Replaces the active block with [before] and [after] (either may be empty) and activates the
     * block at the seam: [after] itself, or a fresh empty paragraph when it is empty or [forceNewParagraph].
     */
    private fun insertParagraphBetween(a: ActiveBlock, before: String, after: String, forceNewParagraph: Boolean = false) {
        active = null
        val sb = StringBuilder()
        if (before.isNotEmpty()) sb.append(before).append("\n\n")
        val insertAt = a.start + sb.length
        if (forceNewParagraph && after.isNotEmpty()) sb.append("\n\n")
        sb.append(after)
        setDoc(doc.replaceRange(a.start, a.end, sb))
        if (after.isNotEmpty() && !forceNewParagraph) {
            val idx = blocks.indexOfFirst { it.start == insertAt }
            if (idx >= 0) {
                val b = blocks[idx]
                active = ActiveBlock(idx, b.kind, b.level, b.start, b.end, TextFieldState(b.source, TextRange(0)))
                return
            }
        }
        var position = insertAt
        if (position >= doc.trimEnd('\n').length) {
            val padded = doc.trimEnd('\n').let { if (it.isEmpty()) "" else it + "\n\n" }
            if (padded != doc) setDoc(padded, save = false)
            position = padded.length
        }
        activateVirtualAt(blocks.count { it.start < position }, position)
    }

    // ---- source mode ----------------------------------------------------------------------------

    fun toggleSourceMode() {
        if (sourceMode) {
            sourceMode = false
            setDoc(sourceField.text.toString())
        } else {
            commit()
            sourceField.edit { replace(0, length, doc); placeCursorBeforeCharAt(0) }
            sourceMode = true
        }
    }

    // ---- note operations ------------------------------------------------------------------------

    fun rename(newTitle: String) {
        viewModelScope.launch {
            commit()
            flush()
            val newPath = graph.notes.rename(path, newTitle)
            path = newPath
            title = NoteText.title(newPath)
            // links to the assets folder may have been rewritten
            val text = graph.notes.read(newPath)
            lastSaved = text
            if (text != currentText()) setDoc(text, save = false)
        }
    }

    fun delete(onDone: () -> Unit) {
        saveJob?.cancel()
        lastSaved = currentText() // suppress the flush in onCleared
        viewModelScope.launch { graph.notes.delete(path); onDone() }
    }

    /** Stores the picked image and inserts its markdown at the cursor (or as a new block at the end). */
    fun insertImage(bytes: ByteArray, name: String) {
        viewModelScope.launch {
            val link = graph.notes.insertAsset(path, bytes, name)
            val alt = name.substringBeforeLast('.')
            val md = "![$alt]($link)"
            val a = active
            if (a != null) {
                a.field.edit {
                    val start = selection.start
                    replace(selection.start, selection.end, md)
                    placeCursorAfterCharAt(start + md.length - 1)
                }
                scheduleSave()
            } else {
                val trimmed = doc.trimEnd('\n')
                setDoc(if (trimmed.isEmpty()) "$md\n" else "$trimmed\n\n$md\n")
            }
        }
    }

    fun resolveLink(link: String): File? = graph.notes.resolveLink(path, link)

    override fun onCleared() {
        flush()
    }

    private fun collapseBlankLines(text: String, around: Int): String {
        // Only touch the neighbourhood of the edit so code blocks elsewhere keep their blank lines.
        val from = (around - 4).coerceAtLeast(0)
        val to = (around + 4).coerceAtMost(text.length)
        val middle = text.substring(from, to).replace(Regex("\n{3,}"), "\n\n")
        return text.substring(0, from) + middle + text.substring(to)
    }
}
