package ch.antoinette.pigeonnotes.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.antoinette.pigeonnotes.AppGraph
import ch.antoinette.pigeonnotes.data.update.Release
import ch.antoinette.pigeonnotes.data.update.UpdateState
import ch.antoinette.pigeonnotes.data.update.Updater

/**
 * The update dialog, over whatever screen is open: offers a newer build, asks for the permission
 * to install, shows the download and hands over to Android's installer. Sits at the top of the UI
 * so the startup check and the "Check for updates" button in the settings share it.
 */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val updater = AppGraph.get(context).updater
    val state by updater.state.collectAsStateWithLifecycle()

    // Back from the system settings, where the user may just have allowed installing updates.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updater.onPermissionMaybeGranted() }

    when (val s = state) {
        is UpdateState.Available -> AvailableDialog(s.release, updater)
        is UpdateState.NeedsPermission -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Allow installing updates") },
            text = {
                Text(
                    "Android asks once whether Pigeon Notes may install apps. In the next screen, turn on " +
                        "“Allow from this source”, then come back: the update continues by itself."
                )
            },
            confirmButton = {
                TextButton(onClick = { context.startActivity(updater.permissionSettings()) }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = updater::dismiss) { Text("Cancel") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text("Downloading ${s.release.versionName}") },
            text = {
                Column {
                    val p = s.progress
                    if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (p == null) "Starting…" else "${(p * 100).toInt()} % of ${megabytes(s.release.apk.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = updater::dismiss) { Text("Cancel") } },
        )
        is UpdateState.Confirming -> {
            val confirm = s.confirm
            LaunchedEffect(confirm) {
                if (confirm != null) {
                    context.startActivity(confirm)
                    updater.confirmationShown()
                }
            }
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
                title = { Text("Installing ${s.release.versionName}") },
                text = {
                    Column {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("Confirm the update in Android's dialog. The app closes while it is replaced; open it again afterwards.")
                    }
                },
                confirmButton = {},
            )
        }
        is UpdateState.InstallFailed -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Update not installed") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { updater.install(s.release) }) { Text("Try again") } },
            dismissButton = { TextButton(onClick = updater::dismiss) { Text("Close") } },
        )
        // Checking, up to date and check errors are shown in the settings, not as a dialog.
        else -> Unit
    }
}

@Composable
private fun AvailableDialog(release: Release, updater: Updater) {
    AlertDialog(
        onDismissRequest = updater::dismiss,
        icon = { Icon(Icons.Outlined.SystemUpdate, null) },
        title = { Text(if (release.prerelease) "Test build available" else "Update available") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "${updater.installed.versionName} → ${release.versionName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (release.prerelease) {
                    Text(
                        release.title.removePrefix("Test: "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (release.notes.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(release.notes, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Downloads ${megabytes(release.apk.size)} from GitHub and installs it. Your notes and settings stay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { updater.install(release) }) { Text("Install") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                TextButton(onClick = { updater.skip(release) }) { Text("Skip") }
                TextButton(onClick = updater::dismiss) { Text("Later") }
            }
        },
    )
}

private fun megabytes(bytes: Long): String =
    if (bytes <= 0) "the APK" else "%.1f MB".format(bytes / 1_000_000.0)
