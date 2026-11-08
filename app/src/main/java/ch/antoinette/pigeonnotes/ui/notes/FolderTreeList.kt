package ch.antoinette.pigeonnotes.ui.notes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Left edge of a depth-0 line, and how far each level of the tree is indented further. */
private val TreeStart = 16.dp
private val IndentStep = 28.dp
private val FolderIconSize = 40.dp

private fun indentOf(depth: Int): Dp = TreeStart + IndentStep * depth

/**
 * A folder's contents as a tree: sub-folders first, each with a round button on the right that
 * opens it in place (its contents slide in below, indented, with a guide line down from the
 * folder's icon), then the notes. Tapping a folder itself enters it.
 */
@Composable
fun FolderTreeList(
    items: List<TreeItem>,
    accent: Color,
    accentContainer: Color,
    picked: Set<String>,
    onOpenNote: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onToggleFolder: (String) -> Unit,
    onFolderOptions: (String) -> Unit,
    onToggleNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
) {
    val selecting = picked.isNotEmpty()
    val guide = accent.copy(alpha = 0.35f)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        items(items, key = { it.key }, contentType = { it::class }) { item ->
            Column(Modifier.animateItem().treeGuides(item.depth, guide)) {
                when (item) {
                    is TreeItem.Folder -> TreeFolderRow(
                        item, accent, accentContainer,
                        // While picking notes, entering a folder would drop the selection: open it in place instead.
                        onClick = { if (selecting) onToggleFolder(item.path) else onOpenFolder(item.path) },
                        onLongClick = { if (!selecting) onFolderOptions(item.path) },
                        onToggle = { onToggleFolder(item.path) },
                    )
                    is TreeItem.Note -> TreeNoteRow(
                        item,
                        selected = item.row.path in picked,
                        selecting = selecting,
                        onClick = { if (selecting) onToggleNote(item.row.path) else onOpenNote(item.row.path) },
                        onLongClick = { onToggleNote(item.row.path) },
                        onDelete = { onDeleteNote(item.row.path) },
                    )
                    is TreeItem.Empty -> Text(
                        "Empty folder",
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = indentOf(item.depth), top = 10.dp, bottom = 10.dp),
                    )
                }
                HorizontalDivider(
                    Modifier.padding(start = indentOf(item.depth)),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/**
 * One vertical line per enclosing open folder, running down from the centre of that folder's
 * icon, so the contents of an expanded folder read as hanging from it.
 */
private fun Modifier.treeGuides(depth: Int, color: Color): Modifier =
    if (depth == 0) this else drawBehind {
        val stroke = 2.dp.toPx()
        for (level in 0 until depth) {
            val x = (indentOf(level) + FolderIconSize / 2).toPx()
            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
        }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeFolderRow(
    item: TreeItem.Folder,
    accent: Color,
    accentContainer: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: () -> Unit,
) {
    val turn by animateFloatAsState(if (item.expanded) 180f else 0f, label = "expand")
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = indentOf(item.depth), end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(FolderIconSize).clip(RoundedCornerShape(12.dp)).background(accentContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (item.expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder, null, tint = accent)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                folderSummary(item.noteCount, item.folderCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        // The in-place toggle: big and round so it is easy to hit apart from the row itself.
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (item.expanded) accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.ExpandMore,
                if (item.expanded) "Close ${item.name}" else "Show ${item.name} here",
                Modifier.rotate(turn),
                tint = if (item.expanded) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun folderSummary(notes: Int, folders: Int): String {
    val parts = buildList {
        if (folders > 0) add(if (folders == 1) "1 folder" else "$folders folders")
        if (notes > 0) add(if (notes == 1) "1 note" else "$notes notes")
    }
    return if (parts.isEmpty()) "Empty" else parts.joinToString(" · ")
}

/** A note: its title (the H1) on top, the file name below it, then the start of its text. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeNoteRow(
    item: TreeItem.Note,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val row = item.row
    SwipeToDelete(enabled = !selecting, onDelete = onDelete) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(start = indentOf(item.depth), end = 16.dp, top = 12.dp, bottom = 12.dp),
        ) {
            if (selecting) {
                Icon(
                    if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                    if (selected) "Selected" else "Not selected",
                    Modifier.padding(top = 2.dp, end = 12.dp),
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Description, null, Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        item.fileName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                if (row.preview.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        row.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    relativeTime(row.modified),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusDot(row)
            }
        }
    }
}

/**
 * Where the folder on screen is: `⌂ Pages › Recipes › Asian`. Every folder above it can be
 * tapped to go back up; the first goes to the home screen. Scrolled to the end when it is long,
 * so the current folder is always in view.
 */
@Composable
fun Breadcrumbs(
    crumbs: List<Pair<String, String>>,
    accent: Color,
    onCrumb: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(crumbs, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { i, (name, path) ->
            val last = i == crumbs.lastIndex
            if (i > 0) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
            Row(
                Modifier
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !last) { onCrumb(path) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (i == 0) {
                    Icon(Icons.Outlined.Home, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (last) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** What can be done with a sub-folder: long-press on its row. */
@Composable
fun FolderOptionsDialog(name: String, onDismiss: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text("Rename") },
                    leadingContent = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                    modifier = Modifier.clickable(onClick = onRename),
                )
                ListItem(
                    headlineContent = { Text("Delete") },
                    supportingContent = { Text("Only an empty folder can be deleted") },
                    leadingContent = { Icon(Icons.Outlined.Delete, null) },
                    modifier = Modifier.clickable(onClick = onDelete),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Explains why a folder was not deleted and lists what is still in it. */
@Composable
fun FolderNotEmptyDialog(name: String, remaining: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("“$name” is not empty") },
        text = {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                Text(
                    if (remaining.size == 1) "One file is still in this folder. Move or delete it first:"
                    else "${remaining.size} files are still in this folder. Move or delete them first:",
                )
                Spacer(Modifier.height(8.dp))
                for (f in remaining.take(20)) {
                    Text(f, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (remaining.size > 20) {
                    Text("… and ${remaining.size - 20} more", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
