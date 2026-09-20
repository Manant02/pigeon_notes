package ch.antoinette.pigeonnotes.ui.editor

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.antoinette.pigeonnotes.ui.graphViewModel
import ch.antoinette.pigeonnotes.ui.notes.CategoryPickerDialog
import ch.antoinette.pigeonnotes.ui.theme.EditorType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(path: String, onBack: () -> Unit) {
    val vm: EditorViewModel = graphViewModel(key = "editor:$path") { EditorViewModel(it, path) }
    val palette = rememberMarkdownPalette()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val categories by vm.categories.collectAsStateWithLifecycle(emptyList())

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
                    Text(
                        vm.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { vm.document.commit(); vm.flush(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
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
                            text = { Text("Move to category…") },
                            leadingIcon = { Icon(Icons.Outlined.DriveFileMove, null) },
                            onClick = { menuOpen = false; moving = true },
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
                    HybridEditor(vm, palette, onOpenLink = openLink)
                }
            }
            if (vm.document.active != null || vm.sourceMode) {
                EditorToolbar(
                    field = if (vm.sourceMode) vm.sourceField else vm.document.active?.field,
                    onDone = { if (vm.sourceMode) vm.toggleSourceMode() else vm.document.commit() },
                    onPickImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.zIndex(1f).background(MaterialTheme.colorScheme.surfaceContainer).fillMaxWidth(),
                )
            }
        }
    }

    if (moving) {
        CategoryPickerDialog(
            title = "Move to",
            categories = categories,
            current = vm.category,
            onDismiss = { moving = false },
            onPick = { vm.moveToCategory(it); moving = false },
        )
    }

    if (renaming) {
        var name by remember { mutableStateOf(vm.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename note") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Title") }) },
            confirmButton = { TextButton(onClick = { vm.rename(name); renaming = false }) { Text("Rename") } },
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
