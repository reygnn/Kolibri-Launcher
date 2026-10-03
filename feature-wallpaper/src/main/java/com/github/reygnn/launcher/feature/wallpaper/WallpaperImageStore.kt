package com.github.reygnn.launcher.feature.wallpaper

import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The life cycle of the wallpaper image files (SPEC_NYX_REWRITE 3a-2, file lifecycle): copy a picked image in,
 * delete files no layer needs anymore, collect orphans — with Kolibri's edit guard. The caller
 * (today Kolibri's `WallpaperDelegate`, in 3b Nyx) owns the state and the edit session; this
 * class owns every file decision, so the rules below live in one place for both apps.
 *
 * Rules, pinned by `WallpaperImageStoreContract`:
 *  - **Order:** copy → save → delete. [deleteUnreferenced] takes the state that is ALREADY
 *    persisted; a process death in between leaves at most an orphan, which [collectOrphans]
 *    removes later — never a layer pointing at a missing file.
 *  - **Only unreferenced files:** a file is deleted only if no layer of that saved state
 *    references it. Layers never share a file (O2), but if that ever breaks, removing one of two
 *    layers must not take the other's image with it.
 *  - **Edit guard:** no orphan GC while an edit session is open — files the session may still
 *    restore are referenced only by its in-memory snapshot, not by the saved state.
 *
 * Blocking disk I/O runs on the injected [ioDispatcher].
 */
@Singleton
class WallpaperImageStore @Inject constructor(
    private val fileManager: WallpaperFileManager,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** Copies the picked [source] into internal storage; null when it can't be read. */
    suspend fun copyIn(source: Uri): Uri? = fileManager.copyToInternal(source)

    /**
     * Deletes those [candidates] that no layer of [saved] references anymore. [saved] must already
     * be persisted (copy → save → delete).
     */
    suspend fun deleteUnreferenced(candidates: Collection<String>, saved: WallpaperState) {
        val referenced = saved.referencedUris
        val doomed = candidates.filterNot { it in referenced }.distinct()
        if (doomed.isEmpty()) return
        // deleteFile is internally guarded (never throws), so a bad delete can't abort the batch.
        withContext(ioDispatcher) { doomed.forEach { fileManager.deleteFile(it) } }
    }

    /**
     * Removes the files no layer of [saved] references — unless [editSessionOpen]. Returns whether
     * the GC ran. Files younger than the file manager's cutoff survive (a copy whose save has not
     * landed yet).
     */
    suspend fun collectOrphans(saved: WallpaperState, editSessionOpen: Boolean): Boolean {
        if (editSessionOpen) return false
        withContext(ioDispatcher) { fileManager.gcOrphans(saved.referencedUris) }
        return true
    }

    /** Deletes every wallpaper image file ("remove wallpaper"); true when none is left. */
    suspend fun deleteAll(): Boolean = withContext(ioDispatcher) { fileManager.clearAll() }
}
