package ch.antoinette.pigeonnotes.ui.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.antoinette.pigeonnotes.data.sync.SyncStatus
import ch.antoinette.pigeonnotes.ui.graphViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(
    category: Category,
    startSearching: Boolean = false,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenSyncSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    vm: NoteListViewModel = graphViewModel(key = "list:$category") { NoteListViewModel(it, category) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(startSearching) }
    var confirmDeletePicked by remember { mutableStateOf(false) }
    var movingPicked by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val inArchive = state.selected == ARCHIVE_DIR

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (state.picked.isNotEmpty()) {
                SelectionTopBar(
                    count = state.picked.size,
                    inArchive = inArchive,
                    onClose = vm::clearPicks,
                    onArchive = { vm.archivePicked(!inArchive) },
                    onDelete = { confirmDeletePicked = true },
                    onMove = { movingPicked = true },
                )
            } else {
                LargeTopAppBar(
                    title = {
                        Text(
                            when (state.selected) {
                                null -> "All notes"
                                "" -> "Uncategorized"
                                ARCHIVE_DIR -> "Archive"
                                else -> state.selected!!
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        IconButton(onClick = { searching = !searching; if (!searching) vm.search("") }) {
                            Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search")
                        }
                        SyncStatusIcon(state.status, state.pendingCount, onClick = onOpenSyncSettings)
                        IconButton(onClick = onOpenAppSettings) { Icon(Icons.Default.Settings, "Settings") }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        floatingActionButton = {
            if (!inArchive) {
                FloatingActionButton(onClick = { vm.createNote(onOpenNote) }) {
                    Icon(Icons.Default.Add, "New note")
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (searching) {
                TextField(
                    value = state.query,
                    onValueChange = vm::search,
                    placeholder = { Text("Search notes") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
            }
            CategoryChips(state.categories, state.selected, vm::select)
            if (!state.configured) {
                NotConfiguredHint(onOpenSyncSettings)
            }
            PullToRefreshBox(
                isRefreshing = state.status is SyncStatus.Syncing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.tileView) {
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Adaptive(160.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalItemSpacing = 8.dp,
                    ) {
                        items(state.rows, key = { it.path }) { row ->
                            NoteTile(
                                row = row,
                                selected = row.path in state.picked,
                                onClick = { if (state.picked.isEmpty()) onOpenNote(row.path) else vm.togglePick(row.path) },
                                onLongClick = { vm.togglePick(row.path) },
                            )
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                        items(state.rows, key = { it.path }) { row ->
                            NoteListRow(
                                row = row,
                                selected = row.path in state.picked,
                                selecting = state.picked.isNotEmpty(),
                                onClick = { if (state.picked.isEmpty()) onOpenNote(row.path) else vm.togglePick(row.path) },
                                onLongClick = { vm.togglePick(row.path) },
                                onDelete = { vm.delete(row.path) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }

    if (confirmDeletePicked) {
        val n = state.picked.size
        AlertDialog(
            onDismissRequest = { confirmDeletePicked = false },
            title = { Text(if (n == 1) "Delete 1 note?" else "Delete $n notes?") },
            text = { Text("The notes and their assets folders are removed here and on the server at the next sync.") },
            confirmButton = { TextButton(onClick = { vm.deletePicked(); confirmDeletePicked = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDeletePicked = false }) { Text("Cancel") } },
        )
    }

    if (movingPicked) {
        CategoryPickerDialog(
            title = if (state.picked.size == 1) "Move note to" else "Move ${state.picked.size} notes to",
            categories = state.categories,
            current = null,
            onDismiss = { movingPicked = false },
            onPick = { vm.movePicked(it); movingPicked = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    inArchive: Boolean,
    onClose: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
) {
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Leave selection") } },
        actions = {
            IconButton(onClick = onArchive) {
                Icon(
                    if (inArchive) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                    if (inArchive) "Unarchive" else "Archive",
                )
            }
            IconButton(onClick = onMove) { Icon(Icons.Outlined.DriveFileMove, "Move to category") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

@Composable
private fun CategoryChips(categories: List<String>, selected: Category, onSelect: (Category) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All") }) }
        item { FilterChip(selected = selected == "", onClick = { onSelect("") }, label = { Text("Notes") }) }
        items(categories) { c ->
            FilterChip(selected = selected == c, onClick = { onSelect(c) }, label = { Text(c) })
        }
        item {
            FilterChip(
                selected = selected == ARCHIVE_DIR,
                onClick = { onSelect(ARCHIVE_DIR) },
                label = { Text("Archive") },
                leadingIcon = { Icon(Icons.Outlined.Archive, null, Modifier.size(18.dp)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun NoteListRow(
    row: NoteRow,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete(); dismissState.reset()
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        gesturesEnabled = !selecting, // swiping would fight the selection

        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onErrorContainer) }
        },
    ) {
        ListItem(
            modifier = Modifier
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = if (!selecting) null else {
                {
                    Icon(
                        if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                        if (selected) "Selected" else "Not selected",
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                }
            },
            headlineContent = { Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                if (row.preview.isNotEmpty()) Text(row.preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End) {
                    Text(relativeTime(row.modified), style = MaterialTheme.typography.labelSmall)
                    StatusDot(row)
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteTile(row: NoteRow, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (row.preview.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    row.preview, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(relativeTime(row.modified), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                StatusDot(row)
            }
        }
    }
}

@Composable
private fun StatusDot(row: NoteRow) {
    if (row.error != null) {
        Icon(Icons.Outlined.ErrorOutline, "Sync error", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
    } else if (row.pending) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
    }
}

@Composable
fun SyncStatusIcon(status: SyncStatus, pending: Int, onClick: () -> Unit) {
    val (icon, desc) = when (status) {
        is SyncStatus.Syncing -> Icons.Outlined.Sync to "Syncing"
        SyncStatus.Offline -> Icons.Outlined.CloudOff to "Offline"
        is SyncStatus.Error -> Icons.Outlined.ErrorOutline to "Sync error"
        SyncStatus.NotConfigured -> Icons.Outlined.CloudOff to "Not configured"
        SyncStatus.Idle -> if (pending > 0) Icons.Outlined.CloudUpload to "$pending pending" else Icons.Outlined.CloudDone to "Synced"
    }
    IconButton(onClick = onClick) {
        Icon(
            icon, desc,
            tint = if (status is SyncStatus.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NotConfiguredHint(onOpenSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Not connected to Nextcloud yet.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onOpenSettings) { Text("Log in") }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dateFmt = DateTimeFormatter.ofPattern("d MMM")
private val yearFmt = DateTimeFormatter.ofPattern("MMM yyyy")

fun relativeTime(epochMillis: Long): String {
    if (epochMillis <= 0) return ""
    val t = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    val now = Instant.now().atZone(ZoneId.systemDefault())
    return when {
        t.toLocalDate() == now.toLocalDate() -> t.format(timeFmt)
        ChronoUnit.DAYS.between(t.toLocalDate(), now.toLocalDate()) < 7 -> t.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        t.year == now.year -> t.format(dateFmt)
        else -> t.format(yearFmt)
    }
}
