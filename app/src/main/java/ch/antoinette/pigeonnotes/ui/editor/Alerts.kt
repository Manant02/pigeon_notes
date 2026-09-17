package ch.antoinette.pigeonnotes.ui.editor

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * GitHub-style alerts: a block quote whose first line is `> [!NOTE]` (or TIP, IMPORTANT,
 * WARNING, CAUTION). https://github.com/orgs/community/discussions/16925
 */
enum class AlertKind(val label: String, val icon: ImageVector) {
    NOTE("Note", Icons.Outlined.Info),
    TIP("Tip", Icons.Outlined.Lightbulb),
    IMPORTANT("Important", Icons.Outlined.Campaign),
    WARNING("Warning", Icons.Outlined.WarningAmber),
    CAUTION("Caution", Icons.Outlined.Report);

    companion object {
        /** Reads the kind from a block's source, defaulting to [NOTE] for anything unrecognised. */
        fun of(source: String): AlertKind {
            val tag = Regex("""\[!([A-Za-z]+)]""").find(source.lineSequence().firstOrNull().orEmpty())
                ?.groupValues?.get(1)?.uppercase()
            return entries.firstOrNull { it.name == tag } ?: NOTE
        }
    }
}

/**
 * Accent colour for an alert. Material You gives no semantic "warning" role, so the palette's
 * own roles are reused: they stay legible in both themes and follow the wallpaper.
 */
@Composable
@ReadOnlyComposable
fun AlertKind.accent(): Color = when (this) {
    AlertKind.NOTE -> MaterialTheme.colorScheme.primary
    AlertKind.TIP -> MaterialTheme.colorScheme.tertiary
    AlertKind.IMPORTANT -> MaterialTheme.colorScheme.secondary
    AlertKind.WARNING -> MaterialTheme.colorScheme.error
    AlertKind.CAUTION -> MaterialTheme.colorScheme.error
}
