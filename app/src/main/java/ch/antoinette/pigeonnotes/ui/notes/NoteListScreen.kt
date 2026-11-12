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
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.SearchOff
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.antoinette.pigeonnotes.data.sync.SyncStatus
import ch.antoinette.pigeonnotes.domain.Structure
import ch.antoinette.pigeonnotes.ui.graphViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * One folder of notes (a page folder or a folder inside one, with the page's accent) or the
 * archive. Long-press selects; the selection can be archived, deleted, tagged or added to a page.
 * Searching every note has a screen of its own, [SearchScreen].
 *
 * A folder is shown as a tree under a breadcrumb of where it is: its sub-folders, which open in
 * place or, tapped, on a screen of their own ([onOpenFolder]), and its notes.
 * [onOpenCrumb] goes back up to a folder named in the breadcrumb (`Pages` = the home screen).
 *
 * The archive is grouped by month; swiping a note right takes it out of the archive (with undo),
 * and its search looks through the whole text of the archived notes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(
    scope: ListScope,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenFolder: (String) -> Unit = {},
    onOpenCrumb: (String) -> Unit = {},
    onOpenSyncSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    vm: NoteListViewModel = graphViewModel(key = "list:$scope") { NoteListViewModel(it, scope) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Saveable, so coming back from a note finds the search as it was left.
    var searching by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf(vm.typedQuery.value) }
    var focusSearch by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    val scopeCo = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }
    var paging by remember { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    var folderOptions by remember { mutableStateOf<String?>(null) }
    var renamingFolder by remember { mutableStateOf<String?>(null) }
    val notEmpty by vm.notEmpty.collectAsStateWithLifecycle()
    val inFolder = scope is ListScope.Folder
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val inArchive = scope == ListScope.Archive
    val accent = state.accent.color()
    val tinted = state.accent != CategoryAccent.DEFAULT

    LaunchedEffect(focusSearch) {
        if (focusSearch) { searchFocus.requestFocus(); focusSearch = false }
    }

    fun search(value: String) { text = value; vm.search(value) }

    /** Takes a note out of the archive and offers to put it back. */
    fun restore(path: String) = vm.restore(path) { restored ->
        scopeCo.launch {
            val result = snackbar.showSnackbar("Moved to quick notes", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) vm.archive(restored)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
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
                        IconButton(onClick = {
                            searching = !searching
                            if (searching) focusSearch = true else { search(""); keyboard?.hide() }
                        }) {
                            Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search")
                        }
                        if (inFolder) {
                            IconButton(onClick = { creatingFolder = true }) { Icon(Icons.Outlined.CreateNewFolder, "New folder") }
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
            if (state.crumbs.isNotEmpty()) {
                Breadcrumbs(state.crumbs, accent, onCrumb = onOpenCrumb)
            }
            if (searching) {
                SearchField(
                    query = text,
                    onQueryChange = ::search,
                    placeholder = if (inArchive) "Search the archive" else "Search “${state.title}”",
                    focusRequester = searchFocus,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            PullToRefreshBox(
                isRefreshing = state.status is SyncStatus.Syncing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                val tree = state.tree
                if (tree != null && tree.isNotEmpty()) {
                    FolderTreeList(
                        items = tree,
                        accent = accent,
                        accentContainer = state.accent.container(),
                        picked = state.picked,
                        onOpenNote = onOpenNote,
                        onOpenFolder = onOpenFolder,
                        onToggleFolder = vm::toggleExpanded,
                        onFolderOptions = { folderOptions = it },
                        onToggleNote = vm.selection::toggle,
                        onDeleteNote = vm::delete,
                    )
                } else if (tree != null) {
                    Text(
                        if (state.query.isNotEmpty()) "Nothing matches."
                        else "This folder is empty. Add a note with +, or a folder with the folder button above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else if (state.loaded) {
                    ArchiveList(
                        rows = state.rows,
                        hits = state.hits,
                        query = text,
                        picked = state.picked,
                        onOpenNote = onOpenNote,
                        onToggle = vm.selection::toggle,
                        onDelete = vm::delete,
                        onRestore = ::restore,
                        modifier = Modifier.hideKeyboardOnScroll(keyboard),
                    )
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

    if (creatingFolder) {
        NameDialog(
            title = "New folder in “${state.title}”",
            initial = "",
            confirm = "Create",
            taken = state.subfolders,
            onDismiss = { creatingFolder = false },
            onConfirm = { vm.createSubfolder(it); creatingFolder = false },
        )
    }
    folderOptions?.let { path ->
        FolderOptionsDialog(
            name = path.substringAfterLast('/'),
            onDismiss = { folderOptions = null },
            onRename = { folderOptions = null; renamingFolder = path },
            onDelete = { folderOptions = null; vm.deleteFolder(path) },
        )
    }
    renamingFolder?.let { path ->
        val name = path.substringAfterLast('/')
        val siblings = state.structure.folders
            .filter { it.substringBeforeLast('/', "") == path.substringBeforeLast('/', "") && it != path }
            .map { it.substringAfterLast('/') }
        NameDialog(
            title = "Rename folder",
            initial = name,
            confirm = "Rename",
            taken = siblings,
            onDismiss = { renamingFolder = null },
            onConfirm = { vm.renameFolder(path, it); renamingFolder = null },
        )
    }
    notEmpty?.let { (path, remaining) ->
        FolderNotEmptyDialog(path.substringAfterLast('/'), remaining, onDismiss = { vm.notEmpty.value = null })
    }
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

/** Swiping the row to the start deletes the note; with [onRestore], swiping it to the end restores it from the archive. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDelete(enabled: Boolean, onDelete: () -> Unit, onRestore: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        when (dismissState.currentValue) {
            SwipeToDismissBoxValue.EndToStart -> { onDelete(); dismissState.reset() }
            SwipeToDismissBoxValue.StartToEnd -> { onRestore?.invoke(); dismissState.reset() }
            SwipeToDismissBoxValue.Settled -> Unit
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = onRestore != null,
        gesturesEnabled = enabled,
        backgroundContent = {
            if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { Icon(Icons.Outlined.Unarchive, "Restore", tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            } else {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onErrorContainer) }
            }
        },
    ) { content() }
}

/**
 * The archived notes, newest first under a header per month, or the notes matching [query] (the
 * whole text is searched) while one is typed. Swiping a note right restores it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchiveList(
    rows: List<NoteRow>,
    hits: List<SearchHit>?,
    query: String,
    picked: Set<String>,
    onOpenNote: (String) -> Unit,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRestore: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) {
        EmptyState(
            Icons.Outlined.Archive, "Nothing archived",
            "Archive a note from its menu, or long-press notes to archive several. " +
                "Archived notes are kept and searchable, but out of the way.",
        )
        return
    }
    val selecting = picked.isNotEmpty()
    @Composable
    fun Note(hit: SearchHit) = SearchResultRow(
        hit = hit,
        showLocation = false,
        selected = hit.row.path in picked,
        selecting = selecting,
        onClick = { if (selecting) onToggle(hit.row.path) else onOpenNote(hit.row.path) },
        onLongClick = { onToggle(hit.row.path) },
        onDelete = { onDelete(hit.row.path) },
        onRestore = { onRestore(hit.row.path) },
    )
    // While the first search after typing runs, the months stay on screen.
    if (query.isNotBlank() && hits != null) {
        if (hits.isEmpty()) {
            EmptyState(
                Icons.Outlined.SearchOff, "Nothing archived matches “${query.trim()}”",
                if (NoteSearch.terms(query).size > 1) "Every word has to appear in the note, in any order." else null,
            )
            return
        }
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            stickyHeader("count") { SectionHeader(if (hits.size == 1) "1 note" else "${hits.size} notes", null) }
            items(hits, key = { it.row.path }) { Note(it) }
        }
        return
    }
    val months = remember(rows) { rows.groupBy { monthOf(it.modified) }.toList() }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        for ((month, inMonth) in months) {
            stickyHeader("month:$month") { SectionHeader(monthLabel(month), inMonth.size) }
            items(inMonth, key = { it.path }) { row ->
                Note(SearchHit(row, "", emptyList(), row.preview, emptyList(), archived = true))
            }
        }
    }
}

private fun monthOf(epochMillis: Long): YearMonth =
    YearMonth.from(Instant.ofEpochMilli(epochMillis.coerceAtLeast(0)).atZone(ZoneId.systemDefault()))

private val monthFmt = DateTimeFormatter.ofPattern("MMMM")
private val monthYearFmt = DateTimeFormatter.ofPattern("MMMM yyyy")

/** "This month", "October" for earlier this year, "October 2025" before that. */
private fun monthLabel(month: YearMonth): String {
    val now = YearMonth.now()
    return when {
        month == now -> "This month"
        month.year == now.year -> month.format(monthFmt)
        else -> month.format(monthYearFmt)
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
internal fun StatusDot(row: NoteRow) {
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
