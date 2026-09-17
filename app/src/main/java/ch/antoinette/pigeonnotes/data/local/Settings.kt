package ch.antoinette.pigeonnotes.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Server connection details. The app password lives in app-private storage; this is a single-user device. */
data class Account(
    val serverUrl: String,
    val loginName: String,
    val appPassword: String,
    val notesRoot: String,
) {
    /** `https://host/remote.php/dav/files/<user>/Notes` without trailing slash. */
    val davRoot: String
        get() = serverUrl.trimEnd('/') + "/remote.php/dav/files/" + loginName + "/" + notesRoot.trim('/')
}

class Settings(private val context: Context) {
    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val loginName = stringPreferencesKey("login_name")
        val appPassword = stringPreferencesKey("app_password")
        val notesRoot = stringPreferencesKey("notes_root")
        val lastSync = longPreferencesKey("last_sync")
    }

    val account: Flow<Account?> = context.dataStore.data.map { it.toAccount() }
    val lastSync: Flow<Long?> = context.dataStore.data.map { it[Keys.lastSync] }

    suspend fun currentAccount(): Account? = context.dataStore.data.first().toAccount()

    suspend fun saveAccount(serverUrl: String, loginName: String, appPassword: String, notesRoot: String = "/Notes") {
        context.dataStore.edit {
            it[Keys.serverUrl] = serverUrl.trimEnd('/')
            it[Keys.loginName] = loginName
            it[Keys.appPassword] = appPassword
            it[Keys.notesRoot] = notesRoot
        }
    }

    suspend fun clearAccount() {
        context.dataStore.edit {
            it.remove(Keys.serverUrl); it.remove(Keys.loginName); it.remove(Keys.appPassword); it.remove(Keys.lastSync)
        }
    }

    suspend fun markSynced(at: Long = System.currentTimeMillis()) {
        context.dataStore.edit { it[Keys.lastSync] = at }
    }

    private fun Preferences.toAccount(): Account? {
        val url = this[Keys.serverUrl] ?: return null
        val user = this[Keys.loginName] ?: return null
        val pw = this[Keys.appPassword] ?: return null
        return Account(url, user, pw, this[Keys.notesRoot] ?: "/Notes")
    }
}
