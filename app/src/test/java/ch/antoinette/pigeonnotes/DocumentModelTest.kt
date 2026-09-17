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
}
