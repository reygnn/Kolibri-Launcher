package com.github.reygnn.nyx_launcher.data.home

import android.net.Uri
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.feature.wallpaper.WallpaperImageStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nyx's thin "set / clear the home wallpaper" coordinator (WV5). Keeps the two steps — copy the
 * picked image into app-internal storage (so the transient content-Uri grant doesn't matter
 * after restart), then persist a single-layer [WallpaperState] — out of the settings UI.
 *
 * Since 3b-1 every delete decision goes through the shared [WallpaperImageStore]: against what is
 * actually PERSISTED, and nothing at all when that can't be read (fail closed). Before, Nyx
 * deleted directly — the old files even when a swallowed save left them referenced, the files on
 * "remove" even when the clear did not land, and the orphan GC read the references through
 * `getWallpaperStateSync()`, which falls back to "nothing" on a read error. The edit session's
 * locks come with 3b-3; until then [editState] keeps choose, remove and the GC away from the files
 * of an open session (3b-1b-b).
 *
 * Interim, until 3b-3 — a known side effect: a choose or remove from outside (Settings, the sheet)
 * while an edit session is open is overwritten again when that session commits or cancels, because
 * the session writes its own state back. That is no data loss — the files stay (orphans for a GC
 * with the session closed) — and it goes away with 3b-3 (shared session, locks, re-sync).
 */
@Singleton
class NyxWallpaperImageSetter @Inject constructor(
    private val repository: WallpaperRepository,
    private val imageStore: WallpaperImageStore,
    private val editState: NyxWallpaperEditState,
) {
    /**
     * Copies [sourceUri] into internal storage and saves it as the single wallpaper layer.
     * Returns true on success, false if the copy failed (e.g. revoked permission / decode
     * failure) — the caller can surface a toast. Copy → save → delete: the previously referenced
     * files go once the new state is saved, and only those no persisted layer references.
     *
     * While an edit session is open nothing is deleted (3b-1b-b): its layers may still reference
     * the previous files and would write them back on commit. They stay as orphans for the GC.
     */
    suspend fun setFromUri(sourceUri: Uri): Boolean {
        val internalUri = imageStore.copyIn(sourceUri) ?: return false
        val newUri = internalUri.toString()
        val previous = referencedUris()
        repository.saveWallpaperState(WallpaperState.single(newUri))
        if (!editState.sessionOpen) imageStore.deleteUnreferenced(previous.filter { it != newUri })
        return true
    }

    /**
     * "Remove wallpaper": the state first, the files second, and only against an empty persisted
     * state (as in Kolibri, 3a-2d). Returns whether the removal took effect; false leaves the
     * wallpaper as it is on disk, and the caller says so.
     *
     * While an edit session is open the files stay (orphans for the next GC): the session may
     * still restore them, and Nyx's edit session has no locks against a concurrent copy yet
     * (3b-3). The result then depends only on whether the clear landed.
     */
    suspend fun clear(): Boolean {
        repository.clearWallpaper()
        if (editState.sessionOpen) return repository.readPersistedImageUris()?.isEmpty() == true
        return imageStore.deleteAllIfNothingPersisted()
    }

    /**
     * One-shot at startup: reclaims files stranded by a crash between copy and save — through the
     * store, so an unreadable state deletes nothing and an open edit session is left alone.
     */
    suspend fun reclaimOrphans() {
        imageStore.collectOrphans(editSessionOpen = editState.sessionOpen)
    }

    private suspend fun referencedUris(): List<String> =
        repository.getWallpaperStateSync().layers.mapNotNull { it.imageUri }
}
