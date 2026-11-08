package ch.antoinette.pigeonnotes.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.LabelOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ch.antoinette.pigeonnotes.domain.NotesRepository

/** The folder a tag choice maps to: `Tags/` for "no tag", `Tags/<tag>` otherwise. */
fun tagFolder(tag: String?): String =
    if (tag.isNullOrEmpty()) NotesRepository.TAGS_DIR else NotesRepository.TAGS_DIR + "/" + tag

/**
 * The folder an add-to-page choice maps to: `Pages/` for a standalone page, `Pages/<folder>`
 * otherwise (`<folder>` may be nested, e.g. `Recipes/Asian`).
 */
fun pageFolder(folder: String?): String =
    if (folder.isNullOrEmpty()) NotesRepository.PAGES_DIR else NotesRepository.PAGES_DIR + "/" + folder

/**
 * Tags a note: "No tag" keeps it in `Tags/` for longer-term significance without a category.
 * [current] is the note's tag now (`""` = untagged, null = not under Tags at all).
 */
@Composable
fun TagPickerDialog(
    title: String,
    tags: List<String>,
    current: String?,
    onDismiss: () -> Unit,
    onPick: (tag: String) -> Unit,
    onCreate: (name: String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameDialog(
            title = "New tag",
            initial = "",
            confirm = "Create",
            taken = tags,
            onDismiss = { creating = false },
            onConfirm = { onCreate(it); creating = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                PickRow("No tag", Icons.Outlined.LabelOff, selected = current == "") { onPick("") }
                for (t in tags) {
                    PickRow(t, Icons.Outlined.Label, selected = current == t) { onPick(t) }
                }
                PickRow("New tag…", Icons.Default.Add, selected = false) { creating = true }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Adds a note to a page: into one of the page folders (nested ones shown indented below their
 * parent), or as a standalone page in `Pages/`. [folders] are relative to `Pages/`, parents
 * first; [current] is the note's page folder now (`""` = standalone page, null = not under Pages).
 */
@Composable
fun PagePickerDialog(
    title: String,
    folders: List<String>,
    current: String?,
    onDismiss: () -> Unit,
    onPick: (folder: String) -> Unit,
    onCreate: (name: String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameDialog(
            title = "New page folder",
            initial = "",
            confirm = "Create",
            taken = folders.filter { '/' !in it },
            onDismiss = { creating = false },
            onConfirm = { onCreate(it); creating = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                PickRow("Standalone page", Icons.Outlined.Article, selected = current == "") { onPick("") }
                for (f in folders) {
                    PickRow(f.substringAfterLast('/'), Icons.Outlined.Folder, selected = current == f, depth = f.count { it == '/' }) { onPick(f) }
                }
                PickRow("New page folder…", Icons.Default.Add, selected = false) { creating = true }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One choice in a picker; [depth] indents a folder nested in the one above it. */
@Composable
private fun PickRow(label: String, icon: ImageVector, selected: Boolean, depth: Int = 0, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null, Modifier.padding(start = 20.dp * depth)) },
        colors = if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else ListItemDefaults.colors(),
        modifier = Modifier.clickable(enabled = !selected, onClick = onClick),
    )
}

/** Asks for a folder name and refuses duplicates before anything is created. */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    taken: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val clash = name.isNotBlank() && taken.any { it.equals(name.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    isError = clash,
                )
                if (clash) {
                    Text("That name is already taken", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank() && !clash) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Confirms deleting [count] notes. */
@Composable
fun ConfirmDeleteDialog(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Delete 1 note?" else "Delete $count notes?") },
        text = { Text("The notes and their assets folders are removed here and on the server at the next sync.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
