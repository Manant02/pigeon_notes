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
 * Every block keeps its source verbatim, and so does the whitespace between blocks: the text is only
 * changed where the user edits it, so a note opened and saved again stays byte-identical (it is
 * shared with Obsidian, Typora and Nextcloud Notes). An edit (Enter, Backspace, …) replaces the
 * affected block ranges, reparses, and then says where the cursor belongs as an offset into the new
 * text; [placeCursor] finds the block holding that offset. The cursor therefore never depends on a
 * block being found at an exact position, and cannot get lost.
 *
 * @param onChanged called whenever the document text changes, to schedule a save
 */
class DocumentModel(
    initial: String = "",
    private val onChanged: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var doc by mutableStateOf("")
        private set
    var blocks by mutableStateOf<List<Block>>(emptyList())
        private set
    var active by mutableStateOf<ActiveBlock?>(null)
        private set

    // ---- undo history: whole-document snapshots, keystrokes coalesced while typing goes on ----
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    /** The text as of the last recorded change; what the next change pushes onto the undo stack. */
    private var lastText = ""
    private var lastTypedAt = 0L
    private var typing = false
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    init {
        load(initial)
    }

    /** Loads a fresh document (opening a note, or after a rename rewrote its links); history starts over. */
    fun load(text: String) {
        active = null
        setDoc(text, notify = false)
        undoStack.clear(); redoStack.clear()
        lastText = currentText(); typing = false
        canUndo = false; canRedo = false
    }

    /** Replaces the whole document, e.g. after loading or leaving source mode. */
    fun setDoc(text: String, notify: Boolean = true) {
        doc = text
        blocks = BlockParser.parse(text)
        if (notify) {
            record(typedNow = false)
            onChanged()
        }
    }

    /** Called on every keystroke in the active block, so typing becomes undoable in groups. */
    fun onTyped() = record(typedNow = true)

    /** Records an undo step and schedules a save after edits made with `setDoc(notify = false)`. */
    private fun changed() {
        record(typedNow = false)
        onChanged()
    }

    private fun record(typedNow: Boolean) {
        val text = currentText()
        if (text == lastText) return
        val now = clock()
        val continuesGroup = typedNow && typing && now - lastTypedAt < TYPING_GROUP_MS
        if (!continuesGroup) {
            undoStack.addLast(lastText)
            while (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
            redoStack.clear()
        }
        lastText = text
        typing = typedNow
        lastTypedAt = now
        canUndo = undoStack.isNotEmpty(); canRedo = false
    }

    fun undo() {
        val target = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(currentText())
        restore(target)
    }

    fun redo() {
        val target = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(currentText())
        restore(target)
    }

    /** Puts [text] in place and the cursor where it first differs from what was there. */
    private fun restore(text: String) {
        val current = currentText()
        val at = current.commonPrefixWith(text).length.coerceAtMost(text.length)
        active = null
        doc = text
        blocks = BlockParser.parse(text)
        lastText = text; typing = false
        canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty()
        onChanged()
        placeCursor(at)
    }

    /** Document text including whatever is typed in the active block, normalised to one trailing newline. */
    fun currentText(): String {
        val base = active?.let { spliced(it) } ?: doc
        return if (base.isBlank()) "" else base.trimEnd('\n') + "\n"
    }

    /** The document with the active block's current text (or [text]) folded in. */
    private fun spliced(a: ActiveBlock, text: String = a.field.text.toString()): String {
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

    /**
     * Puts the cursor at document offset [at], in whichever block holds it after the last reparse.
     * An offset between two blocks lands at the end of the block before it; one past the last block
     * opens a new line at the end of the note (which is then directly below it).
     */
    private fun placeCursor(at: Int) {
        val idx = blocks.indexOfLast { it.start <= at }
        val b = blocks.getOrNull(idx)
        when {
            b != null && at <= b.end -> activateExisting(idx, at - b.start)
            idx == blocks.lastIndex -> activateAtEnd()
            b != null -> activateExisting(idx, b.source.length)
            else -> activateExisting(0, 0)
        }
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
        val raw = a.field.text.toString()
        val text = closeFence(raw)
        when {
            a.kind == BlockKind.EMPTY || a.virtual -> if (text.isNotBlank()) setDoc(spliced(a, text))
            raw == doc.substring(a.start, a.end) -> Unit
            // A block emptied out leaves one empty line behind, not two.
            text.isBlank() -> setDoc(doc.replaceRange(a.start, minOf(a.end + 1, doc.length), ""))
            else -> setDoc(spliced(a, text))
        }
    }

    /** [text] with a code fence it leaves open closed, so it cannot swallow the blocks below it. */
    private fun closeFence(text: String): String =
        BlockParser.unclosedFence(text)?.let { text.trimEnd('\n') + "\n" + it } ?: text

    /** Writes [source] in place of the active block (or onto its empty line) and returns where it starts. */
    private fun replaceActive(a: ActiveBlock, source: String): Int {
        active = null
        val empty = a.kind == BlockKind.EMPTY || a.virtual
        setDoc(if (empty) doc.replaceRange(a.start, a.start, source + "\n") else doc.replaceRange(a.start, a.end, source), notify = false)
        return a.start
    }

    // ---- Enter / Backspace ----------------------------------------------------------------------

    /**
     * Enter in a paragraph/heading. At the start of the block this inserts an empty line above and
     * keeps the cursor on the text (Typora); at the end an empty line opens directly below; in the
     * middle the text after the cursor becomes a new block. A line opening a code fence gets its
     * closing fence and the cursor on the line between them.
     */
    fun splitActive(cursor: Int) {
        val a = active ?: return
        val text = a.field.text.toString()
        val at = cursor.coerceIn(0, text.length)
        val before = text.substring(0, at).trimEnd()
        val after = text.substring(at).trimStart()

        val fence = if (after.isEmpty()) BlockParser.unclosedFence(before) else null
        if (fence != null) {
            val start = replaceActive(a, "$before\n\n$fence")
            placeCursor(start + before.length + 1)
            changed()
            return
        }

        if (a.kind == BlockKind.EMPTY || a.virtual) {
            if (text.isBlank()) {
                // Enter on an empty line: add another empty line and stay below it.
                val pos = a.start
                val wasVirtual = a.virtual
                active = null
                setDoc(doc.replaceRange(pos, pos, "\n"), notify = false)
                if (wasVirtual) openVirtualAtEnd() else placeCursor(pos + 1)
                changed()
            } else {
                // Typed text then Enter: materialise the paragraph, then split it.
                val pos = a.start
                commit()
                placeCursor(pos + at)
                val b = active
                if (b != null && b.kind != BlockKind.EMPTY && !b.virtual) splitActive(b.field.selection.start)
            }
            return
        }

        if (text.isBlank()) {
            // The block was emptied: it becomes an empty line, and Enter adds one below that.
            commit()
            placeCursor(a.start)
            if (active?.kind == BlockKind.EMPTY) splitActive(0)
            return
        }

        active = null
        when {
            before.isEmpty() -> {
                // Cursor at the very start: push the block down, leaving an empty line above.
                val add = newlinesForBlankLineAbove(a.index)
                setDoc(doc.replaceRange(a.start, a.end, "\n".repeat(add) + closeFence(after)), notify = false)
                placeCursor(a.start + add)
            }
            after.isEmpty() -> {
                val source = closeFence(before)
                setDoc(doc.replaceRange(a.start, a.end, source), notify = false)
                openBlankLineAfter(a.start + source.length)
            }
            else -> {
                val head = closeFence(before)
                setDoc(doc.replaceRange(a.start, a.end, head + "\n\n" + closeFence(after)), notify = false)
                placeCursor(a.start + head.length + 2)
            }
        }
        changed()
    }

    /** Enter after the closing fence of a code block (or the `$$` of a formula): continue below it. */
    fun exitActive() {
        val a = active ?: return
        commit()
        val idx = blocks.indexOfLast { it.start <= a.start }
        if (idx < 0) return
        openBlankLineAfter(blocks[idx].end)
        changed()
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
                setDoc(doc.replaceRange(a.start, minOf(a.end + 1, doc.length), ""), notify = false)
                placeCursor(a.start)
            }
            kept.isEmpty() -> {
                // An empty line above the remaining list.
                val add = newlinesForBlankLineAbove(a.index)
                setDoc(doc.replaceRange(a.start, a.end, "\n".repeat(add) + rest), notify = false)
                placeCursor(a.start + add - 1)
            }
            else -> {
                setDoc(doc.replaceRange(a.start, a.end, if (rest.isEmpty()) kept else "$kept\n\n$rest"), notify = false)
                openBlankLineAfter(a.start + kept.length)
            }
        }
        changed()
    }

    // One newline ends a block and one more separates it from the next; only further newlines are
    // visible empty lines (see BlockParser.emptyLines). Blocks may also follow each other with a
    // single newline (a heading directly above its text), so an empty line between two blocks can
    // take more than one inserted newline to appear.

    /**
     * Opens an empty line directly below the block ending at [blockEnd] and puts the cursor on it.
     * Below the last block that is the new line at the end of the note.
     */
    private fun openBlankLineAfter(blockEnd: Int) {
        val next = blocks.firstOrNull { it.kind != BlockKind.EMPTY && it.start >= blockEnd }
        val newlines = doc.substring(blockEnd, next?.start ?: doc.length).count { it == '\n' }
        if (next == null && newlines < 3) {
            activateAtEnd(); return
        }
        setDoc(doc.replaceRange(blockEnd, blockEnd, "\n".repeat(maxOf(1, 3 - newlines))), notify = false)
        // The third newline after the block is the first empty line.
        var nl = blockEnd - 1
        repeat(3) { nl = doc.indexOf('\n', nl + 1) }
        placeCursor(nl)
    }

    /** How many newlines to insert in front of block [index] so an empty line shows directly above it. */
    private fun newlinesForBlankLineAbove(index: Int): Int {
        // Before the first block every newline is an empty line.
        val prev = blocks.subList(0, index).lastOrNull { it.kind != BlockKind.EMPTY } ?: return 1
        // Front matter's source includes its closing newline.
        val from = if (prev.kind == BlockKind.FRONTMATTER) prev.end - 1 else prev.end
        val newlines = doc.substring(from, blocks[index].start).count { it == '\n' }
        return maxOf(1, 3 - newlines)
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

    /**
     * Backspace at the very start of the active block. An empty line is removed ([deleteEmptyActive]);
     * otherwise the block joins the one above: a blank line above disappears, text-like blocks
     * (paragraph, heading, list, quote) take the text in, and anything else (formula, code, image,
     * table, rule) just receives the cursor at its end.
     */
    fun joinWithPrevious() {
        val a = active ?: return
        val text = a.field.text.toString()
        if (text.isEmpty()) {
            deleteEmptyActive(); return
        }
        if (a.kind == BlockKind.EMPTY || a.virtual) {
            // Text typed on an empty line is a paragraph in the making: materialise it, then join.
            val pos = a.start
            commit()
            placeCursor(pos)
            val b = active
            if (b != null && b.kind != BlockKind.EMPTY && !b.virtual && b.start == pos) joinWithPrevious()
            return
        }
        if (a.index == 0) return
        val prev = blocks[a.index - 1]
        active = null
        when (prev.kind) {
            BlockKind.EMPTY -> {
                setDoc(doc.replaceRange(a.start, a.end, text).removeRange(prev.start, prev.start + 1))
                placeCursor(a.start - 1)
            }
            BlockKind.PARAGRAPH, BlockKind.HEADING, BlockKind.LIST, BlockKind.QUOTE, BlockKind.ALERT -> {
                setDoc(doc.replaceRange(prev.start, a.end, prev.source + text))
                placeCursor(prev.end)
            }
            else -> {
                if (text != doc.substring(a.start, a.end)) setDoc(doc.replaceRange(a.start, a.end, text))
                placeCursor(prev.end)
            }
        }
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
            record(typedNow = false)
            onChanged()
        } else {
            val trimmed = doc.trimEnd('\n')
            setDoc(if (trimmed.isEmpty()) "$markdown\n" else "$trimmed\n\n$markdown\n")
        }
    }

    companion object {
        /** Keystrokes closer together than this undo as one step. */
        const val TYPING_GROUP_MS = 1_000L
        const val HISTORY_LIMIT = 200
    }
}
