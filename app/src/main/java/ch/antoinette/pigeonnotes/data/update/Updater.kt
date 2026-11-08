package ch.antoinette.pigeonnotes.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings as AndroidSettings
import ch.antoinette.pigeonnotes.BuildConfig
import ch.antoinette.pigeonnotes.data.local.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Where an update stands. The dialog follows [Available] through to [Confirming]. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class CheckFailed(val message: String) : UpdateState
    data class Available(val release: Release) : UpdateState
    /** Android must first be told that this app may install apps. */
    data class NeedsPermission(val release: Release) : UpdateState
    /** [progress] is 0..1, or null while the size is unknown. */
    data class Downloading(val release: Release, val progress: Float?) : UpdateState
    /**
     * Handed to the system installer. [confirm] is the system's "Do you want to update this app?"
     * screen, to be shown by the UI while it is there; null once it is shown or not needed.
     */
    data class Confirming(val release: Release, val confirm: Intent?) : UpdateState
    data class InstallFailed(val release: Release, val message: String) : UpdateState
}

/**
 * Keeps the app up to date from its own GitHub releases: looks for a newer build, downloads its
 * APK, checks it against GitHub's digest and hands it to the system installer. Android replaces
 * the app with it and ends this process; signing with the same key (see CLAUDE.md) keeps the data.
 */
class Updater(
    private val context: Context,
    private val http: OkHttpClient,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    val installed = InstalledBuild(
        versionCode = BuildConfig.VERSION_CODE,
        versionName = BuildConfig.VERSION_NAME,
        commit = BuildConfig.GIT_COMMIT,
        commitTime = BuildConfig.GIT_COMMIT_TIME,
    )

    private var startupChecked = false
    private var job: Job? = null
    private val dir get() = File(context.cacheDir, "updates")

    /** Once per process, when the app is opened: quiet unless there is something to offer. */
    fun checkOnStartup() {
        if (startupChecked) return
        startupChecked = true
        scope.launch(Dispatchers.IO) { dir.deleteRecursively() } // an APK left over from the last update
        check(manual = false)
    }

    /**
     * Looks for an update. A [manual] check (from the settings) reports "up to date" and errors
     * and also offers a build the user skipped; the startup check stays silent about both.
     */
    fun check(manual: Boolean) {
        if (busy()) return
        job = scope.launch {
            if (manual) _state.value = UpdateState.Checking
            val result = runCatching { fetchReleases() }
            val releases = result.getOrElse { e ->
                if (e is CancellationException) throw e
                _state.value = if (manual) UpdateState.CheckFailed(e.message ?: "No connection") else UpdateState.Idle
                return@launch
            }
            val pick = Releases.pick(releases, installed, settings.testBuilds.first())
            _state.value = when {
                pick == null -> if (manual) UpdateState.UpToDate else UpdateState.Idle
                !manual && pick.commit == settings.skippedUpdate.first() -> UpdateState.Idle
                else -> UpdateState.Available(pick)
            }
        }
    }

    /** "Later": the prompt comes back on the next start. */
    fun dismiss() {
        job?.cancel()
        _state.value = UpdateState.Idle
    }

    /** "Skip": this build is not offered on startup again; a newer one is. */
    fun skip(release: Release) {
        scope.launch { settings.setSkippedUpdate(release.commit) }
        _state.value = UpdateState.Idle
    }

    /** Downloads [release] and installs it, after asking for the permission to install if needed. */
    fun install(release: Release) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(release)
            return
        }
        job?.cancel()
        job = scope.launch {
            try {
                _state.value = UpdateState.Downloading(release, null)
                val apk = download(release)
                ensureActive()
                _state.value = UpdateState.Confirming(release, null)
                commit(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.InstallFailed(release, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** The system settings page where the user lets this app install apps. */
    fun permissionSettings(): Intent =
        Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))

    /** Back from the settings page: carry on if the permission was given. */
    fun onPermissionMaybeGranted() {
        val s = _state.value as? UpdateState.NeedsPermission ?: return
        if (context.packageManager.canRequestPackageInstalls()) install(s.release)
    }

    // ---- results from the system installer (InstallResultReceiver) ------------------------------

    internal fun onConfirmationNeeded(confirm: Intent) {
        val release = (state.value as? UpdateState.Confirming)?.release ?: return
        _state.value = UpdateState.Confirming(release, confirm)
    }

    /** The UI showed the confirmation screen. */
    fun confirmationShown() {
        val s = state.value as? UpdateState.Confirming ?: return
        _state.value = s.copy(confirm = null)
    }

    internal fun onInstallFailed(message: String) {
        val release = (state.value as? UpdateState.Confirming)?.release ?: return
        _state.value = UpdateState.InstallFailed(release, message)
    }

    internal fun onInstalled() {
        // Usually never seen: Android ends this process to replace the app.
        _state.value = UpdateState.Idle
    }

    // ---- network and installer ------------------------------------------------------------------

    private fun busy() = when (state.value) {
        is UpdateState.Downloading, is UpdateState.Confirming -> true
        else -> false
    }

    private suspend fun fetchReleases(): List<Release> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=30")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PigeonNotes/${installed.versionName}")
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("GitHub answered ${resp.code}")
            Releases.parse(resp.body.string())
        }
    }

    /** Streams the APK to the cache, reporting progress, and checks its size and digest. */
    private suspend fun download(release: Release): File = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, release.apk.name)
        val sha = MessageDigest.getInstance("SHA-256")
        val request = Request.Builder().url(release.apk.url).header("User-Agent", "PigeonNotes/${installed.versionName}").build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed (${resp.code})")
            val total = resp.body.contentLength().takeIf { it > 0 } ?: release.apk.size.takeIf { it > 0 }
            var done = 0L
            var shown = -1
            resp.body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        done += n
                        val percent = total?.let { (done * 100 / it).toInt() } ?: -1
                        // Only while still downloading: "Cancel" may have closed the dialog meanwhile.
                        if (percent != shown && _state.value is UpdateState.Downloading) {
                            shown = percent
                            _state.value = UpdateState.Downloading(release, total?.let { done.toFloat() / it })
                        }
                    }
                }
            }
        }
        if (release.apk.size > 0 && file.length() != release.apk.size) {
            throw IOException("The download is incomplete (${file.length()} of ${release.apk.size} bytes)")
        }
        val digest = sha.digest().joinToString("") { "%02x".format(it) }
        if (release.apk.sha256 != null && digest != release.apk.sha256) {
            throw IOException("The download is damaged: its checksum does not match GitHub's")
        }
        file
    }

    /**
     * Writes the APK into an installer session and commits it. The outcome arrives in
     * [InstallResultReceiver]. Asks to install without the confirmation screen: Android allows that
     * once this app installed itself before; otherwise it asks the user ([onConfirmationNeeded]).
     */
    private suspend fun commit(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                // Mutable: the installer adds the status to it.
                val pending = PendingIntent.getBroadcast(
                    context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }
}
