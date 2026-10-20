package ch.antoinette.pigeonnotes

import ch.antoinette.pigeonnotes.domain.markdown.BlockId
import ch.antoinette.pigeonnotes.domain.markdown.BlockKind
import ch.antoinette.pigeonnotes.domain.markdown.BlockParser
import ch.antoinette.pigeonnotes.ui.editor.DocumentModel
import ch.antoinette.pigeonnotes.ui.editor.EnterAction
import ch.antoinette.pigeonnotes.ui.editor.enterAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Random editing sessions on random notes, the way the editor drives the model (taps, typing, Enter
 * through [enterAction], Backspace at the start of a block, undo). After every step the model must
 * be consistent, and keys that do not delete text must not lose any.
 */
class EditorFuzzTest {

    private val pieces = listOf(
        "# Title", "## Sub heading", "Some text", "more *text* here", "Para\nwith two lines", "  indented text",
        "- a\n- b", "1. one\n2. two", "- [ ] todo\n- [x] done", "- loose\n\n- list",
        "```\ncode\n```", "```kt\nval x = 1\n\nval y = 2\n```", "```\nunclosed fence",
        "$$\nx^2\n$$", "> quote\n> more", "> [!NOTE]\n> alert", "![img](a.png)", "| a | b |\n|---|---|\n| 1 | 2 |",
        "---", "<div>\nhtml\n</div>", "text  ",
    )
    private val gaps = listOf("\n", "\n\n", "\n\n", "\n\n\n", "\n\n\n\n", "\n  \n", "\n\t\n\n")
    private val snippets = listOf("x", "word ", "Ünïcödé", " ", "# ", "- ", "- [ ] ", "> ", "```", "~~~", "$$", "**", "[", "1. ")

    private fun randomNote(r: Random): String {
        val sb = StringBuilder()
        if (r.nextInt(8) == 0) sb.append("---\ntitle: t\n---\n")
        if (r.nextInt(6) == 0) sb.append("\n")
        repeat(1 + r.nextInt(7)) { i ->
            if (i > 0) sb.append(gaps.random(r))
            sb.append(pieces.random(r))
        }
        sb.append(listOf("", "\n", "\n", "\n", "\n\n", "\n\n\n").random(r))
        return sb.toString()
    }

    /** The letters of [text], which no Enter or join may lose. */
    private fun letters(text: String) = text.filter { it.isLetter() }

    @Test
    fun `random editing keeps the model consistent and never loses text`() {
        val r = Random(20261019)
        repeat(600) { session ->
            val note = randomNote(r)
            val m = DocumentModel(note)
            val log = StringBuilder("session $session, note ${note.show()}")
            assertEquals(log.toString(), note, m.currentText())
            check(m, log)
            repeat(30) { step(m, r, log) }
        }
    }

    private fun step(m: DocumentModel, r: Random, log: StringBuilder) {
        val before = m.currentText()
        var keepsText = true
        var letterDropped = "" // leaving a list on an empty checked item drops its `[x]`
        var expectLineBelow: Int? = null
        val a = m.active
        when (r.nextInt(12)) {
            0, 1 -> if (m.blocks.isNotEmpty()) {
                val i = r.nextInt(m.blocks.size)
                val offset = r.nextInt(m.blocks[i].source.length + 1)
                log.append("\n tap block $i at $offset")
                m.activate(m.blocks[i].id, offset)
            }
            2 -> { log.append("\n tap below"); m.activateAtEnd() }
            3, 4 -> if (a != null) {
                val s = snippets.random(r)
                log.append("\n type ${s.show()}")
                a.field.edit { replace(selection.start, selection.end, s) }
                m.onTyped()
                keepsText = false
            }
            5, 6, 7 -> if (a != null) {
                val text = a.field.text.toString()
                val at = a.field.selection.start
                val action = enterAction(if (a.virtual) BlockKind.PARAGRAPH else a.kind, text, at)
                log.append("\n enter at $at in ${a.kind}${if (a.virtual) " (end)" else ""} ${text.show()}: $action")
                when (action) {
                    EnterAction.Split -> {
                        val last = a.index >= m.blocks.lastIndex
                        val plain = (a.kind == BlockKind.PARAGRAPH || a.kind == BlockKind.HEADING) && !a.virtual &&
                            text.matches(Regex("""#{0,2} ?[\p{L} ]*\p{L}""")) && text.substring(at).isBlank()
                        if (plain && !last) expectLineBelow = a.index + 1
                        m.splitActive(at)
                    }
                    is EnterAction.LeaveList -> {
                        letterDropped = text.substring(action.lineStart, action.lineEnd).filter { it.isLetter() }
                        m.leaveListActive(action.lineStart, action.lineEnd)
                    }
                    is EnterAction.Continue -> {
                        a.field.edit { replace(at, at, "\n" + action.prefix) }
                        m.onTyped()
                    }
                    EnterAction.Exit -> m.exitActive()
                    EnterAction.Newline -> {
                        a.field.edit { replace(at, at, "\n") }
                        m.onTyped()
                    }
                }
            }
            8 -> if (a != null) {
                val sel = a.field.selection
                if (sel.collapsed && sel.start == 0) {
                    log.append("\n backspace at the start of ${a.kind} ${a.field.text.toString().show()}")
                    m.joinWithPrevious()
                } else {
                    log.append("\n backspace in the text")
                    a.field.edit { if (sel.collapsed) replace(sel.start - 1, sel.start, "") else replace(sel.min, sel.max, "") }
                    m.onTyped()
                    keepsText = false
                }
            }
            9 -> { log.append("\n done"); m.commit() }
            10 -> {
                keepsText = false
                when (r.nextInt(4)) {
                    0 -> { log.append("\n undo"); m.undo() }
                    1 -> { log.append("\n redo"); m.redo() }
                    2 -> {
                        val i = m.blocks.indexOfFirst { it.source.contains("[ ]") || it.source.contains("[x]") }
                        if (i >= 0) {
                            log.append("\n toggle a checkbox in block $i")
                            m.toggleCheckbox(m.blocks[i].id, m.blocks[i].source.indexOf('['))
                        }
                    }
                    else -> { log.append("\n source mode and back"); m.commit(); m.setDoc(m.doc) }
                }
            }
            else -> if (a != null) {
                // Select a stretch of the field, as a long press would, then type over it.
                val len = a.field.text.length
                val s = r.nextInt(len + 1)
                val e = (s + r.nextInt(4)).coerceAtMost(len)
                log.append("\n replace $s..$e")
                a.field.edit { replace(s, e, "y") }
                m.onTyped()
                keepsText = false
            }
        }
        check(m, log)
        if (keepsText) assertEquals("lost text\n$log", letters(before).length - letterDropped.length, letters(m.currentText()).length)
        if (keepsText && letterDropped.isEmpty()) assertEquals("lost text\n$log", letters(before), letters(m.currentText()))
        expectLineBelow?.let {
            val now = m.active
            assertTrue("no empty line right below\n$log", now != null && now.kind == BlockKind.EMPTY && now.index == it)
        }
    }

    private fun check(m: DocumentModel, log: StringBuilder) {
        val parsed = BlockParser.parse(m.doc)
        if (parsed.size != m.blocks.size || parsed.indices.any { !parsed[it].sameAs(m.blocks[it]) }) {
            fail("blocks are not what the text means\n$log\n text ${m.doc.show()}\n blocks ${m.blocks}\n parsed $parsed")
        }
        val ids = m.blocks.map { it.id }
        assertTrue("ids not unique\n$log", ids.toSet().size == ids.size && BlockId.NONE !in ids)
        val a = m.active ?: return
        if (a.virtual) {
            assertEquals("new line not at the end\n$log", m.blocks.size, a.index)
        } else {
            val b = m.blocks.getOrNull(a.index)
            assertTrue("active block out of sync\n$log", b != null && b.id == a.id && b.kind == a.kind)
        }
    }

    private fun String.show() = "\"" + replace("\n", "\\n").replace("\t", "\\t") + "\""
}
