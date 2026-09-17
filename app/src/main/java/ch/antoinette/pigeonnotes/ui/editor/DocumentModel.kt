package ch.antoinette.pigeonnotes.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import ch.antoinette.pigeonnotes.domain.markdown.Block
import ch.antoinette.pigeonnotes.domain.markdown.BlockKind
import ch.antoinette.pigeonnotes.domain.markdown.BlockParser

/**
 * The block currently being edited as raw markdown. [index] is its position in [DocumentModel.blocks];
 * a [virtual] block is a not-yet-existing paragraph appended after the last block (index == blocks.size).
 * An [BlockKind.EMPTY] block edits an empty line: its text is spliced in as `text + "\n"`.
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
 * The note text as a list of blocks with at most one of them "active" (being typed into).
 * Pure document logic — no storage, no Android — so the whitespace and Enter/Backspace rules are testable.
 *
 * @param onChanged called whenever the document text changes, to schedule a save
 */
class DocumentModel(initial: String = "", private val onChanged: () -> Unit = {}) {
    var doc by mutableStateOf("")
        private set
    var blocks by mutableStateOf<List<Block>>(emptyList())
        private set
    var active by mutableStateOf<ActiveBlock?>(null)
        private set

    init {
        setDoc(initial, notify = false)
    }

    /** Replaces the whole document, e.g. after loading or leaving source mode. */
    fun setDoc(text: String, notify: Boolean = true) {
        doc = text
        blocks = BlockParser.parse(text)
        if (notify) onChanged()
    }

    /** Document text including whatever is typed in the active block, normalised to one trailing newline. */
    fun currentText(): String {
        val base = active?.let { spliced(it) } ?: doc
        return if (base.isBlank()) "" else base.trimEnd('\n') + "\n"
    }

    /** The document with the active block's current text folded in. */
    private fun spliced(a: ActiveBlock): String {
        val text = a.field.text.toString()
        return when {
            a.kind == BlockKind.EMPTY || a.virtual -> if (text.isEmpty()) doc else doc.replaceRange(a.start, a.start, text + "\n")
            else -> doc.replaceRange(a.start, a.end, text)
        }
    }

    // ---- activation -----------------------------------------------------------------------------

    /** Makes block [index] editable with the cursor at [sourceOffset] within the block source. */
    fun activate(index: Int, sourceOffset: Int) {
        val current = active
        if (current != null && !current.virtual && current.index == index) return
        commit()
        activateExisting(index, sourceOffset)
    }

    private fun activateExisting(index: Int, sourceOffset: Int) {
        val b = blocks.getOrNull(index) ?: return
        val cursor = sourceOffset.coerceIn(0, b.source.length)
        active = ActiveBlock(index, b.kind, b.level, b.start, b.end, TextFieldState(b.source, TextRange(cursor)))
    }

    private fun activateEmptyAt(position: Int) {
        val idx = blocks.indexOfFirst { it.kind == BlockKind.EMPTY && it.start == position }
        if (idx >= 0) activateExisting(idx, 0) else activateAtEnd()
    }

    /**
     * Tap below the last block: start a new paragraph at the end. Existing trailing blank lines are
     * kept (they are visible EMPTY blocks); only a missing separator is added.
     */
    fun activateAtEnd() {
        val a = active
        if (a != null && a.virtual) return
        commit()
        if (doc.isNotEmpty() && !doc.endsWith("\n\n")) {
            setDoc(doc.trimEnd('\n') + "\n\n", notify = false)
        }
        openVirtualAtEnd()
    }

    private fun openVirtualAtEnd() {
        active = ActiveBlock(blocks.size, BlockKind.PARAGRAPH, 0, doc.length, doc.length, TextFieldState(), virtual = true)
    }

    /** Writes the active block back into the document and leaves edit mode. */
    fun commit() {
        val a = active ?: return
        active = null
        val text = a.field.text.toString()
        when {
            a.kind == BlockKind.EMPTY || a.virtual -> if (text.isNotBlank()) setDoc(spliced(a))
            text == doc.substring(a.start, a.end) -> Unit
            // A block emptied out leaves one empty line behind, not two.
            text.isBlank() -> setDoc(doc.replaceRange(a.start, minOf(a.end + 1, doc.length), ""))
            else -> setDoc(spliced(a))
        }
    }

    // ---- Enter / Backspace ----------------------------------------------------------------------

    /**
     * Enter in a paragraph/heading. At the start of the block this inserts an empty line above and
     * keeps the cursor on the text (Typora); otherwise the text after the cursor becomes a new block.
     */
    fun splitActive(cursor: Int) {
        val a = active ?: return
        val text = a.field.text.toString()
        val at = cursor.coerceIn(0, text.length)
        val before = text.substring(0, at).trimEnd()
        val after = text.substring(at).trimStart()

        if (a.kind == BlockKind.EMPTY || a.virtual) {
            if (text.isBlank()) {
                // Enter on an empty line: add another empty line and stay below it.
                val pos = a.start
                val wasVirtual = a.virtual
                active = null
                setDoc(doc.replaceRange(pos, pos, "\n"))
                if (wasVirtual) openVirtualAtEnd() else activateEmptyAt(pos + 1)
            } else {
                // Typed text then Enter: materialise the paragraph, then split it.
                val pos = a.start
                commit()
                val idx = blocks.indexOfFirst { it.start == pos }
                if (idx >= 0) {
                    activateExisting(idx, at)
                    splitActive(at)
                }
            }
            return
        }

        when {
            before.isEmpty() && after.isNotEmpty() -> {
                // Cursor at the very start: push the block down, leaving an empty line above.
                active = null
                setDoc(doc.replaceRange(a.start, a.start, "\n"))
                val idx = blocks.indexOfFirst { it.start == a.start + 1 }
                if (idx >= 0) activateExisting(idx, 0)
            }
            after.isEmpty() -> {
                active = null
                setDoc(doc.replaceRange(a.start, a.end, before))
                openEmptyLineAfter(a.start + before.length)
            }
            else -> {
                active = null
                setDoc(doc.replaceRange(a.start, a.end, "$before\n\n$after"))
                val idx = blocks.indexOfFirst { it.start == a.start + before.length + 2 }
                if (idx >= 0) activateExisting(idx, 0)
            }
        }
    }

    /** Enter on an empty list item: drop the bullet and continue below the list. */
    fun leaveListActive(lineStart: Int, lineEnd: Int) {
        val a = active ?: return
        val text = a.field.text.toString()
        val kept = text.substring(0, lineStart.coerceIn(0, text.length)).trimEnd()
        val rest = text.substring(lineEnd.coerceIn(0, text.length)).trimStart('\n')
        active = null
        when {
            kept.isEmpty() && rest.isEmpty() -> {
                // The list was a single empty item: replace it with one empty line.
                setDoc(doc.replaceRange(a.start, minOf(a.end + 1, doc.length), ""))
                activateEmptyAt(a.start)
            }
            kept.isEmpty() -> {
                setDoc(doc.replaceRange(a.start, a.end, "\n$rest")) // empty line above the remaining list
                activateEmptyAt(a.start)
            }
            else -> {
                setDoc(doc.replaceRange(a.start, a.end, if (rest.isEmpty()) kept else "$kept\n\n$rest"))
                openEmptyLineAfter(a.start + kept.length)
            }
        }
    }

    /** Creates (if needed) and activates an empty line right after the block ending at [blockEnd]. */
    private fun openEmptyLineAfter(blockEnd: Int) {
        if (doc.substring(blockEnd).trim('\n').isEmpty()) {
            activateAtEnd(); return
        }
        setDoc(doc.replaceRange(blockEnd, blockEnd, "\n"))
        activateEmptyAt(blockEnd + 2)
    }

    /**
     * Backspace in an empty active line removes that line and puts the cursor on the line directly
     * above — which may itself be another blank line, not the nearest text.
     */
    fun deleteEmptyActive() {
        val a = active ?: return
        if (a.field.text.isNotEmpty()) return
        active = null
        when {
            a.virtual -> {
                // The virtual line sits after the trailing blank lines: eat one and stay at the end.
                if (doc.endsWith("\n\n\n")) {
                    setDoc(doc.dropLast(1))
                    openVirtualAtEnd()
                    return
                }
                setDoc(doc.trimEnd('\n').let { if (it.isEmpty()) "" else it + "\n" }, notify = false)
            }
            a.kind == BlockKind.EMPTY -> setDoc(doc.removeRange(a.start, a.start + 1))
            else -> setDoc(doc.replaceRange(a.start, minOf(a.end + 2, doc.length), "")) // block plus separator
        }
        val idx = blocks.indexOfLast { it.start < a.start }
        if (idx >= 0) activateExisting(idx, blocks[idx].source.length)
    }

    /** Flips a `[ ]`/`[x]` in block [blockIndex] at [sourceOffset] without opening the block for editing. */
    fun toggleCheckbox(blockIndex: Int, sourceOffset: Int) {
        commit()
        val b = blocks.getOrNull(blockIndex) ?: return
        val at = b.start + sourceOffset
        if (at + 3 > doc.length || doc[at] != '[' || doc[at + 2] != ']') return
        setDoc(doc.replaceRange(at, at + 3, if (doc[at + 1] == ' ') "[x]" else "[ ]"))
    }

    /** Inserts markdown at the cursor of the active block, or as a new block at the end. */
    fun insertAtCursor(markdown: String) {
        val a = active
        if (a != null) {
            a.field.edit {
                val start = selection.start
                replace(selection.start, selection.end, markdown)
                placeCursorAfterCharAt((start + markdown.length - 1).coerceIn(0, length - 1))
            }
            onChanged()
        } else {
            val trimmed = doc.trimEnd('\n')
            setDoc(if (trimmed.isEmpty()) "$markdown\n" else "$trimmed\n\n$markdown\n")
        }
    }
}
