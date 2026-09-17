package ch.antoinette.pigeonnotes.ui.notes

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
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
    onOpenNote: (String) -> Unit,
    onOpenSettings: () -> Unit,
    vm: NoteListViewModel = graphViewModel { NoteListViewModel(it) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<NoteRow?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(state.selected?.ifEmpty { "Notes" } ?: "Pigeon Notes") },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) vm.search("") }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search")
                    }
                    SyncStatusIcon(state.status, state.pendingCount, onClick = onOpenSettings)
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.createNote(onOpenNote) }) {
                Icon(Icons.Default.Add, "New note")
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
                NotConfiguredHint(onOpenSettings)
            }
            PullToRefreshBox(
                isRefreshing = state.status is SyncStatus.Syncing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(state.rows, key = { it.path }) { row ->
                        NoteListRow(row, onClick = { onOpenNote(row.path) }, onDelete = { pendingDelete = row })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete “${row.title}”?") },
            text = { Text("The note and its assets folder are removed here and on the server at the next sync.") },
            confirmButton = { TextButton(onClick = { vm.delete(row.path); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteListRow(row: NoteRow, onClick: () -> Unit, onDelete: () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete(); dismissState.reset()
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onErrorContainer) }
        },
    ) {
        ListItem(
            modifier = Modifier.background(MaterialTheme.colorScheme.background).clickable(onClick = onClick),
            headlineContent = { Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                if (row.preview.isNotEmpty()) Text(row.preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End) {
                    Text(relativeTime(row.modified), style = MaterialTheme.typography.labelSmall)
                    if (row.error != null) {
                        Spacer(Modifier.height(4.dp))
                        Icon(Icons.Outlined.ErrorOutline, "Sync error", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    } else if (row.pending) {
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                    }
                }
            },
        )
    }
}

@Composable
private fun SyncStatusIcon(status: SyncStatus, pending: Int, onClick: () -> Unit) {
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
