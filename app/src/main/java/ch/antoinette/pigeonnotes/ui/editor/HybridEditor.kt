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
    val blocks = vm.blocks
    val active = vm.active
    val listState = rememberLazyListState()

    // Display list: blocks, with a virtual active block inserted at its index.
    val count = blocks.size + (if (active?.virtual == true) 1 else 0)

    LaunchedEffect(active?.index, active?.virtual) {
        val a = active ?: return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == a.index }) listState.animateScrollToItem(a.index)
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { vm.activateAtEnd() } },
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 240.dp),
    ) {
        items(count = count, key = { displayIndex ->
            val a = active
            if (a != null && a.virtual && displayIndex == a.index) "virtual"
            else {
                val bi = if (a != null && a.virtual && displayIndex > a.index) displayIndex - 1 else displayIndex
                "$bi:${blocks[bi].start}"
            }
        }) { displayIndex ->
            val a = active
            val isVirtual = a != null && a.virtual && displayIndex == a.index
            val blockIndex = if (a != null && a.virtual && displayIndex > a.index) displayIndex - 1 else displayIndex
            val block = if (isVirtual) null else blocks[blockIndex]
            val editing = isVirtual || (a != null && !a.virtual && a.index == blockIndex)

            Box(Modifier.fillMaxWidth().padding(vertical = spacingFor(block?.kind))) {
                if (editing && a != null) {
                    ActiveBlockField(
                        active = a,
                        palette = palette,
                        onTextChanged = vm::onTextChanged,
                        onSplit = vm::splitActive,
                        onLeaveList = vm::leaveListActive,
                    )
                } else if (block != null) {
                    RenderedBlock(
                        block = block,
                        palette = palette,
                        resolveLink = vm::resolveLink,
                        onActivate = { offset -> vm.activate(blockIndex, offset) },
                        onOpenLink = onOpenLink,
                    )
                }
            }
        }
        item(key = "tail") {
            // Extra tap target so a short note can still be appended to comfortably.
            Box(Modifier.fillMaxWidth().height(120.dp).pointerInput(Unit) { detectTapGestures { vm.activateAtEnd() } })
        }
    }
}

private fun spacingFor(kind: BlockKind?): androidx.compose.ui.unit.Dp = when (kind) {
    BlockKind.HEADING -> 10.dp
    BlockKind.IMAGE, BlockKind.CODE -> 8.dp
    BlockKind.HR -> 0.dp
    else -> 6.dp
}
