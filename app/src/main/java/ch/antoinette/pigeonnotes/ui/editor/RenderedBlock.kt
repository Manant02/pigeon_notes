package ch.antoinette.pigeonnotes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
 * long-pressing a link opens it.
 */
@Composable
fun RenderedBlock(
    block: Block,
    palette: MarkdownPalette,
    resolveLink: (String) -> File?,
    onActivate: (sourceOffset: Int) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val analysis = remember(block.source, block.kind) { InlineStyler.analyze(block.source, block.kind, block.level) }
    val projection = remember(analysis) { InlineStyler.project(analysis) }

    when (block.kind) {
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

        BlockKind.CODE -> Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(palette.codeBackground)
                .padding(12.dp),
        ) {
            StyledText(block, projection, palette, onActivate, onOpenLink)
        }

        BlockKind.QUOTE -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .padding(end = 12.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
            StyledText(block, projection, palette, onActivate, onOpenLink)
        }

        else -> StyledText(block, projection, palette, onActivate, onOpenLink)
    }
}

@Composable
private fun StyledText(
    block: Block,
    projection: Projection,
    palette: MarkdownPalette,
    onActivate: (Int) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val annotated = remember(projection, palette) { palette.annotate(projection) }
    fun outputOffset(pos: Offset): Int = layout?.getOffsetForPosition(pos) ?: projection.text.length

    Text(
        text = annotated,
        style = blockTextStyle(block.kind, block.level, palette),
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(projection) {
                detectTapGestures(
                    onTap = { pos -> onActivate(projection.toSource(outputOffset(pos))) },
                    onLongPress = { pos ->
                        val off = outputOffset(pos)
                        val link = projection.links.firstOrNull { off in it.start..it.end }
                        if (link != null) onOpenLink(link.url) else onActivate(projection.toSource(off))
                    },
                )
            },
    )
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
