package ch.antoinette.pigeonnotes.ui.editor

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.antoinette.pigeonnotes.ui.graphViewModel
import ch.antoinette.pigeonnotes.domain.Structure
import ch.antoinette.pigeonnotes.domain.markdown.Toc
import ch.antoinette.pigeonnotes.ui.notes.PagePickerDialog
import ch.antoinette.pigeonnotes.ui.notes.TagPickerDialog
import ch.antoinette.pigeonnotes.ui.theme.EditorType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(path: String, onBack: () -> Unit) {
    val vm: EditorViewModel = graphViewModel(key = "editor:$path") { EditorViewModel(it, path) }
    val palette = rememberMarkdownPalette()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }
    var paging by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val structure by vm.structure.collectAsStateWithLifecycle(Structure())

    // No sync while the note is open; whatever was requested meanwhile runs when the screen closes.
    DisposableEffect(vm) {
        vm.holdSync()
        onDispose { vm.releaseSync() }
    }

    // Save when the app goes to the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) vm.flush() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
            vm.insertImage(bytes, displayName(context, uri))
        }
    }
    val openLink: (String) -> Unit = { url ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    // Back: first leaves edit mode, then the screen.
    BackHandler(enabled = vm.document.active != null || vm.sourceMode) {
        if (vm.sourceMode) vm.toggleSourceMode() else vm.document.commit()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // The file name: tap it to rename the note.
                    Text(
                        vm.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClickLabel = "Rename") { renaming = true }.padding(end = 8.dp),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { vm.document.commit(); vm.flush(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { showToc = true }, enabled = !vm.sourceMode) {
                        Icon(Icons.AutoMirrored.Filled.Toc, "Table of contents")
                    }
                    IconButton(onClick = { vm.toggleSourceMode() }) {
                        Icon(Icons.Default.Code, "Source", tint = if (vm.sourceMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menuOpen = false; renaming = true },
                        )
                        DropdownMenuItem(
                            text = { Text(if (vm.tag != null) "Change tag…" else "Tag…") },
                            leadingIcon = { Icon(Icons.Outlined.Label, null) },
                            onClick = { menuOpen = false; tagging = true },
                        )
                        DropdownMenuItem(
                            text = { Text(if (vm.pageFolder != null) "Change page…" else "Add to page…") },
                            leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                            onClick = { menuOpen = false; paging = true },
                        )
                        DropdownMenuItem(
                            text = { Text(if (vm.archived) "Unarchive" else "Archive") },
                            leadingIcon = { Icon(if (vm.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null) },
                            onClick = { menuOpen = false; vm.archive(!vm.archived) },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete note") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f)) {
                if (!vm.loaded) return@Box
                if (vm.sourceMode) {
                    BasicTextField(
                        state = vm.sourceField,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        textStyle = EditorType.code.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        lineLimits = TextFieldLineLimits.MultiLine(),
                    )
                } else {
                    HybridEditor(vm, palette, onOpenLink = openLink, listState = listState)
                }
            }
            if (vm.document.active != null || vm.sourceMode) {
                EditorToolbar(
                    field = if (vm.sourceMode) vm.sourceField else vm.document.active?.field,
                    canUndo = vm.canUndo,
                    canRedo = vm.canRedo,
                    onUndo = vm::undo,
                    onRedo = vm::redo,
                    onDone = { if (vm.sourceMode) vm.toggleSourceMode() else vm.document.commit() },
                    onPickImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.zIndex(1f).background(MaterialTheme.colorScheme.surfaceContainer).fillMaxWidth(),
                )
            }
        }
    }

    if (tagging) {
        TagPickerDialog(
            title = "Tag note",
            tags = structure.tags,
            current = vm.tag,
            onDismiss = { tagging = false },
            onPick = { vm.applyTag(it); tagging = false },
            onCreate = { vm.createTagAndApply(it); tagging = false },
        )
    }
    if (paging) {
        PagePickerDialog(
            title = "Add to page",
            folders = structure.pageFolders,
            current = vm.pageFolder,
            onDismiss = { paging = false },
            onPick = { vm.addToPage(it); paging = false },
            onCreate = { vm.createPageFolderAndApply(it); paging = false },
        )
    }

    if (showToc) {
        val toc = Toc.of(vm.document.blocks)
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            Text(
                "Contents", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (toc.isEmpty()) {
                Text(
                    "This note has no headings.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            LazyColumn(Modifier.padding(bottom = 16.dp)) {
                items(toc, key = { it.index }) { entry ->
                    ListItem(
                        headlineContent = {
                            Text(
                                entry.text.ifEmpty { "(empty heading)" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = if (entry.level == 1) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier
                            .clickable {
                                showToc = false
                                // Leaving edit mode can change the block list, so the heading is found again by id.
                                vm.document.commit()
                                val index = vm.document.blocks.indexOfFirst { it.id == entry.id }.takeIf { it >= 0 } ?: entry.index
                                scope.launch { listState.animateScrollToItem(index) }
                            }
                            .padding(start = (16 * (entry.level - 1)).dp),
                    )
                }
            }
        }
    }

    if (renaming) {
        // The whole name is selected, so typing replaces it and a tap places the cursor to adjust it.
        var name by remember { mutableStateOf(TextFieldValue(vm.title, TextRange(0, vm.title.length))) }
        val focus = remember { FocusRequester() }
        val rename = { if (name.text.isNotBlank()) vm.rename(name.text.trim()); renaming = false }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename note") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true, label = { Text("File name") },
                    suffix = { Text(".md") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { rename() }),
                    modifier = Modifier.focusRequester(focus),
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            },
            confirmButton = { TextButton(onClick = rename, enabled = name.text.isNotBlank()) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${vm.title}”?") },
            text = { Text("The note and its assets folder are removed here and on the server at the next sync.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.let { if (it.isNotBlank()) return it }
    }
    val ext = when (context.contentResolver.getType(uri)) {
        "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "jpg"
    }
    return "image.$ext"
}
