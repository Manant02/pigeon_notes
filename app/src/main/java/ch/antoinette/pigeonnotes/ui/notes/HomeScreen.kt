package ch.antoinette.pigeonnotes.ui.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LabelOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.antoinette.pigeonnotes.data.sync.SyncStatus
import ch.antoinette.pigeonnotes.ui.graphViewModel

/**
 * The start screen, top to bottom: quick notes (short-term, in the root folder), the pages row
 * (long-term, `Pages/`) and the tagged notes (medium-term, `Tags/`) behind a row of tag pills.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onOpenNote: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenArchive: () -> Unit,
    onOpenSyncSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    vm: HomeViewModel = graphViewModel { HomeViewModel(it) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }
    var paging by remember { mutableStateOf(false) }
    var addingPage by remember { mutableStateOf(false) }
    var namingPage by remember { mutableStateOf<Boolean?>(null) } // true = folder, false = single page
    var addingTag by remember { mutableStateOf(false) }
    val selecting = state.picked.isNotEmpty()

    fun tapNote(path: String) = if (selecting) vm.selection.toggle(path) else onOpenNote(path)

    Scaffold(
        topBar = {
            if (selecting) {
                SelectionTopBar(
                    count = state.picked.size,
                    inArchive = false,
                    onClose = vm.selection::clear,
                    onArchive = { vm.selection.archive(true) },
                    onDelete = { confirmDelete = true },
                    onTag = { tagging = true },
                    onPage = { paging = true },
                )
            } else {
                TopAppBar(
                    title = { Text("Notes") },
                    actions = {
                        IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search") }
                        IconButton(onClick = onOpenArchive) { Icon(Icons.Outlined.Archive, "Archive") }
                        SyncStatusIcon(state.status, state.pendingCount, onClick = onOpenSyncSettings)
                        IconButton(onClick = onOpenAppSettings) { Icon(Icons.Default.Settings, "Settings") }
                    },
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.createQuickNote(onOpenNote) }) { Icon(Icons.Default.Add, "New quick note") }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.status is SyncStatus.Syncing,
            onRefresh = vm::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!state.configured) {
                    item("hint") { NotConfiguredHint(onOpenSyncSettings) }
                }
                if (state.quick.isNotEmpty()) {
                    item("quick") {
                        QuickNotes(state.quick, state.picked, onTap = ::tapNote, onLongPress = vm.selection::toggle)
                    }
                }
                item("pages") {
                    PagesRow(
                        pages = state.pages,
                        rows = state.pageRows,
                        onOpenNote = onOpenNote,
                        onOpenFolder = onOpenFolder,
                        onAdd = { addingPage = true },
                    )
                }
                stickyHeader("pills") {
                    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 4.dp)) {
                        TagPills(state.tags, state.tagFilter, onFilter = vm::filterTags, onAdd = { addingTag = true })
                    }
                }
                if (state.tagged.isEmpty()) {
                    item("empty-tags") {
                        Text(
                            when (state.tagFilter) {
                                null -> "No tagged notes yet. Long-press a quick note to tag it."
                                TagFilter.Untagged -> "No untagged notes."
                                is TagFilter.Tag -> "Nothing tagged “${(state.tagFilter as TagFilter.Tag).name}” yet."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (state.tagged.isNotEmpty()) {
                    item("tagged") {
                        StaggeredTiles(
                            state.tagged, state.picked,
                            onTap = ::tapNote, onLongPress = vm.selection::toggle,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        }
    }

    SelectionDialogs(
        selection = vm.selection,
        count = state.picked.size,
        structure = state.structure,
        confirmDelete = confirmDelete, onConfirmDeleteChange = { confirmDelete = it },
        tagging = tagging, onTaggingChange = { tagging = it },
        paging = paging, onPagingChange = { paging = it },
    )

    if (addingPage) {
        AlertDialog(
            onDismissRequest = { addingPage = false },
            title = { Text("New page") },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text("Single page") },
                        supportingContent = { Text("One note, opened right away") },
                        leadingContent = { Icon(Icons.Outlined.Article, null) },
                        modifier = Modifier.clickable { addingPage = false; namingPage = false },
                    )
                    ListItem(
                        headlineContent = { Text("Page folder") },
                        supportingContent = { Text("A folder holding several notes") },
                        leadingContent = { Icon(Icons.Outlined.CreateNewFolder, null) },
                        modifier = Modifier.clickable { addingPage = false; namingPage = true },
                    )
                }
            },
            confirmButton = { TextButton(onClick = { addingPage = false }) { Text("Cancel") } },
        )
    }
    namingPage?.let { folder ->
        NameDialog(
            title = if (folder) "New page folder" else "New page",
            initial = "",
            confirm = "Create",
            taken = state.pages.map { it.name },
            onDismiss = { namingPage = null },
            onConfirm = { name ->
                namingPage = null
                if (folder) vm.createPageFolder(name) else vm.createPage(name, onOpenNote)
            },
        )
    }
    if (addingTag) {
        NameDialog(
            title = "New tag",
            initial = "",
            confirm = "Create",
            taken = state.tags,
            onDismiss = { addingTag = false },
            onConfirm = { vm.createTag(it); addingTag = false },
        )
    }
}

/** The short-term notes on a lighter panel that grows with their number. */
@Composable
private fun QuickNotes(
    quick: List<NoteRow>,
    picked: Set<String>,
    onTap: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(8.dp),
    ) {
        StaggeredTiles(quick, picked, onTap, onLongPress)
    }
}

/**
 * Two columns of tiles packed like a masonry: each tile goes to the column that is estimated to be
 * shorter, so tiles of different heights leave no gaps.
 */
@Composable
private fun StaggeredTiles(
    rows: List<NoteRow>,
    picked: Set<String>,
    onTap: (String) -> Unit,
    onLongPress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val columns = remember(rows) {
        val left = ArrayList<NoteRow>(); val right = ArrayList<NoteRow>()
        var leftHeight = 0; var rightHeight = 0
        for (row in rows) {
            val h = estimatedLines(row)
            if (leftHeight <= rightHeight) { left += row; leftHeight += h } else { right += row; rightHeight += h }
        }
        left to right
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (column in listOf(columns.first, columns.second)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in column) {
                    NoteTile(
                        row = row,
                        selected = row.path in picked,
                        onClick = { onTap(row.path) },
                        onLongClick = { onLongPress(row.path) },
                    )
                }
            }
        }
    }
}

/** Rough tile height in text lines, from how the title and preview wrap in a half-width tile. */
private fun estimatedLines(row: NoteRow): Int {
    val title = ((row.title.length + 19) / 20).coerceIn(1, 2)
    val preview = if (row.preview.isEmpty()) 0 else ((row.preview.length + 25) / 26).coerceIn(1, 8)
    return title * 2 + preview + 2 // the title is larger; the footer row counts as two
}

private val PageTileHeight = 132.dp
private val PageTileWidth = 150.dp

/** Long-term pages in a horizontally scrolling grid of [rows] rows, with a "+" tile at the end. */
@Composable
private fun PagesRow(
    pages: List<PageTile>,
    rows: Int,
    onOpenNote: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val rowCount = rows.coerceIn(1, 3).coerceAtMost(maxOf(1, pages.size + 1))
    LazyHorizontalGrid(
        rows = GridCells.Fixed(rowCount),
        modifier = Modifier.fillMaxWidth().height(PageTileHeight * rowCount + 8.dp * (rowCount - 1)),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(pages, key = { it.path }) { page ->
            PageCard(page) { if (page.folder) onOpenFolder(page.path) else onOpenNote(page.path) }
        }
        item("add") {
            Card(
                modifier = Modifier.width(PageTileWidth).height(PageTileHeight).clickable(onClick = onAdd),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, "New page", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A single page shows its preview; a folder page its name with a folder mark and its latest notes. */
@Composable
private fun PageCard(page: PageTile, onClick: () -> Unit) {
    val accent = page.accent.color()
    Card(
        modifier = Modifier.width(PageTileWidth).height(PageTileHeight).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = page.accent.container()),
    ) {
        Column(Modifier.padding(12.dp).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (page.folder) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(16.dp), tint = accent)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    page.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = accent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            if (page.folder) {
                for (title in page.recent.take(3)) {
                    Text(
                        "· $title",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (page.count > 3) {
                    Text(
                        "+${page.count - 3} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (page.preview.isNotEmpty()) {
                Text(
                    page.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The tag filter: untagged first (icon only), then one pill per tag, then "+". Tapping the active pill clears it. */
@Composable
private fun TagPills(tags: List<String>, filter: TagFilter?, onFilter: (TagFilter) -> Unit, onAdd: () -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item("untagged") {
            FilterChip(
                selected = filter == TagFilter.Untagged,
                onClick = { onFilter(TagFilter.Untagged) },
                label = { Icon(Icons.Outlined.LabelOff, "Untagged", Modifier.size(18.dp)) },
            )
        }
        items(tags, key = { it }) { tag ->
            FilterChip(
                selected = filter == TagFilter.Tag(tag),
                onClick = { onFilter(TagFilter.Tag(tag)) },
                label = { Text(tag) },
            )
        }
        item("add") {
            AssistChip(onClick = onAdd, label = { Icon(Icons.Default.Add, "New tag", Modifier.size(18.dp)) })
        }
    }
}
