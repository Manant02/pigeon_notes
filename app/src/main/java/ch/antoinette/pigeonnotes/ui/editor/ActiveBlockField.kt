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
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
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
    onExitBlock: () -> Unit,
    onBackspaceAtStart: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val output = remember(active, palette) { MarkdownOutputTransformation(active.kind, active.level, palette) }
    val input = remember(active) { EnterTransformation(active.kind, onSplit, onLeaveList, onExitBlock) }

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
                    // Backspace with nothing before the cursor joins the line above (Typora). IMEs send
                    // a key event exactly then, since there is no text for deleteSurroundingText to eat.
                    val sel = active.field.selection
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace && sel.collapsed && sel.start == 0) {
                        onBackspaceAtStart(); true
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

        BlockKind.QUOTE, BlockKind.ALERT -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
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

/**
 * Styles the raw block and hides markers whose owner element does not contain the cursor. While text
 * is selected, every element the selection touches shows its syntax: hiding it would shrink the text
 * under the selection handles (an image collapsing to "🖼 alt" as soon as it is selected).
 */
class MarkdownOutputTransformation(
    private val kind: BlockKind,
    private val level: Int,
    private val palette: MarkdownPalette,
) : OutputTransformation {
    private var cached: Analysis? = null

    override fun TextFieldBuffer.transformOutput() {
        val source = asCharSequence().toString()
        val analysis = cached?.takeIf { it.source == source } ?: InlineStyler.analyze(source, kind, level).also { cached = it }
        val selStart = selection.min
        val selEnd = selection.max
        val projection = InlineStyler.project(analysis, selStart, cursorEnd = selEnd)
        // Apply replacements back to front so earlier offsets stay valid, then styles in output coordinates.
        for (m in analysis.markers.asReversed()) {
            if (m.ownerTouches(selStart, selEnd)) continue
            if (m.replacement.isEmpty()) delete(m.start, m.end) else replace(m.start, m.end, m.replacement)
        }
        val base = blockTextStyle(kind, level, palette)
        // The buffer moves the selection along with the edits above, so this is in output coordinates.
        val shownStart = selection.min.coerceIn(0, length)
        val shownEnd = selection.max.coerceIn(0, length)
        for (s in projection.styles) {
            val style = palette.spanStyle(s, base) ?: continue
            val a = s.start.coerceIn(0, length); val b = s.end.coerceIn(0, length)
            if (b <= a) continue
            if (!style.background.isSpecified || shownEnd <= shownStart) {
                addStyle(style, a, b); continue
            }
            // Span backgrounds (inline code, ==highlight==) are painted over the selection highlight,
            // so leave the selected part without one.
            addStyle(style.copy(background = Color.Unspecified), a, b)
            val bg = SpanStyle(background = style.background)
            if (a < shownStart) addStyle(bg, a, minOf(b, shownStart))
            if (b > shownEnd) addStyle(bg, maxOf(a, shownEnd), b)
        }
    }
}
