package ch.antoinette.pigeonnotes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.insert
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import ch.antoinette.pigeonnotes.domain.markdown.Analysis
import ch.antoinette.pigeonnotes.domain.markdown.BlockKind
import ch.antoinette.pigeonnotes.domain.markdown.InlineStyler

/**
 * The editable block: raw markdown in a text field whose *output* hides the syntax of every
 * inline element the cursor is not inside, styled exactly like the rendered block.
 */
@Composable
fun ActiveBlockField(
    active: ActiveBlock,
    palette: MarkdownPalette,
    onTextChanged: () -> Unit,
    onSplit: (cursor: Int) -> Unit,
    onLeaveList: (lineStart: Int, lineEnd: Int) -> Unit,
    onDeleteEmpty: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val output = remember(active, palette) { MarkdownOutputTransformation(active.kind, active.level, palette) }
    val input = remember(active) { EnterTransformation(active.kind, onSplit, onLeaveList) }

    LaunchedEffect(active) {
        focus.requestFocus()
        snapshotFlow { active.field.text.toString() }.collect { onTextChanged() }
    }

    val field: @Composable () -> Unit = {
        BasicTextField(
            state = active.field,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    // Backspace in an empty line removes the line (Typora); IMEs that only send
                    // deleteSurroundingText never reach here, which is harmless.
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace && active.field.text.isEmpty()) {
                        onDeleteEmpty(); true
                    } else false
                },
            textStyle = blockTextStyle(active.kind, active.level, palette),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            lineLimits = TextFieldLineLimits.MultiLine(),
            keyboardOptions = KeyboardOptions(
                capitalization = if (active.kind == BlockKind.CODE) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
                autoCorrectEnabled = active.kind != BlockKind.CODE,
            ),
            inputTransformation = input,
            outputTransformation = output,
        )
    }

    when (active.kind) {
        BlockKind.CODE, BlockKind.FRONTMATTER, BlockKind.TABLE, BlockKind.HTML, BlockKind.MATH -> Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(palette.codeBackground)
                .padding(12.dp),
        ) { field() }

        BlockKind.QUOTE -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .padding(end = 12.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
            field()
        }

        else -> field()
    }
}

/** Styles the raw block and hides markers whose owner element does not contain the cursor. */
class MarkdownOutputTransformation(
    private val kind: BlockKind,
    private val level: Int,
    private val palette: MarkdownPalette,
) : OutputTransformation {
    private var cached: Analysis? = null

    override fun TextFieldBuffer.transformOutput() {
        val source = asCharSequence().toString()
        val analysis = cached?.takeIf { it.source == source } ?: InlineStyler.analyze(source, kind, level).also { cached = it }
        val cursor = if (selection.collapsed) selection.start else null
        val projection = InlineStyler.project(analysis, cursor)
        // Apply replacements back to front so earlier offsets stay valid, then styles in output coordinates.
        for (m in analysis.markers.asReversed()) {
            if (cursor != null && m.ownerContains(cursor)) continue
            if (m.replacement.isEmpty()) delete(m.start, m.end) else replace(m.start, m.end, m.replacement)
        }
        for (s in projection.styles) {
            val style = palette.spanStyle(s) ?: continue
            val a = s.start.coerceIn(0, length); val b = s.end.coerceIn(0, length)
            if (b > a) addStyle(style, a, b)
        }
    }
}

/**
 * Enter key semantics: paragraphs and headings split into a new block; list items continue the
 * list (or leave it when the item is empty); quotes continue the quote; code keeps plain newlines.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
class EnterTransformation(
    private val kind: BlockKind,
    private val onSplit: (Int) -> Unit,
    private val onLeaveList: (Int, Int) -> Unit,
) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount != 1) return
        val range = changes.getRange(0)
        if (range.length != 1 || charAt(range.start) != '\n') return
        val newlineAt = range.start
        when (kind) {
            BlockKind.PARAGRAPH, BlockKind.HEADING, BlockKind.IMAGE, BlockKind.EMPTY -> {
                val cursor = changes.getOriginalRange(0).start
                revertAllChanges()
                onSplit(cursor)
            }

            BlockKind.LIST -> {
                val lineStart = asCharSequence().lastIndexOf('\n', newlineAt - 1) + 1
                val line = asCharSequence().substring(lineStart, newlineAt)
                val m = LIST_LINE.matchEntire(line)
                if (m == null) return
                val (indent, bullet, checkbox, content) = m.destructured
                if (content.isBlank()) {
                    revertAllChanges()
                    onLeaveList(lineStart, newlineAt)
                    return
                }
                val nextBullet = bullet.dropLast(1).toIntOrNull()?.let { "${it + 1}${bullet.last()}" } ?: bullet
                val prefix = indent + nextBullet + " " + (if (checkbox.isNotEmpty()) "[ ] " else "")
                insert(newlineAt + 1, prefix)
                placeCursorAfterCharAt(newlineAt + prefix.length)
            }

            BlockKind.QUOTE -> {
                insert(newlineAt + 1, "> ")
                placeCursorAfterCharAt(newlineAt + 2)
            }

            else -> Unit
        }
    }

    companion object {
        private val LIST_LINE = Regex("""^(\s*)([-*+]|\d+[.)])\s+(\[[ xX]\]\s+)?(.*)$""")
    }
}
