package ch.antoinette.pigeonnotes

import androidx.compose.foundation.text.input.placeCursorAtEnd
import ch.antoinette.pigeonnotes.domain.markdown.BlockKind
import ch.antoinette.pigeonnotes.ui.editor.DocumentModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentModelTest {

    private fun model(text: String) = DocumentModel(text)

    /** Activates the block at [index], types [text] (replacing its content) and returns the model. */
    private fun DocumentModel.type(index: Int, text: String, cursorAtEnd: Boolean = true): DocumentModel {
        activate(index, 0)
        active!!.field.edit {
            replace(0, length, text)
            if (cursorAtEnd) placeCursorAtEnd() else placeCursorBeforeCharAt(0)
        }
        return this
    }

    @Test
    fun `enter at the start of a block inserts an empty line above`() {
        val m = model("One\n\nTwo\n")
        m.activate(1, 0) // "Two"
        m.splitActive(0)
        assertEquals("One\n\n\nTwo\n", m.doc)
        assertEquals(listOf(BlockKind.PARAGRAPH, BlockKind.EMPTY, BlockKind.PARAGRAPH), m.blocks.map { it.kind })
        // The cursor stays with the text that moved down.
        assertEquals("Two", m.active!!.field.text.toString())
    }

    @Test
    fun `enter at the end opens an empty line below`() {
        val m = model("One\n\nTwo\n")
        m.activate(0, 3)
        m.splitActive(3)
        assertEquals(BlockKind.EMPTY, m.active!!.kind)
        m.active!!.field.edit { replace(0, length, "Mid") }
        m.commit()
        assertEquals("One\n\nMid\n\nTwo\n", m.doc)
    }

    @Test
    fun `enter in the middle splits the block`() {
        val m = model("Hello world\n")
        m.activate(0, 5)
        m.splitActive(5)
        assertEquals("Hello\n\nworld\n", m.doc)
        assertEquals("world", m.active!!.field.text.toString())
    }

    @Test
    fun `repeated enter on empty lines stacks whitespace`() {
        val m = model("One\n")
        m.activate(0, 3)
        m.splitActive(3)      // empty line below "One"
        m.splitActive(0)      // another
        m.splitActive(0)      // another
        m.active!!.field.edit { replace(0, length, "End") }
        m.commit()
        assertEquals("One\n\n\n\nEnd\n", m.doc)
    }

    @Test
    fun `typing in an empty line keeps the surrounding whitespace`() {
        val m = model("A\n\n\n\nB\n")
        val empties = m.blocks.withIndex().filter { it.value.kind == BlockKind.EMPTY }
        assertEquals(2, empties.size)
        m.type(empties.first().index, "Mid")
        m.commit()
        assertEquals("A\n\nMid\n\n\nB\n", m.doc)
    }

    @Test
    fun `backspace on an empty line removes it and moves up`() {
        val m = model("One\n\n\n\nTwo\n")
        val empty = m.blocks.indexOfFirst { it.kind == BlockKind.EMPTY }
        m.activate(empty, 0)
        m.deleteEmptyActive()
        assertEquals("One\n\n\nTwo\n", m.doc)
        assertEquals("One", m.active!!.field.text.toString())
    }

    @Test
    fun `backspace steps through consecutive blank lines before reaching text`() {
        val m = model("One\n\n\n\n\nTwo\n") // three blank lines between the paragraphs
        assertEquals(3, m.blocks.count { it.kind == BlockKind.EMPTY })
        // Start on the last blank line.
        m.activate(m.blocks.indexOfLast { it.kind == BlockKind.EMPTY }, 0)

        m.deleteEmptyActive()
        assertEquals("One\n\n\n\nTwo\n", m.doc)
        assertEquals("lands on the blank line above, not on the text", BlockKind.EMPTY, m.active!!.kind)

        m.deleteEmptyActive()
        assertEquals("One\n\n\nTwo\n", m.doc)
        assertEquals(BlockKind.EMPTY, m.active!!.kind)

        // Only the last one falls through to the paragraph above.
        m.deleteEmptyActive()
        assertEquals("One\n\nTwo\n", m.doc)
        assertEquals("One", m.active!!.field.text.toString())
    }

    @Test
    fun `backspace at the end of the note eats trailing blank lines one at a time`() {
        val m = model("One\n")
        m.activate(0, 3)
        m.splitActive(3)   // blank line below "One", cursor on it
        m.splitActive(0)   // another blank line
        assertEquals("One\n\n\n", m.doc)

        m.deleteEmptyActive()
        assertEquals("One\n\n", m.doc)
        assertTrue("stays at the end rather than jumping into the text", m.active!!.virtual)

        m.deleteEmptyActive()
        assertEquals("One", m.active!!.field.text.toString())
    }

    @Test
    fun `emptying a block leaves a single empty line`() {
        val m = model("One\n\nTwo\n\nThree\n")
        m.type(1, "")
        m.commit()
        assertEquals("One\n\n\nThree\n", m.doc)
        assertEquals(listOf(BlockKind.PARAGRAPH, BlockKind.EMPTY, BlockKind.PARAGRAPH), m.blocks.map { it.kind })
    }

    @Test
    fun `leaving a list on an empty item continues below it`() {
        val m = model("- one\n- two\n\nAfter\n")
        m.activate(0, 0)
        // The user typed a third, empty bullet and pressed Enter on it.
        m.active!!.field.edit { replace(0, length, "- one\n- two\n- ") }
        val src = m.active!!.field.text.toString()
        m.leaveListActive(src.indexOf("- two") + 6, src.length)
        assertEquals("- one\n- two\n\n\nAfter\n", m.doc)
        assertEquals(BlockKind.EMPTY, m.active!!.kind)
        m.active!!.field.edit { replace(0, length, "Next") }
        m.commit()
        assertEquals("- one\n- two\n\nNext\n\nAfter\n", m.doc)
    }

    @Test
    fun `checkbox toggles without opening the block`() {
        val m = model("- [ ] todo\n- [x] done\n")
        val src = m.blocks[0].source
        m.toggleCheckbox(0, src.indexOf("[ ]"))
        assertEquals("- [x] todo\n- [x] done\n", m.doc)
        assertNull(m.active)
        m.toggleCheckbox(0, m.blocks[0].source.indexOf("[x]"))
        assertEquals("- [ ] todo\n- [x] done\n", m.doc)
    }

    @Test
    fun `appending at the end of an empty note`() {
        val m = model("")
        m.activateAtEnd()
        assertNotNull(m.active)
        m.active!!.field.edit { replace(0, length, "# Title") }
        m.commit()
        assertEquals("# Title\n", m.currentText())
        assertEquals(listOf(BlockKind.HEADING), m.blocks.map { it.kind })
    }

    @Test
    fun `current text includes the block being typed`() {
        val m = model("One\n")
        m.activate(0, 3)
        m.active!!.field.edit { replace(0, length, "One more") }
        assertEquals("One more\n", m.currentText())
        assertTrue("doc only changes on commit", m.doc == "One\n")
    }

    // ---- Backspace at the start of a line with text -------------------------------------------

    @Test
    fun `backspace at the start after a formula removes the blank line and lands on the formula`() {
        val m = model("$$\nx\n$$\n\n\nText\n")
        // the extra blank line between formula and text is an EMPTY block
        assertEquals(listOf(BlockKind.MATH, BlockKind.EMPTY, BlockKind.PARAGRAPH), m.blocks.map { it.kind })
        m.activate(2, 0)
        m.joinWithPrevious()
        assertEquals("$$\nx\n$$\n\nText\n", m.doc)
        assertEquals("Text", m.active!!.field.text.toString())
        assertEquals(0, m.active!!.field.selection.start)

        // No blank line left: the cursor moves onto the formula, its source untouched.
        m.joinWithPrevious()
        assertEquals("$$\nx\n$$\n\nText\n", m.doc)
        assertEquals(BlockKind.MATH, m.active!!.kind)
        assertEquals("$$\nx\n$$".length, m.active!!.field.selection.start)
    }

    @Test
    fun `backspace at the start of a paragraph joins it to the list above`() {
        val m = model("- a\n- b\n\ntext\n")
        m.activate(1, 0)
        m.joinWithPrevious()
        assertEquals("- a\n- btext\n", m.doc)
        assertEquals(BlockKind.LIST, m.active!!.kind)
        assertEquals("- a\n- b".length, m.active!!.field.selection.start)
    }

    @Test
    fun `backspace at the start of an empty line after a list still removes the line`() {
        val m = model("- a\n\n\ntext\n")
        m.activate(1, 0) // the EMPTY block
        m.joinWithPrevious()
        assertEquals("- a\n\ntext\n", m.doc)
        assertEquals(BlockKind.LIST, m.active!!.kind)
    }

    @Test
    fun `backspace at the start of the first block does nothing`() {
        val m = model("One\n\nTwo\n")
        m.activate(0, 0)
        m.joinWithPrevious()
        assertEquals("One\n\nTwo\n", m.doc)
        assertEquals(0, m.active!!.index)
    }

    // ---- Enter where blocks follow each other without a blank line ------------------------------

    @Test
    fun `enter at the end of a heading directly above text opens a line right below it`() {
        val m = model("# Title\nText\n\nMore\n")
        assertEquals(listOf(BlockKind.HEADING, BlockKind.PARAGRAPH, BlockKind.PARAGRAPH), m.blocks.map { it.kind })
        m.activate(0, 7)
        m.splitActive(7)
        assertEquals("the cursor stays below the heading, not at the end of the note", 1, m.active!!.index)
        assertEquals(BlockKind.EMPTY, m.active!!.kind)
        m.active!!.field.edit { replace(0, length, "Mid") }
        m.commit()
        assertEquals("# Title\n\nMid\n\nText\n\nMore\n", m.doc)
    }

    @Test
    fun `enter at the end of a paragraph directly above a code block`() {
        val m = model("Intro\n```\ncode\n```\n\nEnd\n")
        assertEquals(BlockKind.CODE, m.blocks[1].kind)
        m.activate(0, 5)
        m.splitActive(5)
        assertEquals(1, m.active!!.index)
        assertEquals(BlockKind.EMPTY, m.active!!.kind)
        assertEquals(BlockKind.CODE, m.blocks[2].kind)
    }

    @Test
    fun `enter at the start of text directly below a heading opens a line above it`() {
        val m = model("# Title\nText\n")
        m.activate(1, 0)
        m.splitActive(0)
        assertEquals(listOf(BlockKind.HEADING, BlockKind.EMPTY, BlockKind.PARAGRAPH), m.blocks.map { it.kind })
        assertEquals("Text", m.active!!.field.text.toString())
        assertEquals(0, m.active!!.field.selection.start)
    }

    @Test
    fun `enter at the start keeps what was typed in the block`() {
        val m = model("One\n\nTwo\n")
        m.type(1, "Changed", cursorAtEnd = false)
        m.splitActive(0)
        assertEquals("One\n\n\nChanged\n", m.currentText())
    }

    @Test
    fun `enter at the end of the last block goes to the line below it`() {
        val m = model("One\n\nTwo\n")
        m.activate(1, 3)
        m.splitActive(3)
        assertTrue(m.active!!.virtual)
        m.active!!.field.edit { replace(0, length, "Three") }
        m.commit()
        assertEquals("One\n\nTwo\n\nThree\n", m.currentText())
    }

    // ---- code blocks ---------------------------------------------------------------------------

    @Test
    fun `enter after the closing fence continues below the code block`() {
        val m = model("```\ncode\n```\n\nAfter\n")
        m.activate(0, "```\ncode\n```".length)
        m.exitActive()
        assertEquals(BlockKind.EMPTY, m.active!!.kind)
        assertEquals(1, m.active!!.index)
        m.active!!.field.edit { replace(0, length, "Between") }
        m.commit()
        assertEquals("```\ncode\n```\n\nBetween\n\nAfter\n", m.doc)
    }

    @Test
    fun `enter after the closing fence of the last block starts a new line at the end`() {
        val m = model("```\ncode\n```\n")
        m.activate(0, "```\ncode\n```".length)
        m.exitActive()
        assertTrue(m.active!!.virtual)
        m.active!!.field.edit { replace(0, length, "After") }
        m.commit()
        assertEquals("```\ncode\n```\n\nAfter\n", m.currentText())
    }

    @Test
    fun `enter after typing a fence opens the code block with its closing fence`() {
        val m = model("One\n\nTwo\n")
        m.activate(0, 3)
        m.splitActive(3) // empty line below "One"
        m.active!!.field.edit { replace(0, length, "```kotlin"); placeCursorAtEnd() }
        m.splitActive(m.active!!.field.text.length)
        assertEquals("One\n\n```kotlin\n\n```\n\nTwo\n", m.doc)
        assertEquals(BlockKind.CODE, m.active!!.kind)
        assertEquals("cursor on the line inside the fences", "```kotlin\n".length, m.active!!.field.selection.start)
        assertEquals(BlockKind.PARAGRAPH, m.blocks.last().kind)
    }

    @Test
    fun `enter after a fence in a paragraph opens the code block`() {
        val m = model("Two\n")
        m.type(0, "```")
        m.splitActive(3)
        assertEquals("```\n\n```\n", m.doc)
        assertEquals(BlockKind.CODE, m.active!!.kind)
        assertEquals(4, m.active!!.field.selection.start)
    }

    @Test
    fun `a fence left open is closed so it cannot swallow the note`() {
        val m = model("One\n\nTwo\n")
        m.type(0, "```")
        m.commit()
        assertEquals("```\n```\n\nTwo\n", m.doc)
        assertEquals(listOf(BlockKind.CODE, BlockKind.PARAGRAPH), m.blocks.map { it.kind })
    }

    // ---- the text is kept verbatim -------------------------------------------------------------

    private val samples = listOf(
        "---\ntitle: x\n---\n\n# Title\nText right below\n\n\n\nAfter blank lines\n",
        "* star bullets\n* second\n\n__bold__ and *it*\n\n1) one\n2) two\n",
        "Para  \nwith hard break\n\n```kotlin\nval a = 1\n\n\nval b = 2\n```\n| a | b |\n|---|---|\n| 1 | 2 |\n",
        "$$\nx^2\n$$\n\n> quote\n> more\n\n> [!NOTE]\n> alert\n\n---\n\n![img](a.assets/p.png)\n",
        "\n\nStarts with blank lines\n   \nand spaces\n\tIndented with tab\n",
        "Windows\r\nline endings\r\n\r\nSecond\r\n",
    )

    @Test
    fun `opening and committing every block without edits leaves the text byte-identical`() {
        for (text in samples) {
            val m = model(text)
            assertEquals(text, m.currentText())
            for (i in m.blocks.indices) {
                m.activate(i, 0)
                m.commit()
                assertEquals("block $i of ${text.trim().take(20)}", text, m.doc)
            }
            assertTrue(!m.canUndo)
        }
    }

    @Test
    fun `an edit changes only the edited block`() {
        val text = samples[2]
        val m = model(text)
        val code = m.blocks.indexOfFirst { it.kind == BlockKind.CODE }
        val src = m.blocks[code].source
        m.type(code, src.replace("val b = 2", "val b = 3"))
        m.commit()
        assertEquals(text.replace("val b = 2", "val b = 3"), m.doc)
    }

    // ---- undo / redo ---------------------------------------------------------------------------

    private class Clock(var now: Long = 0L) : () -> Long {
        override fun invoke() = now
    }

    @Test
    fun `typing undoes in groups and structural edits are separate steps`() {
        val clock = Clock()
        val m = DocumentModel("One\n\nTwo\n", clock = clock)
        m.activate(0, 3)
        for (c in "abc") {
            m.active!!.field.edit { append(c.toString()) }
            m.onTyped(); clock.now += 100
        }
        clock.now += 5_000
        m.active!!.field.edit { append("d") }
        m.onTyped()
        m.splitActive(m.active!!.field.text.length) // Enter at the end
        assertEquals("Oneabcd\n\n\nTwo\n", m.doc)
        assertTrue(m.canUndo)

        m.undo()
        assertEquals("Oneabcd\n\nTwo\n", m.currentText())
        m.undo()
        assertEquals("Oneabc\n\nTwo\n", m.currentText())
        assertEquals("cursor sits where the text differs", 6, m.active!!.field.selection.start)
        m.undo()
        assertEquals("One\n\nTwo\n", m.currentText())
        assertTrue(!m.canUndo)
        assertTrue(m.canRedo)

        m.redo()
        assertEquals("Oneabc\n\nTwo\n", m.currentText())
        m.redo(); m.redo()
        assertEquals("Oneabcd\n\n\nTwo\n", m.currentText())
        assertTrue(!m.canRedo)
    }

    @Test
    fun `a new edit after undo drops the redo history`() {
        val m = DocumentModel("One\n")
        m.toggleCheckbox(0, 0) // no-op: not a checkbox
        m.activate(0, 3)
        m.active!!.field.edit { append("!") }; m.onTyped()
        m.undo()
        assertTrue(m.canRedo)
        m.activate(0, 0)
        m.active!!.field.edit { replace(0, 0, "?") }; m.onTyped()
        assertTrue(!m.canRedo)
        assertEquals("?One\n", m.currentText())
    }

    @Test
    fun `checkbox toggles are undoable and load resets the history`() {
        val m = DocumentModel("- [ ] a\n")
        m.toggleCheckbox(0, 2)
        assertEquals("- [x] a\n", m.doc)
        m.undo()
        assertEquals("- [ ] a\n", m.doc)
        m.load("fresh\n")
        assertTrue(!m.canUndo && !m.canRedo)
    }
}
