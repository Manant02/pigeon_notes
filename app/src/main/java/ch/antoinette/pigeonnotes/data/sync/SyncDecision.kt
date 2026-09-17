package ch.antoinette.pigeonnotes.data.sync

import ch.antoinette.pigeonnotes.data.local.db.LocalState

/** What the engine should do for one path. Pure data so the table can be unit-tested. */
sealed interface Action {
    /** Nothing to do. */
    data object None : Action

    /** Remote has content we do not: fetch it and mark clean. */
    data object Download : Action

    /** Remote is gone and we have no local changes: remove local copy and bookkeeping. */
    data object DeleteLocal : Action

    /** Forget the bookkeeping row (both sides already agree the thing does not exist). */
    data object DropEntry : Action

    /** Push local content. [ifMatch] guards against overwriting a remote change; [ifNoneMatch] against clobbering something new. */
    data class Upload(val ifMatch: String?, val ifNoneMatch: Boolean) : Action

    /** Create the directory remotely. */
    data object CreateRemoteDir : Action

    /** Remote and local both changed: keep both copies. */
    data object Conflict : Action

    /** Local deletion, remote untouched: delete remotely (guarded by [ifMatch]). */
    data class DeleteRemote(val ifMatch: String?) : Action

    /** Local deletion but remote changed meanwhile: cancel the deletion and take the remote version. */
    data object KeepRemote : Action

    /** Local rename with an unchanged remote: move remotely. */
    data class MoveRemote(val from: String) : Action
}

object SyncDecision {

    /**
     * Three-way decision for one path.
     *
     * @param local     local bookkeeping state, or null when we have no row for this path
     * @param baseEtag  ETag last agreed with the server (null = never synced)
     * @param remoteEtag ETag the server reports now (null = does not exist remotely)
     * @param isDir     directories only need create/delete handling
     * @param movedFrom previous path for a locally renamed entry
     */
    fun decide(
        local: LocalState?,
        baseEtag: String?,
        remoteEtag: String?,
        isDir: Boolean = false,
        movedFrom: String? = null,
    ): Action {
        val remoteChanged = remoteEtag != baseEtag
        return when (local) {
            null -> if (remoteEtag != null) Action.Download else Action.None

            LocalState.CLEAN -> when {
                remoteEtag == null -> Action.DeleteLocal
                !remoteChanged -> Action.None
                isDir -> Action.None // children are handled individually; the dir just gets its new base etag
                else -> Action.Download
            }

            LocalState.MODIFIED -> when {
                remoteEtag == null -> Action.Upload(ifMatch = null, ifNoneMatch = true)
                !remoteChanged -> Action.Upload(ifMatch = baseEtag, ifNoneMatch = false)
                else -> Action.Conflict
            }

            LocalState.NEW -> when {
                isDir -> if (remoteEtag == null) Action.CreateRemoteDir else Action.None
                movedFrom != null && remoteEtag == null -> Action.MoveRemote(movedFrom)
                remoteEtag == null -> Action.Upload(ifMatch = null, ifNoneMatch = true)
                else -> Action.Conflict
            }

            LocalState.DELETED -> when {
                remoteEtag == null -> Action.DropEntry
                !remoteChanged -> Action.DeleteRemote(ifMatch = if (isDir) null else baseEtag)
                else -> Action.KeepRemote
            }
        }
    }
}
