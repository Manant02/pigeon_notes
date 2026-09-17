package ch.antoinette.pigeonnotes.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Picks the folder a note should live in. The root folder is offered as "No category", so a note
 * can always be moved back out of one.
 */
@Composable
fun CategoryPickerDialog(
    title: String,
    categories: List<String>,
    current: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                CategoryRow("No category", Icons.Outlined.FolderOpen, selected = current == "") { onPick("") }
                for (c in categories) {
                    CategoryRow(c, Icons.Outlined.Folder, selected = current == c) { onPick(c) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CategoryRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        colors = if (selected) ListItemDefaults.colors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer)
        else ListItemDefaults.colors(),
        modifier = Modifier.clickable(enabled = !selected, onClick = onClick),
    )
}
