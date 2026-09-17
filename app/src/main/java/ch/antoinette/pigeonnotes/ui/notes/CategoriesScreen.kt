package ch.antoinette.pigeonnotes.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.antoinette.pigeonnotes.AppGraph
import ch.antoinette.pigeonnotes.ui.graphViewModel
import kotlinx.coroutines.flow.combine

/** One row of the categories list. */
data class CategoryRowState(val name: String, val accent: CategoryAccent, val count: Int, val pinned: Boolean)

class CategoriesViewModel(graph: AppGraph) : ViewModel() {
    val rows = combine(
        graph.notes.notes,
        graph.notes.categories,
        graph.settings.categoryOrder,
        graph.settings.pinnedCategories,
        graph.settings.categoryColors,
    ) { notes, categories, order, pinned, colors ->
        val ordered = order.filter { it in categories } + categories.filter { it !in order }
        ordered.map { c ->
            CategoryRowState(c, CategoryAccent.of(colors[c]), notes.count { it.parent == c }, c in pinned)
        }
    }

    val uncategorized = combine(graph.notes.notes) { (notes) -> notes.count { it.parent.isEmpty() } }
    val archived = combine(graph.notes.notes) { (notes) ->
        notes.count { it.parent == ARCHIVE_DIR || it.parent.startsWith("$ARCHIVE_DIR/") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    onOpenCategory: (Category) -> Unit,
    vm: CategoriesViewModel = graphViewModel { CategoriesViewModel(it) },
) {
    val rows by vm.rows.collectAsStateWithLifecycle(emptyList())
    val uncategorized by vm.uncategorized.collectAsStateWithLifecycle(0)
    val archived by vm.archived.collectAsStateWithLifecycle(0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categories") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item("root") {
                ListItem(
                    headlineContent = { Text("Uncategorized") },
                    supportingContent = { Text(countLabel(uncategorized)) },
                    leadingContent = { Icon(Icons.Outlined.FolderOpen, null) },
                    modifier = Modifier.clickable { onOpenCategory("") },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            items(rows, key = { it.name }) { row ->
                val accent = row.accent.color()
                ListItem(
                    headlineContent = { Text(row.name) },
                    supportingContent = { Text(countLabel(row.count)) },
                    leadingContent = { Icon(Icons.Outlined.Folder, null, tint = accent) },
                    trailingContent = {
                        if (row.pinned) Icon(Icons.Outlined.PushPin, "Pinned", Modifier.size(18.dp), tint = accent)
                    },
                    modifier = Modifier.clickable { onOpenCategory(row.name) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item("archive") {
                ListItem(
                    headlineContent = { Text("Archive") },
                    supportingContent = { Text(countLabel(archived)) },
                    leadingContent = { Icon(Icons.Outlined.Archive, null) },
                    modifier = Modifier.clickable { onOpenCategory(ARCHIVE_DIR) },
                )
            }
        }
    }
}

private fun countLabel(n: Int) = if (n == 1) "1 note" else "$n notes"
