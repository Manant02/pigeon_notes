package ch.antoinette.pigeonnotes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import ch.antoinette.pigeonnotes.domain.markdown.Analysis
import ch.antoinette.pigeonnotes.domain.markdown.Block
import ch.antoinette.pigeonnotes.domain.markdown.BlockKind
import ch.antoinette.pigeonnotes.domain.markdown.InlineStyler
import ch.antoinette.pigeonnotes.domain.markdown.Projection
import coil3.compose.AsyncImage
import java.io.File

/**
 * A block in reading mode. Tapping anywhere activates it with the cursor at the tapped character;
 * tapping a checkbox toggles it; long-pressing a link opens it.
 */
@Composable
fun RenderedBlock(
    block: Block,
    palette: MarkdownPalette,
    resolveLink: (String) -> File?,
    onActivate: (sourceOffset: Int) -> Unit,
    onToggleCheckbox: (sourceOffset: Int) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val analysis = remember(block.source, block.kind) { InlineStyler.analyze(block.source, block.kind, block.level) }
    val projection = remember(analysis) { InlineStyler.project(analysis, renderMath = true) }

    when (block.kind) {
        BlockKind.EMPTY -> {
            // One empty line, same height as a body line, so whitespace looks like it does in the file.
            val lineHeight = with(LocalDensity.current) { blockTextStyle(BlockKind.PARAGRAPH, 0, palette).lineHeight.toDp() }
            Box(Modifier.fillMaxWidth().height(lineHeight).pointerInput(Unit) { detectTapGestures { onActivate(0) } })
        }

        BlockKind.HR -> HorizontalDivider(
            Modifier.padding(vertical = 12.dp).pointerInput(Unit) { detectTapGestures { onActivate(0) } },
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        BlockKind.FRONTMATTER -> AssistChip(
            onClick = { onActivate(4) },
            label = { Text("Properties") },
            modifier = Modifier.padding(bottom = 8.dp),
        )

        BlockKind.IMAGE -> ImageBlock(block, analysis, resolveLink, onActivate)

        BlockKind.MATH -> DisplayMath(
            block.source,
            Modifier.pointerInput(Unit) { detectTapGestures { onActivate(block.source.length) } },
        )

        BlockKind.CODE -> Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(palette.codeBackground)
                .padding(12.dp),
        ) {
            StyledText(block, projection, palette, onActivate, onToggleCheckbox, onOpenLink)
        }

        BlockKind.LIST -> ListBlockText(block, projection, palette, onActivate, onToggleCheckbox, onOpenLink)

        BlockKind.ALERT -> AlertBlock(block, projection, palette, onActivate, onToggleCheckbox, onOpenLink)

        BlockKind.QUOTE -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .padding(end = 12.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
            StyledText(block, projection, palette, onActivate, onToggleCheckbox, onOpenLink)
        }

        else -> StyledText(block, projection, palette, onActivate, onToggleCheckbox, onOpenLink)
    }
}

/** A GitHub-style alert: accent bar, icon and title, then the quoted body. */
@Composable
private fun AlertBlock(
    block: Block,
    projection: Projection,
    palette: MarkdownPalette,
    onActivate: (Int) -> Unit,
    onToggleCheckbox: (Int) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val kind = remember(block.source) { AlertKind.of(block.source) }
    val accent = kind.accent()
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .padding(end = 12.dp)
                .width(3.dp)
                .fillMaxHeight()
                .background(accent, RoundedCornerShape(2.dp)),
        )
        Column(Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.pointerInput(Unit) { detectTapGestures { onActivate(0) } },
            ) {
                Icon(kind.icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(kind.label, style = MaterialTheme.typography.labelLarge, color = accent)
            }
            Spacer(Modifier.height(2.dp))
            StyledText(block, projection, palette, onActivate, onToggleCheckbox, onOpenLink)
        }
    }
}

/**
 * A list block: one Text per item, so the spacing between items ([ListItemGap]) can be larger than
 * the line spacing inside an item, and the hanging indent applies to the item as a whole.
 */
@Composable
private fun ListBlockText(
    block: Block,
    projection: Projection,
    palette: MarkdownPalette,
    onActivate: (Int) -> Unit,
    onToggleCheckbox: (Int) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val style = blockTextStyle(block.kind, block.level, palette)
    val items = remember(projection, palette, style) { palette.annotateListItems(projection, style) }
    if (items.isEmpty()) {
        StyledText(block, projection, palette, onActivate, onToggleCheckbox, onOpenLink)
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ListItemGap)) {
        for (item in items) {
            key(item.outputStart) {
                var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                fun outputOffset(pos: Offset): Int =
                    item.outputStart + (layout?.getOffsetForPosition(pos) ?: 0)
                Text(
                    text = item.text,
                    inlineContent = item.inlineContent,
                    style = style,
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(item) {
                            // Long press is left to the surrounding SelectionContainer.
                            detectTapGestures { pos -> tapAt(outputOffset(pos), projection, onActivate, onToggleCheckbox, onOpenLink) }
                        },
                )
            }
        }
    }
}

@Composable
private fun StyledText(
    block: Block,
    projection: Projection,
    palette: MarkdownPalette,
    onActivate: (Int) -> Unit,
    onToggleCheckbox: (Int) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val style = blockTextStyle(block.kind, block.level, palette)
    val annotated = remember(projection, palette, style) { palette.annotate(projection, style) }
    fun outputOffset(pos: Offset): Int =
        layout?.let { annotated.toOutput(it.getOffsetForPosition(pos)) } ?: projection.text.length

    Text(
        text = annotated.text,
        inlineContent = annotated.inlineContent,
        style = style,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(projection) {
                detectTapGestures { pos -> tapAt(outputOffset(pos), projection, onActivate, onToggleCheckbox, onOpenLink) }
            },
    )
}

/** A tap on a checkbox toggles it, on a link opens it, anywhere else starts editing at that spot. */
private fun tapAt(
    off: Int,
    projection: Projection,
    onActivate: (Int) -> Unit,
    onToggleCheckbox: (Int) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    projection.checkboxes.firstOrNull { off in it.start..it.end }?.let { onToggleCheckbox(it.sourceStart); return }
    projection.links.firstOrNull { off in it.start until it.end }?.let { onOpenLink(it.url); return }
    onActivate(projection.toSource(off))
}

@Composable
private fun ImageBlock(block: Block, analysis: Analysis, resolveLink: (String) -> File?, onActivate: (Int) -> Unit) {
    val url = analysis.links.firstOrNull()?.url
    val file = remember(url) { url?.let(resolveLink) }
    val alt = block.source.substringAfter("![", "").substringBefore("]")
    Column(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures { onActivate(block.source.length) } },
    ) {
        if (file != null && file.exists()) {
            AsyncImage(
                model = file,
                contentDescription = alt,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(16.dp),
            ) {
                Text(
                    if (url != null) "🖼 $alt\n$url" else block.source,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
