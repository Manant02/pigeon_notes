package ch.antoinette.pigeonnotes.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ch.antoinette.pigeonnotes.AppGraph
import ch.antoinette.pigeonnotes.ui.graphViewModel
import ch.antoinette.pigeonnotes.domain.NotesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppSettingsViewModel(private val graph: AppGraph) : ViewModel() {
    val tileView = graph.settings.tileView

    /** Every category, in the user's saved order. */
    val categories: Flow<List<String>> = combine(graph.notes.categories, graph.settings.categoryOrder) { all, order ->
        order.filter { it in all } + all.filter { it !in order }
    }

    /** Set when a category could not be deleted because files are still in its folder. */
    val notEmpty = MutableStateFlow<Pair<String, List<String>>?>(null)

    fun setTileView(enabled: Boolean) {
        viewModelScope.launch { graph.settings.setTileView(enabled) }
    }

    fun setCategoryOrder(order: List<String>) {
        viewModelScope.launch { graph.settings.setCategoryOrder(order) }
    }

    fun createCategory(name: String) {
        viewModelScope.launch { graph.notes.createCategory(name) }
    }

    fun renameCategory(path: String, newName: String) {
        viewModelScope.launch {
            val newPath = graph.notes.renameCategory(path, newName)
            // keep the manual chip order pointing at the renamed folder
            val order = graph.settings.categoryOrder.first()
            if (path in order) graph.settings.setCategoryOrder(order.map { if (it == path) newPath else it })
        }
    }

    fun deleteCategory(path: String) {
        viewModelScope.launch {
            when (val result = graph.notes.deleteCategory(path)) {
                is NotesRepository.CategoryDeletion.NotEmpty -> notEmpty.value = path to result.remaining
                NotesRepository.CategoryDeletion.Deleted -> {
                    val order = graph.settings.categoryOrder.first()
                    if (path in order) graph.settings.setCategoryOrder(order - path)
                }
            }
        }
    }

    fun dismissNotEmpty() { notEmpty.value = null }
}

/** App preferences (not the server connection, which lives in [SettingsScreen]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    onBack: () -> Unit,
    vm: AppSettingsViewModel = graphViewModel { AppSettingsViewModel(it) },
) {
    val tiles by vm.tileView.collectAsStateWithLifecycle(false)
    val categories by vm.categories.collectAsStateWithLifecycle(emptyList())
    val notEmpty by vm.notEmpty.collectAsStateWithLifecycle()
    var reordering by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Appearance",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp),
            )
            ListItem(
                headlineContent = { Text("Note layout") },
                supportingContent = { Text(if (tiles) "Tiles, like Keep" else "One note per row") },
                trailingContent = {
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            selected = !tiles,
                            onClick = { vm.setTileView(false) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            icon = {},
                            label = { Icon(Icons.Outlined.ViewList, "List") },
                        )
                        SegmentedButton(
                            selected = tiles,
                            onClick = { vm.setTileView(true) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            icon = {},
                            label = { Icon(Icons.Outlined.GridView, "Tiles") },
                        )
                    }
                },
            )
            ListItem(
                headlineContent = { Text("Category order") },
                supportingContent = {
                    Text(if (categories.isEmpty()) "No categories yet" else categories.joinToString(" · "))
                },
                modifier = Modifier.clickable(enabled = categories.isNotEmpty()) { reordering = true },
            )

            Text(
                "Categories",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp),
            )
            for (c in categories) {
                ListItem(
                    headlineContent = { Text(c) },
                    leadingContent = { Icon(Icons.Outlined.Folder, null) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { renaming = c }) { Icon(Icons.Outlined.Edit, "Rename $c") }
                            IconButton(onClick = { confirmDelete = c }) { Icon(Icons.Outlined.Delete, "Delete $c") }
                        }
                    },
                )
            }
            ListItem(
                headlineContent = { Text("New category") },
                leadingContent = { Icon(Icons.Outlined.CreateNewFolder, null) },
                modifier = Modifier.clickable { creating = true },
            )
        }
    }

    if (creating) {
        CategoryNameDialog(
            title = "New category",
            initial = "",
            confirm = "Create",
            taken = categories,
            onDismiss = { creating = false },
            onConfirm = { vm.createCategory(it); creating = false },
        )
    }

    renaming?.let { path ->
        CategoryNameDialog(
            title = "Rename category",
            initial = path.substringAfterLast('/'),
            confirm = "Rename",
            taken = categories - path,
            onDismiss = { renaming = null },
            onConfirm = { vm.renameCategory(path, it); renaming = null },
        )
    }

    confirmDelete?.let { path ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “$path”?") },
            text = { Text("The folder is removed here and on the server. Only empty categories can be deleted.") },
            confirmButton = { TextButton(onClick = { vm.deleteCategory(path); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    notEmpty?.let { (path, remaining) ->
        AlertDialog(
            onDismissRequest = vm::dismissNotEmpty,
            title = { Text("“$path” is not empty") },
            text = {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        if (remaining.size == 1) "One file is still in this folder. Move or delete it first:"
                        else "${remaining.size} files are still in this folder. Move or delete them first:",
                    )
                    Spacer(Modifier.height(8.dp))
                    for (f in remaining.take(20)) {
                        Text("• $f", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (remaining.size > 20) {
                        Text("…and ${remaining.size - 20} more", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissNotEmpty) { Text("OK") } },
        )
    }

    if (reordering) {
        ReorderCategoriesDialog(
            categories = categories,
            onDismiss = { reordering = false },
            onSave = { vm.setCategoryOrder(it); reordering = false },
        )
    }
}

/** Asks for a category name and refuses duplicates before anything is created. */
@Composable
private fun CategoryNameDialog(
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
                    Text("That category already exists", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank() && !clash) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReorderCategoriesDialog(categories: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var order by remember { mutableStateOf(categories) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Category order") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                order.forEachIndexed { i, c ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(c, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { order = order.swap(i, i - 1) }, enabled = i > 0) {
                            Icon(Icons.Default.KeyboardArrowUp, "Move up")
                        }
                        IconButton(onClick = { order = order.swap(i, i + 1) }, enabled = i < order.lastIndex) {
                            Icon(Icons.Default.KeyboardArrowDown, "Move down")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(order) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun <T> List<T>.swap(a: Int, b: Int): List<T> {
    if (a !in indices || b !in indices) return this
    return toMutableList().also { it[a] = this[b]; it[b] = this[a] }
}
