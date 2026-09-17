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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.antoinette.pigeonnotes.data.sync.SyncStatus
import ch.antoinette.pigeonnotes.ui.graphViewModel

/**
 * The start screen: the notes touched most recently, chips for the categories that are not pinned,
 * and a section per pinned category showing its latest notes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenNote: (String) -> Unit,
    onOpenCategory: (Category) -> Unit,
    onSearch: () -> Unit,
    onOpenAllCategories: () -> Unit,
    onOpenSyncSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    vm: HomeViewModel = graphViewModel { HomeViewModel(it) },
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes") },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search") }
                    SyncStatusIcon(state.status, state.pendingCount, onClick = onOpenSyncSettings)
                    IconButton(onClick = onOpenAppSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.createNote(onOpenNote) }) { Icon(Icons.Default.Add, "New note") }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.status is SyncStatus.Syncing,
            onRefresh = vm::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item("recent") {
                    RecentNotes(state.recent, onOpenNote = onOpenNote, onSeeAll = { onOpenCategory(null) })
                }
                item("chips") {
                    CategoryRow(
                        chips = state.chips,
                        showUncategorized = state.hasUncategorized,
                        onOpenCategory = onOpenCategory,
                        onSeeAll = onOpenAllCategories,
                    )
                }
                items(state.pinned, key = { it.category }) { section ->
                    PinnedCategoryCard(section, onOpenNote = onOpenNote, onOpenCategory = { onOpenCategory(section.category) })
                }
                if (state.pinned.isEmpty()) {
                    item("hint") {
                        Text(
                            "Pin a category in Settings to show its notes here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The most recent notes as square-ish tiles, two per row. */
@Composable
private fun RecentNotes(recent: List<NoteRow>, onOpenNote: (String) -> Unit, onSeeAll: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (recent.isEmpty()) {
            Text(
                "No notes yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            for (pair in recent.chunked(2)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (row in pair) {
                        RecentTile(row, Modifier.weight(1f)) { onOpenNote(row.path) }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        SeeAll("All notes", onSeeAll)
    }
}

@Composable
private fun RecentTile(row: NoteRow, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.heightIn(min = 84.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.preview.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    row.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(
    chips: List<String>,
    showUncategorized: Boolean,
    onOpenCategory: (Category) -> Unit,
    onSeeAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showUncategorized) {
                item("root") {
                    FilterChip(selected = false, onClick = { onOpenCategory("") }, label = { Text("Uncategorized") })
                }
            }
            items(chips, key = { it }) { c ->
                FilterChip(selected = false, onClick = { onOpenCategory(c) }, label = { Text(c) })
            }
        }
        SeeAll("All categories", onSeeAll)
    }
}

/** A pinned category: its title in the category's accent, then its most recent notes. */
@Composable
private fun PinnedCategoryCard(section: PinnedSection, onOpenNote: (String) -> Unit, onOpenCategory: () -> Unit) {
    val accent = section.accent.color()
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = section.accent.container()),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenCategory).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(10.dp))
            Text(
                section.category,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (section.total > section.notes.size) "${section.total}" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Open ${section.category}", tint = accent, modifier = Modifier.size(18.dp))
        }
        if (section.notes.isEmpty()) {
            Text(
                "No notes yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
            )
        }
        for (row in section.notes) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenNote(row.path) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.preview.isNotEmpty()) {
                    Text(
                        row.preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SeeAll(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onClick) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp))
        }
    }
}
