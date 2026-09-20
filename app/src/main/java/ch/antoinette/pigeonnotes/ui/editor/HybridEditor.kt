package ch.antoinette.pigeonnotes.ui.editor

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import ch.antoinette.pigeonnotes.domain.markdown.BlockKind

/**
 * The document as a list of rendered blocks with (at most) one editable block in place.
 * Tapping empty space below the content starts a new paragraph at the end.
 */
@Composable
fun HybridEditor(vm: EditorViewModel, palette: MarkdownPalette, onOpenLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val doc = vm.document
    val blocks = doc.blocks
    val active = doc.active
    val listState = rememberLazyListState()

    // Display list: the parsed blocks, plus a virtual paragraph after the last one while it is being typed.
    val count = blocks.size + (if (active?.virtual == true) 1 else 0)

    LaunchedEffect(active?.index, active?.virtual) {
        val a = active ?: return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == a.index }) listState.animateScrollToItem(a.index)
    }

    // Rendered blocks are selectable together (long-press and drag), so a passage spanning several
    // blocks can be copied; the block being edited keeps the text field's own selection.
    SelectionContainer(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { doc.activateAtEnd() } },
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 240.dp),
        ) {
            items(count = count, key = { i -> if (i < blocks.size) "${blocks[i].start}:${blocks[i].kind}" else "virtual" }) { i ->
                val a = active
                val block = blocks.getOrNull(i)
                val editing = a != null && a.index == i

                Box(Modifier.fillMaxWidth().padding(vertical = spacingFor(block?.kind))) {
                    if (editing && a != null) {
                        ActiveBlockField(
                            active = a,
                            palette = palette,
                            onTextChanged = vm::onTextChanged,
                            onSplit = doc::splitActive,
                            onLeaveList = doc::leaveListActive,
                            onBackspaceAtStart = doc::joinWithPrevious,
                        )
                    } else if (block != null) {
                        RenderedBlock(
                            block = block,
                            palette = palette,
                            resolveLink = vm::resolveLink,
                            onActivate = { offset -> doc.activate(i, offset) },
                            onToggleCheckbox = { offset -> doc.toggleCheckbox(i, offset) },
                            onOpenLink = onOpenLink,
                        )
                    }
                }
            }
            item(key = "tail") {
                // Extra tap target so a short note can still be appended to comfortably.
                Box(Modifier.fillMaxWidth().height(120.dp).pointerInput(Unit) { detectTapGestures { doc.activateAtEnd() } })
            }
        }
    }
}

private fun spacingFor(kind: BlockKind?): androidx.compose.ui.unit.Dp = when (kind) {
    BlockKind.HEADING -> 10.dp
    BlockKind.IMAGE, BlockKind.CODE, BlockKind.ALERT -> 8.dp
    BlockKind.HR, BlockKind.EMPTY -> 0.dp
    else -> 6.dp
}
