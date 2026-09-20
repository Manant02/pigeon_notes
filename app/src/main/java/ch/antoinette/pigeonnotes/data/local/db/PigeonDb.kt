package ch.antoinette.pigeonnotes.data.local.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** What happened locally since [SyncEntry.baseEtag] was agreed with the server. */
enum class LocalState { CLEAN, MODIFIED, NEW, DELETED }

/**
 * Sync bookkeeping for one file or directory. Content lives on disk (see FileStore); this row only
 * records what we last agreed with the server plus a list-index (title/preview) for notes.
 */
@Entity(tableName = "entries")
data class SyncEntry(
    /** Relative to the notes root, `/`-separated, no leading slash. `""` is the root itself. */
    @PrimaryKey val path: String,
    val isDir: Boolean,
    /** ETag at the last moment local and remote agreed; null for never-synced entries. */
    val baseEtag: String?,
    val localState: LocalState,
    /** For a locally renamed entry: the previous path, so sync can issue a MOVE instead of PUT+DELETE. */
    val movedFrom: String? = null,
    val title: String = "",
    val preview: String = "",
    val modified: Long = 0L,
    val size: Long = 0L,
    val syncError: String? = null,
) {
    val isNote: Boolean get() = !isDir && path.endsWith(".md", ignoreCase = true)
    val parent: String get() = if ('/' in path) path.substringBeforeLast('/') else ""
    val name: String get() = path.substringAfterLast('/')
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM entries")
    suspend fun all(): List<SyncEntry>

    @Query("SELECT * FROM entries WHERE path = :path")
    suspend fun get(path: String): SyncEntry?

    @Query("SELECT * FROM entries WHERE path = :path")
    fun observe(path: String): Flow<SyncEntry?>

    @Query("SELECT * FROM entries WHERE path LIKE :prefix || '%'")
    suspend fun underPrefix(prefix: String): List<SyncEntry>

    @Query(
        "SELECT * FROM entries WHERE isDir = 0 AND path LIKE '%.md' AND localState != 'DELETED' " +
            "ORDER BY modified DESC"
    )
    fun observeNotes(): Flow<List<SyncEntry>>

    @Query("SELECT * FROM entries WHERE isDir = 1 AND localState != 'DELETED'")
    fun observeDirs(): Flow<List<SyncEntry>>

    @Query("SELECT * FROM entries WHERE isDir = 0 AND localState != 'DELETED'")
    fun observeFiles(): Flow<List<SyncEntry>>

    @Query("SELECT COUNT(*) FROM entries WHERE localState != 'CLEAN'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM entries WHERE syncError IS NOT NULL")
    fun observeErrorCount(): Flow<Int>

    @Upsert
    suspend fun upsert(entry: SyncEntry)

    /**
     * Records a local edit without touching [SyncEntry.baseEtag], so it cannot clobber an ETag the
     * sync engine stored concurrently. Returns 0 when the row does not exist yet.
     */
    @Query(
        "UPDATE entries SET localState = :state, title = :title, preview = :preview, modified = :modified, size = :size " +
            "WHERE path = :path"
    )
    suspend fun markEdited(path: String, state: LocalState, title: String, preview: String, modified: Long, size: Long): Int

    /**
     * Records a successful upload. The row only becomes CLEAN if no edit landed since the engine
     * looked at it ([seenModified]); otherwise the newer local content stays pending against the new ETag.
     */
    @Query(
        "UPDATE entries SET baseEtag = :etag, movedFrom = NULL, syncError = NULL, " +
            "localState = CASE WHEN modified = :seenModified THEN 'CLEAN' ELSE localState END WHERE path = :path"
    )
    suspend fun markUploaded(path: String, etag: String?, seenModified: Long)

    @Upsert
    suspend fun upsertAll(entries: List<SyncEntry>)

    @Query("DELETE FROM entries WHERE path = :path")
    suspend fun delete(path: String)

    @Query("DELETE FROM entries")
    suspend fun clear()
}

@Database(entities = [SyncEntry::class], version = 1, exportSchema = true)
abstract class PigeonDb : RoomDatabase() {
    abstract fun syncDao(): SyncDao

    companion object {
        fun create(context: Context): PigeonDb =
            Room.databaseBuilder(context, PigeonDb::class.java, "pigeon.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
