package ch.antoinette.pigeonnotes.ui.settings

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ch.antoinette.pigeonnotes.AppGraph
import ch.antoinette.pigeonnotes.data.sync.SyncStatus
import ch.antoinette.pigeonnotes.ui.graphViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    val account = graph.settings.account
    val status = graph.syncEngine.status
    val log = graph.syncEngine.log
    val lastSync = graph.settings.lastSync

    /** Non-null while a browser login is in progress; the URL to open. */
    val loginUrl = MutableStateFlow<String?>(null)
    val loginError = MutableStateFlow<String?>(null)
    private var loginJob: Job? = null

    fun startLogin(serverUrl: String) {
        loginJob?.cancel()
        loginError.value = null
        loginJob = viewModelScope.launch {
            try {
                val started = graph.loginFlow.start(normalize(serverUrl))
                loginUrl.value = started.loginUrl
                val creds = graph.loginFlow.awaitCredentials(started)
                graph.settings.saveAccount(creds.server, creds.loginName, creds.appPassword)
                loginUrl.value = null
                graph.syncEngine.requestSync()
            } catch (e: Exception) {
                loginUrl.value = null
                loginError.value = e.message ?: "Login failed"
            }
        }
    }

    fun cancelLogin() {
        loginJob?.cancel(); loginUrl.value = null
    }

    fun saveManual(serverUrl: String, user: String, appPassword: String) {
        viewModelScope.launch {
            graph.settings.saveAccount(normalize(serverUrl), user.trim(), appPassword.trim())
            graph.syncEngine.requestSync()
        }
    }

    fun logout() {
        viewModelScope.launch { graph.settings.clearAccount() }
    }

    fun syncNow() = graph.syncEngine.requestSync()

    private fun normalize(url: String): String {
        val t = url.trim().trimEnd('/')
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = graphViewModel { SettingsViewModel(it) }) {
    val account by vm.account.collectAsStateWithLifecycle(null)
    val status by vm.status.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val lastSync by vm.lastSync.collectAsStateWithLifecycle(null)
    val loginUrl by vm.loginUrl.collectAsStateWithLifecycle()
    val loginError by vm.loginError.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Open the browser once per login attempt.
    var openedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    loginUrl?.let { url ->
        if (openedUrl != url) {
            openedUrl = url
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val acc = account
            if (acc == null) {
                LoginCard(
                    busy = loginUrl != null,
                    error = loginError,
                    onLogin = vm::startLogin,
                    onCancel = vm::cancelLogin,
                    onManual = vm::saveManual,
                )
            } else {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Connected", style = MaterialTheme.typography.titleMedium)
                        Text(acc.serverUrl, style = MaterialTheme.typography.bodyMedium)
                        Text("${acc.loginName} · ${acc.notesRoot}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when (val s = status) {
                                is SyncStatus.Syncing -> "Syncing: ${s.detail}"
                                SyncStatus.Offline -> "Offline – changes are kept locally"
                                is SyncStatus.Error -> "Error: ${s.message}"
                                SyncStatus.NotConfigured -> "Not configured"
                                SyncStatus.Idle -> "Up to date" + (lastSync?.let { " · last sync ${ch.antoinette.pigeonnotes.ui.notes.relativeTime(it)}" } ?: "")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (status is SyncStatus.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::syncNow, enabled = status !is SyncStatus.Syncing) { Text("Sync now") }
                            OutlinedButton(onClick = vm::logout) { Text("Log out") }
                        }
                    }
                }
            }

            Text("Sync log", style = MaterialTheme.typography.titleSmall)
            HorizontalDivider()
            if (log.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
            log.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}

@Composable
private fun LoginCard(
    busy: Boolean,
    error: String?,
    onLogin: (String) -> Unit,
    onCancel: () -> Unit,
    onManual: (String, String, String) -> Unit,
) {
    var server by rememberSaveable { mutableStateOf("https://nextcloud.manman.ch") }
    var manual by rememberSaveable { mutableStateOf(false) }
    var user by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect to Nextcloud", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = server, onValueChange = { server = it }, label = { Text("Server URL") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy,
            )
            if (busy) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.height(24.dp))
                    Text("Finish logging in in the browser…", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            } else if (!manual) {
                Button(onClick = { onLogin(server) }, modifier = Modifier.fillMaxWidth()) { Text("Log in with Nextcloud") }
                TextButton(onClick = { manual = true }) { Text("Use an app password instead") }
            } else {
                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("App password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onManual(server, user, password) }, enabled = user.isNotBlank() && password.isNotBlank()) { Text("Save") }
                    TextButton(onClick = { manual = false }) { Text("Back") }
                }
            }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text(
                "Notes are read from the /Notes folder of your account. The app password is stored on this device only.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
