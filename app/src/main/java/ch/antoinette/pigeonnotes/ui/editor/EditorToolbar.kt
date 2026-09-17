package ch.antoinette.pigeonnotes.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Formatting bar shown above the keyboard while a block is being edited. */
@Composable
fun EditorToolbar(field: TextFieldState?, onDone: () -> Unit, onPickImage: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            ToolButton(Icons.Default.FormatBold, "Bold") { field?.wrap("**") }
            ToolButton(Icons.Default.FormatItalic, "Italic") { field?.wrap("*") }
            ToolButton(Icons.Default.Code, "Code") { field?.wrap("`") }
            ToolButton(Icons.Default.Title, "Heading") { field?.cycleHeading() }
            ToolButton(Icons.Default.FormatListBulleted, "Bullet list") { field?.prefixLines("- ") }
            ToolButton(Icons.Default.FormatListNumbered, "Numbered list") { field?.prefixLines("1. ") }
            ToolButton(Icons.Default.CheckBox, "Checkbox") { field?.prefixLines("- [ ] ") }
            ToolButton(Icons.Default.FormatQuote, "Quote") { field?.prefixLines("> ") }
            ToolButton(Icons.Default.Link, "Link") { field?.insertLink() }
            ToolButton(Icons.Default.Image, "Image", onPickImage)
            ToolButton(Icons.Default.KeyboardArrowDown, "Done", onDone)
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label) }
}

// ---- edit helpers on the active field ---------------------------------------------------------

/** Wraps the selection (or the word at the cursor) with [mark]; toggles it off when already wrapped. */
private fun TextFieldState.wrap(mark: String) = edit {
    var s = selection.min; var e = selection.max
    val text = asCharSequence()
    if (s == e) {
        while (s > 0 && !text[s - 1].isWhitespace()) s--
        while (e < length && !text[e].isWhitespace()) e++
    }
    val n = mark.length
    if (s >= n && e + n <= length && text.substring(s - n, s) == mark && text.substring(e, e + n) == mark) {
        replace(e, e + n, ""); replace(s - n, s, "")
        selection = androidx.compose.ui.text.TextRange(s - n, e - n)
    } else {
        replace(e, e, mark); replace(s, s, mark)
        selection = androidx.compose.ui.text.TextRange(s + n, e + n)
    }
}

/** none → `# ` → `## ` → `### ` → none, applied to the first line. */
private fun TextFieldState.cycleHeading() = edit {
    val text = asCharSequence().toString()
    val current = Regex("""^(#{1,6})\s""").find(text)?.groupValues?.get(1)?.length ?: 0
    val cursor = selection.start
    if (current > 0) replace(0, current + 1, "")
    val next = if (current >= 3) 0 else current + 1
    val prefix = if (next == 0) "" else "#".repeat(next) + " "
    if (prefix.isNotEmpty()) replace(0, 0, prefix)
    val delta = prefix.length - (if (current > 0) current + 1 else 0)
    placeCursorBeforeCharAt((cursor + delta).coerceIn(0, length))
}

/** Adds (or removes) [prefix] on every line touched by the selection. */
private fun TextFieldState.prefixLines(prefix: String) = edit {
    val text = asCharSequence().toString()
    val from = text.lastIndexOf('\n', (selection.min - 1).coerceAtLeast(0)).let { if (selection.min == 0) 0 else it + 1 }
    val to = text.indexOf('\n', selection.max).let { if (it < 0) text.length else it }
    val lines = text.substring(from, to).split('\n')
    val allPrefixed = lines.all { it.startsWith(prefix) }
    val updated = lines.joinToString("\n") { if (allPrefixed) it.removePrefix(prefix) else prefix + it.removePrefix(prefix) }
    replace(from, to, updated)
    placeCursorAfterCharAt((from + updated.length - 1).coerceAtLeast(0))
}

private fun TextFieldState.insertLink() = edit {
    val s = selection.min; val e = selection.max
    val label = asCharSequence().substring(s, e).ifEmpty { "link" }
    val md = "[$label](url)"
    replace(s, e, md)
    val urlStart = s + label.length + 3
    selection = androidx.compose.ui.text.TextRange(urlStart, urlStart + 3)
}
