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
import ch.antoinette.pigeonnotes.ui.notes.ARCHIVE_DIR
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AppSettingsViewModel(private val graph: AppGraph) : ViewModel() {
    val tileView = graph.settings.tileView

    /** Category folders that actually hold notes, in the user's saved order. */
    val categories: Flow<List<String>> = combine(graph.notes.notes, graph.settings.categoryOrder) { notes, order ->
        val present = notes.map { it.parent }.filter { it.isNotEmpty() && !it.startsWith(ARCHIVE_DIR) }.distinct()
        order.filter { it in present } + present.filter { it !in order }.sortedBy { it.lowercase() }
    }

    fun setTileView(enabled: Boolean) {
        viewModelScope.launch { graph.settings.setTileView(enabled) }
    }

    fun setCategoryOrder(order: List<String>) {
        viewModelScope.launch { graph.settings.setCategoryOrder(order) }
    }
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
    var reordering by remember { mutableStateOf(false) }

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
        }
    }

    if (reordering) {
        ReorderCategoriesDialog(
            categories = categories,
            onDismiss = { reordering = false },
            onSave = { vm.setCategoryOrder(it); reordering = false },
        )
    }
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
