package ch.antoinette.pigeonnotes.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.insert
import ch.antoinette.pigeonnotes.domain.markdown.BlockKind
import ch.antoinette.pigeonnotes.domain.markdown.BlockParser

/** What Enter does in the active block. */
sealed interface EnterAction {
    /** Split the block at the cursor ([DocumentModel.splitActive]). */
    data object Split : EnterAction

    /** Enter on an empty list item, the line [lineStart, lineEnd): leave the list ([DocumentModel.leaveListActive]). */
    data class LeaveList(val lineStart: Int, val lineEnd: Int) : EnterAction

    /** A newline followed by [prefix], continuing a list item or a quote. */
    data class Continue(val prefix: String) : EnterAction

    /** Continue below the block, which stays as it is ([DocumentModel.exitActive]). */
    data object Exit : EnterAction

    /** Just a newline inside the block. */
    data object Newline : EnterAction
}

/**
 * Enter key semantics, for Enter pressed at [at] in [text] (the block source without the new
 * newline): paragraphs and headings split into a new block; list items continue the list (or leave
 * it when the item is empty); quotes continue the quote; code keeps plain newlines, except after the
 * closing fence (or a formula's closing `$$`), where Enter continues below the block.
 */
fun enterAction(kind: BlockKind, text: String, at: Int): EnterAction = when (kind) {
    BlockKind.PARAGRAPH, BlockKind.HEADING, BlockKind.IMAGE, BlockKind.EMPTY -> EnterAction.Split

    BlockKind.LIST -> {
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        val m = LIST_LINE.matchEntire(text.substring(lineStart, at))
        if (m == null) {
            EnterAction.Newline
        } else {
            val (indent, bullet, checkbox, content) = m.destructured
            if (content.isBlank()) {
                EnterAction.LeaveList(lineStart, at)
            } else {
                val nextBullet = bullet.dropLast(1).toIntOrNull()?.let { "${it + 1}${bullet.last()}" } ?: bullet
                EnterAction.Continue(indent + nextBullet + " " + (if (checkbox.isNotEmpty()) "[ ] " else ""))
            }
        }
    }

    BlockKind.QUOTE, BlockKind.ALERT -> EnterAction.Continue("> ")

    BlockKind.CODE, BlockKind.MATH ->
        if (at == text.length && (kind == BlockKind.MATH || BlockParser.isClosedFence(text))) EnterAction.Exit else EnterAction.Newline

    else -> EnterAction.Newline
}

private val LIST_LINE = Regex("""^(\s*)([-*+]|\d+[.)])\s+(\[[ xX]\]\s+)?(.*)$""")

/** Applies [enterAction] to a newline typed into the active block's field. */
@OptIn(ExperimentalFoundationApi::class)
class EnterTransformation(
    private val kind: BlockKind,
    private val onSplit: (Int) -> Unit,
    private val onLeaveList: (Int, Int) -> Unit,
    private val onExitBlock: () -> Unit,
) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        val range = changes.getRange(0)
        if (range.length != 1 || charAt(range.start) != '\n') return
        val newlineAt = range.start
        val cursor = changes.getOriginalRange(0).start
        val text = asCharSequence().removeRange(newlineAt, newlineAt + 1).toString()
        when (val action = enterAction(kind, text, newlineAt)) {
            EnterAction.Split -> {
                revertAllChanges()
                onSplit(cursor)
            }
            is EnterAction.LeaveList -> {
                revertAllChanges()
                onLeaveList(action.lineStart, action.lineEnd)
            }
            is EnterAction.Continue -> {
                insert(newlineAt + 1, action.prefix)
                placeCursorAfterCharAt(newlineAt + action.prefix.length)
            }
            EnterAction.Exit -> {
                revertAllChanges()
                onExitBlock()
            }
            EnterAction.Newline -> Unit
        }
    }
}
