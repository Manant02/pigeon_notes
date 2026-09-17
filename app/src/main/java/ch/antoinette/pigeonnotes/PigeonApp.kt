package ch.antoinette.pigeonnotes

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import ch.antoinette.pigeonnotes.data.sync.SyncWorker

class PigeonApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        val graph = AppGraph.get(this)
        graph.connectivity.start()
        SyncWorker.schedule(this)
        // Sync whenever the app comes to the foreground.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = graph.syncEngine.requestSync()
        })
    }
}
