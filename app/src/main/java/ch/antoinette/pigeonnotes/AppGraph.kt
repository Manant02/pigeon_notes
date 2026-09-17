package ch.antoinette.pigeonnotes

import android.content.Context
import ch.antoinette.pigeonnotes.data.local.FileStore
import ch.antoinette.pigeonnotes.data.local.Settings
import ch.antoinette.pigeonnotes.data.local.db.PigeonDb
import ch.antoinette.pigeonnotes.data.remote.LoginFlowV2
import ch.antoinette.pigeonnotes.data.remote.WebDavClient
import ch.antoinette.pigeonnotes.data.sync.ConnectivityObserver
import ch.antoinette.pigeonnotes.data.sync.SyncEngine
import ch.antoinette.pigeonnotes.domain.NotesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Hand-rolled dependency graph; one instance per process. */
class AppGraph private constructor(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(context)
    val db = PigeonDb.create(context)
    val files = FileStore(File(context.filesDir, "notes"))
    val http = WebDavClient.newHttpClient()
    val loginFlow = LoginFlowV2(http)
    val syncEngine = SyncEngine(settings, db.syncDao(), files, http, appScope)
    val notes = NotesRepository(files, db.syncDao(), syncEngine)
    val connectivity = ConnectivityObserver(context) { syncEngine.requestSync(delayMillis = 500) }

    companion object {
        @Volatile private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }
    }
}
