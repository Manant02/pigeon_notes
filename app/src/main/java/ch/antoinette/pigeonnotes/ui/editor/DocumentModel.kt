package ch.antoinette.pigeonnotes.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import ch.antoinette.pigeonnotes.domain.markdown.Block
import ch.antoinette.pigeonnotes.domain.markdown.BlockId
import ch.antoinette.pigeonnotes.domain.markdown.BlockKind
import ch.antoinette.pigeonnotes.domain.markdown.BlockParser

/**
 * The block being edited as raw markdown: block [index] of [DocumentModel.blocks], or with [virtual]
 * the new line at the end of the note (index == blocks.size), which only becomes a block once
 * something is typed on it. The field of an [BlockKind.EMPTY] block starts empty, whatever
 * whitespace its line holds.
 */
class ActiveBlock(
    val index: Int,
    val id: BlockId,
    val kind: BlockKind,
    val level: Int,
    val field: TextFieldState,
    val virtual: Boolean = false,
)

/**
 * The note as a list of blocks ([BlockParser.parse]) with at most one of them "active" (being typed
 * into). Pure document logic — no storage, no Android — so the editing rules are testable.
 *
 * The blocks hold the note's lines verbatim, so a note is only changed where it is edited. Enter,
 * Backspace and the rest are operations on the block list; the few helpers below that insert and
 * remove lines keep blank lines meaning what they show (see [insertEmpty]). Each edit is then
 * [adopt]ed: joined to text and read back by the parser, so the blocks on screen are always exactly
 * what the text means to any other markdown app, and the cursor is placed by line and column, which
 * joining and parsing never move.
 *
 * @param onChanged called whenever the document text changes, to schedule a save
 */
class DocumentModel(
    initial: String = "",
    private val onChanged: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var blocks by mutableStateOf<List<Block>>(emptyList())
        private set
    var active by mutableStateOf<ActiveBlock?>(null)
        private set

    /** The note text, without what is being typed in the active block. */
    val doc: String get() = BlockParser.join(blocks, finalNewline)

    /** Whether the note's last line ends with a newline; an edited note always does. */
    private var finalNewline = true

    /** The text as loaded: [currentText] returns it unchanged until something is edited. */
    private var loaded = ""
    private var nextId = 1L

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
        loaded = text
        read(text)
        undoStack.clear(); redoStack.clear()
        lastText = currentText(); typing = false
        canUndo = false; canRedo = false
    }

    /** Replaces the whole document, e.g. when leaving source mode. */
    fun setDoc(text: String) {
        active = null
        read(text)
        changed()
    }

    private fun read(text: String) {
        finalNewline = text.isEmpty() || text.endsWith("\n")
        blocks = withIds(BlockParser.parse(text), blocks)
    }

    /**
     * The note text including what is typed in the active block. Until something is edited this is
     * exactly the text as loaded; an edited note is saved without blank lines at its end.
     */
    fun currentText(): String {
        val text = BlockParser.join(active?.let(::folded) ?: blocks, finalNewline)
        if (text == loaded) return text
        val lines = BlockParser.lines(text).dropLastWhile { it.isBlank() }
        return if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
    }

    // ---- undo / redo ----------------------------------------------------------------------------

    /** Called on every keystroke in the active block, so typing becomes undoable in groups. */
    fun onTyped() = record(typedNow = true)

    /** Records an undo step and schedules a save. */
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
        val at = currentText().commonPrefixWith(text).length.coerceAtMost(text.trimEnd('\n').length)
        active = null
        read(text)
        lastText = text; typing = false
        canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty()
        onChanged()
        val line = text.take(at).count { it == '\n' }
        placeCursor(Cursor.At(line, at - (text.lastIndexOf('\n', at - 1) + 1)))
    }

    // ---- activation -----------------------------------------------------------------------------

    /** Makes block [id] editable with the cursor at [sourceOffset] within its source. */
    fun activate(id: BlockId, sourceOffset: Int) {
        val current = active
        if (current != null && !current.virtual && current.id == id) return
        commit()
        val index = blocks.indexOfFirst { it.id == id }
        if (index >= 0) activateExisting(index, sourceOffset)
    }

    private fun activateExisting(index: Int, sourceOffset: Int) {
        val b = blocks[index]
        val text = if (b.kind == BlockKind.EMPTY) "" else b.source
        active = ActiveBlock(index, b.id, b.kind, b.level, TextFieldState(text, TextRange(sourceOffset.coerceIn(0, text.length))))
    }

    /** Tap below the last block: the cursor goes to a new line at the end of the note. */
    fun activateAtEnd() {
        if (active?.virtual == true) return
        commit()
        openEnd()
    }

    /**
     * Makes the note's first block editable with the text of its `# Title` selected, so typing
     * replaces it (a new note's placeholder title). False, and nothing changes, when the note
     * doesn't start with a one-line level-1 heading.
     */
    fun selectTitle(): Boolean {
        commit()
        val index = blocks.indexOfFirst { it.kind != BlockKind.EMPTY }
        val b = blocks.getOrNull(index)?.takeIf { it.kind == BlockKind.HEADING && it.level == 1 } ?: return false
        val title = ATX_TITLE.matchEntire(b.source)?.groups?.get(1)?.range ?: return false
        active = ActiveBlock(index, b.id, b.kind, b.level, TextFieldState(b.source, TextRange(title.first, title.last + 1)))
        return true
    }

    private fun openEnd() {
        active = ActiveBlock(blocks.size, newId(), BlockKind.PARAGRAPH, 0, TextFieldState(), virtual = true)
    }

    /** Writes the active block back into the document and leaves edit mode. */
    fun commit() {
        val a = active ?: return
        val edited = folded(a)
        if (edited == blocks) active = null else adopt(edited, cursor = null)
    }

    /** The blocks with the active block's text written in: what the note is right now. */
    private fun folded(a: ActiveBlock): List<Block> {
        val text = a.field.text.toString()
        val list = blocks.toMutableList()
        when {
            a.virtual || a.kind == BlockKind.EMPTY -> {
                // Nothing typed (or only spaces): the empty line stays as it was.
                if (text.isBlank()) return blocks
                val (at, separator) = list.takeSlot(a)
                list.add(at, written(text, separator, a.id))
            }
            text == list[a.index].source -> return blocks
            // A block emptied out leaves one empty line behind.
            text.isBlank() -> list.replaceWithEmpty(a.index)
            else -> list[a.index] = list[a.index].copy(source = closeFence(text))
        }
        return list
    }

    // ---- Enter ----------------------------------------------------------------------------------

    /**
     * Enter in a paragraph, heading, image or empty line. At the start of the block an empty line
     * opens above and the cursor stays on the text (Typora); at the end an empty line opens directly
     * below; in the middle the text after the cursor becomes a new block. A line opening a code fence
     * gets its closing fence, with the cursor on the line between them.
     */
    fun splitActive(cursor: Int) {
        val a = active ?: return
        val text = a.field.text.toString()
        val at = cursor.coerceIn(0, text.length)
        val before = text.substring(0, at).trimEnd()
        val after = text.substring(at).trimStart()
        val list = blocks.toMutableList()

        if (text.isBlank()) {
            // Enter on an empty line adds another one and moves down to it. (A block whose text was
            // deleted is an empty line by now.)
            val target = when {
                a.virtual -> { list.insertEmpty(list.size); Cursor.End }
                a.kind == BlockKind.EMPTY -> cursorAt(list, list.insertEmpty(a.index + 1))
                else -> cursorAt(list, list.insertEmpty(list.replaceWithEmpty(a.index) + 1))
            }
            adopt(list, target)
            return
        }

        val (i, separator) = list.takeSlot(a)
        val fence = if (after.isEmpty()) BlockParser.unclosedFence(before) else null
        val target = when {
            fence != null -> {
                list.add(i, written("$before\n\n$fence", separator, a.id))
                cursorAt(list, i, before.length + 1)
            }
            before.isEmpty() -> {
                list.add(i, written(after, separator, a.id))
                cursorAt(list, list.insertEmpty(i) + 1)
            }
            after.isEmpty() -> {
                list.add(i, written(before, separator, a.id))
                openLineBelow(list, i)
            }
            else -> {
                list.add(i, written(before, "", a.id))
                list.add(i + 1, written(after, separator, newId()))
                cursorAt(list, i + 1)
            }
        }
        adopt(list, target)
    }

    /**
     * Enter after the closing fence of a code block (or the `$$` of a formula): the block stays as
     * it is and the cursor goes to a new line below it.
     */
    fun exitActive() {
        val a = active ?: return
        val text = a.field.text.toString()
        if (a.virtual || a.kind == BlockKind.EMPTY || text.isBlank()) {
            splitActive(text.length)
            return
        }
        val list = blocks.toMutableList()
        if (text != list[a.index].source) list[a.index] = list[a.index].copy(source = closeFence(text))
        adopt(list, openLineBelow(list, a.index))
    }

    /** Enter on an empty list item ([lineStart], [lineEnd] in the field): drop the bullet and leave the list there. */
    fun leaveListActive(lineStart: Int, lineEnd: Int) {
        val a = active ?: return
        if (a.virtual || a.kind == BlockKind.EMPTY) return
        val text = a.field.text.toString()
        val kept = text.substring(0, lineStart.coerceIn(0, text.length)).trimEnd()
        val rest = text.substring(lineEnd.coerceIn(0, text.length)).trimStart('\n')
        val list = blocks.toMutableList()
        val i = a.index
        val separator = list[i].separator
        val target = when {
            // The list was a single empty item: it turns into an empty line.
            kept.isEmpty() && rest.isEmpty() -> cursorAt(list, list.replaceWithEmpty(i))
            // The first item was empty: an empty line above the rest of the list.
            kept.isEmpty() -> {
                list[i] = list[i].copy(source = rest)
                cursorAt(list, list.insertEmpty(i))
            }
            // Otherwise the list ends with the item above, and an empty line opens below it.
            else -> {
                list[i] = list[i].copy(source = kept, separator = if (rest.isEmpty()) separator else "")
                if (rest.isNotEmpty()) list.add(i + 1, list[i].copy(source = rest, separator = separator, id = newId()))
                openLineBelow(list, i)
            }
        }
        adopt(list, target)
    }

    // ---- Backspace ------------------------------------------------------------------------------

    /**
     * Backspace in an empty active line removes that line and puts the cursor on the line directly
     * above — which may itself be another blank line, not the nearest text.
     */
    fun deleteEmptyActive() {
        val a = active ?: return
        if (a.field.text.isNotEmpty()) return
        removeLine(a)
    }

    private fun removeLine(a: ActiveBlock) {
        val list = blocks.toMutableList()
        if (a.virtual) {
            // The new line at the end sits below any empty lines there: eat one and stay at the end.
            if (list.lastOrNull()?.kind == BlockKind.EMPTY) {
                list.removeAt(list.lastIndex)
                adopt(list, Cursor.End)
            } else if (list.isNotEmpty()) {
                activateExisting(list.lastIndex, list.last().source.length)
            }
            return
        }
        list.removeBlock(a.index)
        adopt(
            list,
            when {
                a.index > 0 -> cursorAt(list, a.index - 1, list[a.index - 1].source.length)
                list.isNotEmpty() -> cursorAt(list, 0)
                else -> Cursor.End
            },
        )
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
        val emptyLine = a.virtual || a.kind == BlockKind.EMPTY
        if (text.isEmpty() || (emptyLine && text.isBlank())) {
            removeLine(a)
            return
        }
        if (a.index == 0) return
        val list = blocks.toMutableList()
        val i: Int
        if (emptyLine) {
            // Text typed on an empty line is a block in the making: write it in, then join that.
            val (at, separator) = list.takeSlot(a)
            list.add(at, written(text, separator, a.id))
            i = at
        } else {
            i = a.index
            if (text != list[i].source) list[i] = list[i].copy(source = closeFence(text))
        }
        val current = list[i]
        val prev = list[i - 1]
        val target = when (prev.kind) {
            BlockKind.EMPTY -> {
                list.removeAt(i - 1)
                cursorAt(list, i - 1)
            }
            BlockKind.PARAGRAPH, BlockKind.HEADING, BlockKind.LIST, BlockKind.QUOTE, BlockKind.ALERT -> {
                list[i - 1] = prev.copy(source = prev.source + current.source, separator = current.separator)
                list.removeAt(i)
                cursorAt(list, i - 1, prev.source.length)
            }
            else -> cursorAt(list, i - 1, prev.source.length)
        }
        adopt(list, target)
    }

    // ---- other edits ----------------------------------------------------------------------------

    /** Flips a `[ ]`/`[x]` in block [id] at [sourceOffset] without opening the block for editing. */
    fun toggleCheckbox(id: BlockId, sourceOffset: Int) {
        commit()
        val i = blocks.indexOfFirst { it.id == id }
        if (i < 0) return
        val s = blocks[i].source
        val at = sourceOffset
        if (at < 0 || at + 3 > s.length || s[at] != '[' || s[at + 2] != ']') return
        val list = blocks.toMutableList()
        list[i] = list[i].copy(source = s.replaceRange(at, at + 3, if (s[at + 1] == ' ') "[x]" else "[ ]"))
        adopt(list, cursor = null)
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
            val list = blocks.toMutableList()
            list.separateBefore(list.size)
            list.add(written(markdown, null, newId()))
            adopt(list, cursor = null)
        }
    }

    // ---- applying an edit -----------------------------------------------------------------------

    /** Where the cursor goes after an edit: a line and column of the note, or the new line at the end. */
    private sealed interface Cursor {
        data class At(val line: Int, val column: Int) : Cursor
        data object End : Cursor
    }

    /**
     * Makes [edited] the document. It is joined to text and read back by the parser, so the blocks
     * are always exactly what the text means (a fence left open, a paragraph that runs on, ...);
     * then the cursor goes to [cursor], if given, and the change is recorded.
     */
    private fun adopt(edited: List<Block>, cursor: Cursor?) {
        active = null
        finalNewline = true
        blocks = withIds(BlockParser.parse(BlockParser.join(edited)), edited)
        if (cursor != null) placeCursor(cursor)
        changed()
    }

    /**
     * Activates the block holding [cursor]. A separator is not a line of its own on screen, so a
     * cursor on one goes to the end of its block; past the last line it is the new line at the end.
     */
    private fun placeCursor(cursor: Cursor) {
        if (cursor is Cursor.At) {
            val target = cursor.line.coerceAtLeast(0)
            var line = 0
            for ((i, b) in blocks.withIndex()) {
                val lines = b.source.split('\n')
                val k = target - line
                if (k < lines.size) {
                    activateExisting(i, lines.take(k).sumOf { it.length + 1 } + cursor.column.coerceIn(0, lines[k].length))
                    return
                }
                line += lines.size
                if (b.separator != null) {
                    if (target == line) {
                        activateExisting(i, b.source.length)
                        return
                    }
                    line++
                }
            }
        }
        openEnd()
    }

    /** The cursor at [offset] in the source of block [index] of [list]. */
    private fun cursorAt(list: List<Block>, index: Int, offset: Int = 0): Cursor {
        val source = list[index].source
        val o = offset.coerceIn(0, source.length)
        val firstLine = (0 until index).sumOf { list[it].lineCount }
        return Cursor.At(firstLine + source.take(o).count { it == '\n' }, o - (source.lastIndexOf('\n', o - 1) + 1))
    }

    /**
     * Gives the blocks read back from the parser the ids of the blocks they come from, so the screen
     * keeps its items (and loaded images) across edits: unchanged blocks at the start and the end
     * keep theirs, the changed ones in between take over the old ids in order.
     */
    private fun withIds(parsed: List<Block>, previous: List<Block>): List<Block> {
        var head = 0
        while (head < parsed.size && head < previous.size && parsed[head].sameAs(previous[head])) head++
        var tail = 0
        while (tail < parsed.size - head && tail < previous.size - head &&
            parsed[parsed.size - 1 - tail].sameAs(previous[previous.size - 1 - tail])
        ) tail++
        return parsed.mapIndexed { i, b ->
            val old = when {
                i < head -> previous[i]
                i >= parsed.size - tail -> previous[previous.size - (parsed.size - i)]
                i < previous.size - tail -> previous[i]
                else -> null
            }
            b.copy(id = old?.id?.takeIf { it != BlockId.NONE } ?: newId())
        }
    }

    private fun newId() = BlockId(nextId++)

    /** New text for the list; the parser decides what kind of block it is when the edit is adopted. */
    private fun written(source: String, separator: String?, id: BlockId) =
        Block(BlockKind.PARAGRAPH, closeFence(source), separator = separator, id = id)

    /** [text] with a code fence it leaves open closed, so it cannot swallow the blocks below it. */
    private fun closeFence(text: String): String =
        BlockParser.unclosedFence(text)?.let { text.trimEnd('\n') + "\n" + it } ?: text

    // ---- list operations that keep blank lines meaningful ---------------------------------------
    // One blank line after a block only separates it from what follows (Block.separator); an empty
    // line shows when there is a blank line beyond that. Blocks can also follow each other with no
    // blank line between (a heading right above its text). These operations keep that consistent,
    // so an edit shows exactly the empty lines it means to.

    /** Inserts an empty line at [index] and returns [index]. */
    private fun MutableList<Block>.insertEmpty(index: Int): Int {
        // Right below a block without a separator the new line would just become that separator.
        separateBefore(index)
        add(index, Block(BlockKind.EMPTY, "", id = newId()))
        return index
    }

    /** Gives the block before [index] a separator if it has none, so what follows it starts after a blank line. */
    private fun MutableList<Block>.separateBefore(index: Int) {
        val prev = getOrNull(index - 1) ?: return
        if (prev.kind != BlockKind.EMPTY && prev.separator == null) set(index - 1, prev.copy(separator = ""))
    }

    /** Removes block [index]. A block right above it takes over its separator, so the blocks around it stay apart. */
    private fun MutableList<Block>.removeBlock(index: Int) {
        val removed = removeAt(index)
        if (removed.kind == BlockKind.EMPTY || index == size) return
        val prev = getOrNull(index - 1) ?: return
        if (prev.kind != BlockKind.EMPTY && prev.separator == null) set(index - 1, prev.copy(separator = removed.separator ?: ""))
    }

    /** Replaces block [index] with one empty line; returns [index]. */
    private fun MutableList<Block>.replaceWithEmpty(index: Int): Int {
        removeBlock(index)
        return insertEmpty(index)
    }

    /**
     * Takes the active block out of the list so text can be written in its place, and returns where
     * that text goes and the separator its last block gets: a block keeps its own. Text typed on an
     * empty line gets a blank line after it, so the lines below stay where they are; text typed at
     * the end goes after a blank line.
     */
    private fun MutableList<Block>.takeSlot(a: ActiveBlock): Pair<Int, String?> = when {
        a.virtual -> {
            separateBefore(size)
            size to null
        }
        a.kind == BlockKind.EMPTY -> {
            removeAt(a.index)
            a.index to ""
        }
        else -> a.index to removeAt(a.index).separator
    }

    /** Opens an empty line directly below block [index] of [list]; below the last block, the new line at the end. */
    private fun openLineBelow(list: MutableList<Block>, index: Int): Cursor =
        if (index == list.lastIndex) Cursor.End else cursorAt(list, list.insertEmpty(index + 1))

    companion object {
        /** Keystrokes closer together than this undo as one step. */
        const val TYPING_GROUP_MS = 1_000L
        const val HISTORY_LIMIT = 200

        /** `# Title`: group 1 is the title text, without the spaces around it. */
        private val ATX_TITLE = Regex("""#[ \t]+(\S(?:.*\S)?)[ \t]*""")
    }
}
