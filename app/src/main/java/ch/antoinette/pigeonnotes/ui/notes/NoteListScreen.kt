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
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Label
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.contentColorFor
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
import ch.antoinette.pigeonnotes.domain.Structure
import ch.antoinette.pigeonnotes.ui.graphViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * One folder of notes (a page folder, with the page's accent), the archive, or every note for
 * searching. Long-press selects; the selection can be archived, deleted, tagged or added to a page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(
    scope: ListScope,
    startSearching: Boolean = false,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenSyncSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    vm: NoteListViewModel = graphViewModel(key = "list:$scope") { NoteListViewModel(it, scope) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(startSearching) }
    var confirmDelete by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }
    var paging by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val inArchive = scope == ListScope.Archive
    val accent = state.accent.color()
    val tinted = state.accent != CategoryAccent.DEFAULT

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (state.picked.isNotEmpty()) {
                SelectionTopBar(
                    count = state.picked.size,
                    inArchive = inArchive,
                    onClose = vm.selection::clear,
                    onArchive = { vm.selection.archive(!inArchive) },
                    onDelete = { confirmDelete = true },
                    onTag = { tagging = true },
                    onPage = { paging = true },
                )
            } else {
                LargeTopAppBar(
                    title = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                    colors = if (tinted) TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = state.accent.container(),
                        scrolledContainerColor = state.accent.container(),
                        titleContentColor = accent,
                    ) else TopAppBarDefaults.largeTopAppBarColors(),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        floatingActionButton = {
            if (!inArchive) {
                FloatingActionButton(
                    onClick = { vm.createNote(onOpenNote) },
                    containerColor = if (tinted) accent else FloatingActionButtonDefaults.containerColor,
                    contentColor = if (tinted) MaterialTheme.colorScheme.surface else contentColorFor(FloatingActionButtonDefaults.containerColor),
                ) {
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
            PullToRefreshBox(
                isRefreshing = state.status is SyncStatus.Syncing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.rows.isEmpty()) {
                    Text(
                        if (state.query.isNotEmpty()) "Nothing matches." else "No notes here yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else if (state.tileView) {
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
                                onClick = { if (state.picked.isEmpty()) onOpenNote(row.path) else vm.selection.toggle(row.path) },
                                onLongClick = { vm.selection.toggle(row.path) },
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
                                onClick = { if (state.picked.isEmpty()) onOpenNote(row.path) else vm.selection.toggle(row.path) },
                                onLongClick = { vm.selection.toggle(row.path) },
                                onDelete = { vm.delete(row.path) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
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
}

/** The delete / tag / add-to-page dialogs for a selection, shared by the home screen and note lists. */
@Composable
fun SelectionDialogs(
    selection: NoteSelection,
    count: Int,
    structure: Structure,
    confirmDelete: Boolean, onConfirmDeleteChange: (Boolean) -> Unit,
    tagging: Boolean, onTaggingChange: (Boolean) -> Unit,
    paging: Boolean, onPagingChange: (Boolean) -> Unit,
) {
    val what = if (count == 1) "note" else "$count notes"
    if (confirmDelete) {
        ConfirmDeleteDialog(count, onDismiss = { onConfirmDeleteChange(false) }) { selection.delete(); onConfirmDeleteChange(false) }
    }
    if (tagging) {
        TagPickerDialog(
            title = "Tag $what",
            tags = structure.tags,
            current = null,
            onDismiss = { onTaggingChange(false) },
            onPick = { selection.tag(it); onTaggingChange(false) },
            onCreate = { selection.createTagAndApply(it); onTaggingChange(false) },
        )
    }
    if (paging) {
        PagePickerDialog(
            title = "Add $what to page",
            folders = structure.pageFolders,
            current = null,
            onDismiss = { onPagingChange(false) },
            onPick = { selection.addToPage(it); onPagingChange(false) },
            onCreate = { selection.createPageFolderAndApply(it); onPagingChange(false) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    count: Int,
    inArchive: Boolean,
    onClose: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onTag: () -> Unit,
    onPage: () -> Unit,
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
            IconButton(onClick = onTag) { Icon(Icons.Outlined.Label, "Tag") }
            IconButton(onClick = onPage) { Icon(Icons.Outlined.Folder, "Add to page") }
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NoteListRow(
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
fun NoteTile(row: NoteRow, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
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
                if (row.tag != null) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        row.tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
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
fun NotConfiguredHint(onOpenSettings: () -> Unit) {
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
