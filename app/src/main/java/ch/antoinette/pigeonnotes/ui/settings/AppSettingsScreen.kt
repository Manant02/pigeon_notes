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
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.NewLabel
import androidx.compose.ui.draw.clip
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
import ch.antoinette.pigeonnotes.data.local.Settings
import ch.antoinette.pigeonnotes.ui.graphViewModel
import ch.antoinette.pigeonnotes.domain.NotesRepository
import ch.antoinette.pigeonnotes.domain.NoteText
import ch.antoinette.pigeonnotes.domain.Page
import ch.antoinette.pigeonnotes.ui.notes.CategoryAccent
import ch.antoinette.pigeonnotes.ui.notes.NameDialog
import ch.antoinette.pigeonnotes.ui.notes.color
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AppSettingsViewModel(private val graph: AppGraph) : ViewModel() {
    val tileView = graph.settings.tileView
    val pageRows = graph.settings.pageRows

    /** Every page, in the user's saved order. */
    val pages: Flow<List<Page>> = combine(graph.notes.structure, graph.settings.pageOrder) { s, order ->
        order.mapNotNull { p -> s.pages.firstOrNull { it.path == p } } + s.pages.filter { it.path !in order }
    }

    val tags: Flow<List<String>> = graph.notes.structure.map { it.tags }
    val colors: Flow<Map<String, String>> = graph.settings.pageColors

    /** Set when a folder could not be deleted because files are still in it. */
    val notEmpty = MutableStateFlow<Pair<String, List<String>>?>(null)

    fun setTileView(enabled: Boolean) {
        viewModelScope.launch { graph.settings.setTileView(enabled) }
    }

    fun setPageRows(rows: Int) {
        viewModelScope.launch { graph.settings.setPageRows(rows) }
    }

    fun setPageOrder(order: List<String>) {
        viewModelScope.launch { graph.settings.setPageOrder(order) }
    }

    fun createPageFolder(name: String) {
        viewModelScope.launch { graph.notes.createFolder(NotesRepository.PAGES_DIR, name) }
    }

    fun createTag(name: String) {
        viewModelScope.launch { graph.notes.createFolder(NotesRepository.TAGS_DIR, name) }
    }

    /** Renames a page (folder or single note) or a tag; order and colour follow it. */
    fun rename(page: Page?, path: String, newName: String) {
        viewModelScope.launch {
            val newPath = if (page is Page.Single) graph.notes.rename(path, newName) else graph.notes.renameFolder(path, newName)
            if (newPath != path) graph.settings.renamePageRefs(path, newPath)
        }
    }

    fun setColor(page: String, accent: CategoryAccent) {
        viewModelScope.launch { graph.settings.setPageColor(page, accent.name) }
    }

    fun deleteFolder(path: String) {
        viewModelScope.launch {
            when (val result = graph.notes.deleteFolder(path)) {
                is NotesRepository.FolderDeletion.NotEmpty -> notEmpty.value = path to result.remaining
                NotesRepository.FolderDeletion.Deleted -> graph.settings.renamePageRefs(path, null)
            }
        }
    }

    fun dismissNotEmpty() { notEmpty.value = null }
}

/** App preferences (not the server connection, which lives in [SettingsScreen]). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppSettingsScreen(
    onBack: () -> Unit,
    vm: AppSettingsViewModel = graphViewModel { AppSettingsViewModel(it) },
) {
    val tiles by vm.tileView.collectAsStateWithLifecycle(false)
    val pageRows by vm.pageRows.collectAsStateWithLifecycle(Settings.DEFAULT_PAGE_ROWS)
    val pages by vm.pages.collectAsStateWithLifecycle(emptyList())
    val tags by vm.tags.collectAsStateWithLifecycle(emptyList())
    val colors by vm.colors.collectAsStateWithLifecycle(emptyMap())
    val notEmpty by vm.notEmpty.collectAsStateWithLifecycle()
    var reordering by remember { mutableStateOf(false) }
    var creatingPage by remember { mutableStateOf(false) }
    var creatingTag by remember { mutableStateOf(false) }
    var editingPage by remember { mutableStateOf<Page?>(null) }
    var editingTag by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Pair<Page?, String>?>(null) } // (page or null for a tag, path)
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
            SectionTitle("Appearance")
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
                headlineContent = { Text("Page rows on home") },
                supportingContent = { Text(if (pageRows == 1) "One row of page tiles" else "$pageRows rows of page tiles") },
                trailingContent = {
                    SingleChoiceSegmentedButtonRow {
                        Settings.PAGE_ROW_OPTIONS.forEachIndexed { i, n ->
                            SegmentedButton(
                                selected = pageRows == n,
                                onClick = { vm.setPageRows(n) },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = Settings.PAGE_ROW_OPTIONS.size),
                                icon = {},
                                label = { Text("$n") },
                            )
                        }
                    }
                },
            )

            SectionTitle("Pages")
            ListItem(
                headlineContent = { Text("Page order") },
                supportingContent = {
                    Text(if (pages.isEmpty()) "No pages yet" else pages.joinToString(" · ") { it.name })
                },
                modifier = Modifier.clickable(enabled = pages.isNotEmpty()) { reordering = true },
            )
            for (page in pages) {
                val accent = CategoryAccent.of(colors[page.path])
                ListItem(
                    headlineContent = { Text(page.name) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                if (page is Page.Folder) "Folder" else "Single page",
                                accent.label.takeIf { accent != CategoryAccent.DEFAULT },
                            ).joinToString(" · ")
                        )
                    },
                    leadingContent = {
                        Icon(if (page is Page.Folder) Icons.Outlined.Folder else Icons.Outlined.Article, null, tint = accent.color())
                    },
                    modifier = Modifier.clickable { editingPage = page },
                )
            }
            ListItem(
                headlineContent = { Text("New page folder") },
                leadingContent = { Icon(Icons.Outlined.CreateNewFolder, null) },
                modifier = Modifier.clickable { creatingPage = true },
            )

            SectionTitle("Tags")
            for (tag in tags) {
                ListItem(
                    headlineContent = { Text(tag) },
                    leadingContent = { Icon(Icons.Outlined.Label, null) },
                    modifier = Modifier.clickable { editingTag = tag },
                )
            }
            ListItem(
                headlineContent = { Text("New tag") },
                leadingContent = { Icon(Icons.Outlined.NewLabel, null) },
                modifier = Modifier.clickable { creatingTag = true },
            )
        }
    }

    editingPage?.let { page ->
        PageDetailDialog(
            page = page,
            accent = CategoryAccent.of(colors[page.path]),
            onAccent = { vm.setColor(page.path, it) },
            onRename = { editingPage = null; renaming = page to page.path },
            onDelete = if (page is Page.Folder) ({ editingPage = null; confirmDelete = page.path }) else null,
            onDismiss = { editingPage = null },
        )
    }

    editingTag?.let { tag ->
        val path = NotesRepository.TAGS_DIR + "/" + tag
        AlertDialog(
            onDismissRequest = { editingTag = null },
            title = { Text(tag) },
            text = {
                Row {
                    TextButton(onClick = { editingTag = null; renaming = null to path }) { Text("Rename") }
                    TextButton(onClick = { editingTag = null; confirmDelete = path }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { editingTag = null }) { Text("Done") } },
        )
    }

    if (creatingPage) {
        NameDialog(
            title = "New page folder", initial = "", confirm = "Create", taken = pages.map { NoteText.title(it.path) },
            onDismiss = { creatingPage = false },
            onConfirm = { vm.createPageFolder(it); creatingPage = false },
        )
    }
    if (creatingTag) {
        NameDialog(
            title = "New tag", initial = "", confirm = "Create", taken = tags,
            onDismiss = { creatingTag = false },
            onConfirm = { vm.createTag(it); creatingTag = false },
        )
    }

    renaming?.let { (page, path) ->
        val name = NoteText.title(path)
        NameDialog(
            title = if (page == null) "Rename tag" else "Rename page",
            initial = name,
            confirm = "Rename",
            taken = (if (page == null) tags else pages.map { NoteText.title(it.path) }) - name,
            onDismiss = { renaming = null },
            onConfirm = { vm.rename(page, path, it); renaming = null },
        )
    }

    confirmDelete?.let { path ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${NoteText.title(path)}”?") },
            text = { Text("The folder is removed here and on the server. Only empty folders can be deleted.") },
            confirmButton = { TextButton(onClick = { vm.deleteFolder(path); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    notEmpty?.let { (path, remaining) ->
        AlertDialog(
            onDismissRequest = vm::dismissNotEmpty,
            title = { Text("“${NoteText.title(path)}” is not empty") },
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
        ReorderDialog(
            items = pages.map { it.path to it.name },
            onDismiss = { reordering = false },
            onSave = { vm.setPageOrder(it); reordering = false },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp),
    )
}

/** Colour, rename and (for folders) delete for one page. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PageDetailDialog(
    page: Page,
    accent: CategoryAccent,
    onAccent: (CategoryAccent) -> Unit,
    onRename: () -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(page.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (option in CategoryAccent.entries) {
                        val color = option.color()
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (option == accent) 3.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape,
                                )
                                .clickable { onAccent(option) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (option == accent) {
                                Icon(Icons.Outlined.Check, option.label, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row {
                    TextButton(onClick = onRename) { Text("Rename") }
                    if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Up/down reordering of (key, label) pairs; [onSave] gets the keys in the new order. */
@Composable
private fun ReorderDialog(items: List<Pair<String, String>>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var order by remember { mutableStateOf(items) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page order") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                order.forEachIndexed { i, (_, label) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        confirmButton = { TextButton(onClick = { onSave(order.map { it.first }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun <T> List<T>.swap(a: Int, b: Int): List<T> {
    if (a !in indices || b !in indices) return this
    return toMutableList().also { it[a] = this[b]; it[b] = this[a] }
}
