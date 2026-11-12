package ch.antoinette.pigeonnotes.ui.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.antoinette.pigeonnotes.ui.graphViewModel

/**
 * Searching every note, archive included: the field in the top bar opens with the keyboard up,
 * results show where each note lives and the words found, title matches first, archived notes in
 * a section of their own. With nothing typed it lists every note, newest first.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    vm: NoteListViewModel = graphViewModel(key = "search") { NoteListViewModel(it, ListScope.All) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Kept here rather than read back from the view model, so the field never lags behind typing.
    var text by rememberSaveable { mutableStateOf(vm.typedQuery.value) }
    var confirmDelete by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }
    var paging by remember { mutableStateOf(false) }
    // Only on arrival: coming back from a note should not pop the keyboard up again.
    var focused by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (!focused) { focus.requestFocus(); focused = true }
    }

    fun type(value: String) { text = value; vm.search(value) }

    Scaffold(
        topBar = {
            if (state.picked.isNotEmpty()) {
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
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    title = {
                        SearchField(
                            query = text,
                            onQueryChange = ::type,
                            placeholder = "Search all notes",
                            focusRequester = focus,
                            modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                        )
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val hits = state.hits
            val selecting = state.picked.isNotEmpty()
            @Composable
            fun Result(hit: SearchHit) = SearchResultRow(
                hit = hit,
                showLocation = true,
                selected = hit.row.path in state.picked,
                selecting = selecting,
                onClick = { if (selecting) vm.selection.toggle(hit.row.path) else onOpenNote(hit.row.path) },
                onLongClick = { vm.selection.toggle(hit.row.path) },
                onDelete = { vm.delete(hit.row.path) },
            )

            when {
                !state.loaded -> Unit
                text.isBlank() && state.rows.isEmpty() -> EmptyState(Icons.Default.Search, "No notes yet", null)
                // Also while the first search after typing is still running, so the screen doesn't blank.
                text.isBlank() || hits == null -> LazyColumn(
                    Modifier.fillMaxSize().hideKeyboardOnScroll(keyboard),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    stickyHeader("header") { SectionHeader("All notes", state.rows.size) }
                    items(state.rows, key = { it.path }) { row ->
                        Result(SearchHit(row, NoteSearch.location(row.path), emptyList(), row.preview, emptyList(), archived = false))
                    }
                }
                hits.isEmpty() -> EmptyState(
                    Icons.Outlined.SearchOff,
                    "Nothing found for “${text.trim()}”",
                    if (NoteSearch.terms(text).size > 1) "Every word has to appear in the note, in any order." else null,
                )
                else -> {
                    val (archived, live) = hits.partition { it.archived }
                    LazyColumn(
                        Modifier.fillMaxSize().hideKeyboardOnScroll(keyboard),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        if (live.isNotEmpty()) {
                            stickyHeader("notes") { SectionHeader(if (live.size == 1) "1 note" else "${live.size} notes", null) }
                            items(live, key = { it.row.path }) { Result(it) }
                        }
                        if (archived.isNotEmpty()) {
                            stickyHeader("archive") { SectionHeader("In the archive", archived.size) }
                            items(archived, key = { it.row.path }) { Result(it) }
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

/** A rounded search field: magnifier, the text, and a clear button once something is typed. */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = modifier.focusRequester(focusRequester),
        decorationBox = { field ->
            Row(
                Modifier.clip(CircleShape).background(colors.surfaceContainerHigh).heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, null, tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, maxLines = 1)
                    }
                    field()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange(""); focusRequester.requestFocus() }) {
                        Icon(Icons.Default.Close, "Clear search", tint = colors.onSurfaceVariant)
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }
        },
    )
}

/**
 * A note in search results or the archive: where it lives (when [showLocation]), its title and a
 * snippet with the words found highlighted, when it changed. Swipe left deletes; with [onRestore],
 * swipe right takes it out of the archive.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchResultRow(
    hit: SearchHit,
    showLocation: Boolean,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    onRestore: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val mark = SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.onSurface, background = colors.primary.copy(alpha = 0.22f))
    Column {
        SwipeToDelete(enabled = !selecting, onDelete = onDelete, onRestore = onRestore) {
            ListItem(
                modifier = Modifier
                    .background(if (selected) colors.secondaryContainer else colors.background)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = if (!selecting) null else {
                    {
                        Icon(
                            if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                            if (selected) "Selected" else "Not selected",
                            tint = if (selected) colors.primary else colors.outline,
                        )
                    }
                },
                overlineContent = if (!showLocation) null else {
                    {
                        Text(
                            hit.location, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (hit.row.tag != null) colors.primary else colors.onSurfaceVariant,
                        )
                    }
                },
                headlineContent = {
                    Text(highlighted(hit.row.title, hit.titleHits, mark), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = if (hit.snippet.isEmpty()) null else {
                    { Text(highlighted(hit.snippet, hit.snippetHits, mark), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(relativeTime(hit.row.modified), style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(4.dp))
                        StatusDot(hit.row)
                    }
                },
            )
        }
        HorizontalDivider(color = colors.outlineVariant)
    }
}

/** A small label over a section of a list, with an optional count, on the background so it can stick. */
@Composable
fun SectionHeader(title: String, count: Int?) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A centred icon, a line saying what is (not) here and an optional explanation. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String?) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Sit a little above the middle, where the eye expects it with the keyboard up.
        Spacer(Modifier.height(96.dp))
    }
}

/** [text] with [ranges] drawn in [style]; ranges past the end are ignored. */
fun highlighted(text: String, ranges: List<IntRange>, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    for (r in ranges) {
        val end = minOf(r.last + 1, text.length)
        if (r.first < end) addStyle(style, r.first, end)
    }
}

/** Puts the keyboard away as soon as the list is scrolled by hand, to show more results. */
@Composable
fun Modifier.hideKeyboardOnScroll(keyboard: SoftwareKeyboardController?): Modifier {
    val connection = remember(keyboard) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) keyboard?.hide()
                return Offset.Zero
            }
        }
    }
    return nestedScroll(connection)
}
