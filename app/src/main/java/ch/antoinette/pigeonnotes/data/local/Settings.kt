package ch.antoinette.pigeonnotes.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

/** What the sync engine needs from settings; an interface so tests can supply a fixed account. */
interface AccountSource {
    suspend fun currentAccount(): Account?
    suspend fun markSynced(at: Long = System.currentTimeMillis())
}

class Settings(private val context: Context) : AccountSource {
    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val loginName = stringPreferencesKey("login_name")
        val appPassword = stringPreferencesKey("app_password")
        val notesRoot = stringPreferencesKey("notes_root")
        val lastSync = longPreferencesKey("last_sync")
        val tileView = booleanPreferencesKey("tile_view")
        val pageRows = intPreferencesKey("page_rows")
        val pageOrder = stringPreferencesKey("page_order")
        val pageColors = stringPreferencesKey("page_colors")
    }

    /** Show note lists as Keep-style tiles instead of rows. */
    val tileView: Flow<Boolean> = context.dataStore.data.map { it[Keys.tileView] ?: false }

    suspend fun setTileView(enabled: Boolean) {
        context.dataStore.edit { it[Keys.tileView] = enabled }
    }

    /** How many rows the horizontally scrolling page tiles on the home screen use. */
    val pageRows: Flow<Int> = context.dataStore.data.map { it[Keys.pageRows] ?: DEFAULT_PAGE_ROWS }

    suspend fun setPageRows(rows: Int) {
        context.dataStore.edit { it[Keys.pageRows] = rows.coerceIn(PAGE_ROW_OPTIONS.first(), PAGE_ROW_OPTIONS.last()) }
    }

    /** User-chosen order of pages (by path); pages not listed follow alphabetically. */
    val pageOrder: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.pageOrder]?.split('\u0000')?.filter { it.isNotEmpty() } ?: emptyList()
    }

    suspend fun setPageOrder(order: List<String>) {
        context.dataStore.edit { it[Keys.pageOrder] = order.joinToString("\u0000") }
    }

    /** Accent colour per page path, as the name of a [ch.antoinette.pigeonnotes.ui.notes.CategoryAccent]. */
    val pageColors: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.pageColors]?.split('\u0000').orEmpty()
            .mapNotNull { entry ->
                val i = entry.indexOf('\u0001')
                if (i <= 0) null else entry.substring(0, i) to entry.substring(i + 1)
            }
            .toMap()
    }

    suspend fun setPageColor(page: String, accent: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.pageColors]?.split('\u0000').orEmpty()
                .filter { it.isNotEmpty() && !it.startsWith(page + '\u0001') }
            prefs[Keys.pageColors] = (current + (page + '\u0001' + accent)).joinToString("\u0000")
        }
    }

    /** Keeps the per-page settings pointing at a page that was renamed, or drops a deleted one. */
    suspend fun renamePageRefs(from: String, to: String?) {
        context.dataStore.edit { prefs ->
            prefs[Keys.pageOrder] = prefs[Keys.pageOrder]?.split('\u0000').orEmpty()
                .filter { it.isNotEmpty() }.mapNotNull { if (it == from) to else it }.joinToString("\u0000")
            prefs[Keys.pageColors] = prefs[Keys.pageColors]?.split('\u0000').orEmpty()
                .filter { it.isNotEmpty() }
                .mapNotNull { entry ->
                    val i = entry.indexOf('\u0001')
                    val name = if (i > 0) entry.substring(0, i) else entry
                    when {
                        name != from -> entry
                        to == null -> null
                        else -> to + entry.substring(i)
                    }
                }
                .joinToString("\u0000")
        }
    }

    val account: Flow<Account?> = context.dataStore.data.map { it.toAccount() }
    val lastSync: Flow<Long?> = context.dataStore.data.map { it[Keys.lastSync] }

    override suspend fun currentAccount(): Account? = context.dataStore.data.first().toAccount()

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

    override suspend fun markSynced(at: Long) {
        context.dataStore.edit { it[Keys.lastSync] = at }
    }

    private fun Preferences.toAccount(): Account? {
        val url = this[Keys.serverUrl] ?: return null
        val user = this[Keys.loginName] ?: return null
        val pw = this[Keys.appPassword] ?: return null
        return Account(url, user, pw, this[Keys.notesRoot] ?: "/Notes")
    }

    companion object {
        const val DEFAULT_PAGE_ROWS = 2
        val PAGE_ROW_OPTIONS = listOf(1, 2, 3)
    }
}
