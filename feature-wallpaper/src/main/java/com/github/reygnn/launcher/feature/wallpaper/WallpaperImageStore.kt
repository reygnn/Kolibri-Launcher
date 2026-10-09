package com.github.reygnn.launcher.feature.wallpaper

import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
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
 *  - **Against what is persisted, fail closed (3a-2c):** every delete decision reads the
 *    PERSISTED references from the [repository] itself, never a state the caller constructed —
 *    the real save swallows its failure in a release build, so "the save returned" does not mean
 *    "the new state is on disk". If that read fails, NOTHING is deleted and the GC does not run;
 *    at worst orphans remain for the next run.
 *  - **Order:** copy → save → delete; a process death in between leaves at most an orphan, which
 *    [collectOrphans] removes later — never a layer pointing at a missing file.
 *  - **Only unreferenced files:** a file is deleted only if no persisted layer references it.
 *    Layers never share a file (O2), but if that ever breaks, removing one of two layers must not
 *    take the other's image with it.
 *  - **Edit guard:** no orphan GC while an edit session is open — files the session may still
 *    restore are referenced only by its in-memory snapshot, not by the saved state.
 *
 * Blocking disk I/O runs on the injected [ioDispatcher].
 */
@Singleton
class WallpaperImageStore @Inject constructor(
    private val fileManager: WallpaperFileManager,
    private val repository: WallpaperRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** Copies the picked [source] into internal storage; null when it can't be read. */
    suspend fun copyIn(source: Uri): Uri? = fileManager.copyToInternal(source)

    /**
     * Deletes those [candidates] that no persisted layer references anymore; nothing at all when
     * the persisted references can't be read.
     */
    suspend fun deleteUnreferenced(candidates: Collection<String>) {
        if (candidates.isEmpty()) return
        val referenced = repository.readPersistedImageUris() ?: return // fail closed
        val doomed = candidates.filterNot { it in referenced }.distinct()
        if (doomed.isEmpty()) return
        // deleteFile is internally guarded (never throws), so a bad delete can't abort the batch.
        withContext(ioDispatcher) { doomed.forEach { fileManager.deleteFile(it) } }
    }

    /**
     * Removes the files no persisted layer references — unless [editSessionOpen] or the persisted
     * references can't be read. Returns whether the GC ran. Files younger than the file manager's
     * cutoff survive (a copy whose save has not landed yet).
     */
    suspend fun collectOrphans(editSessionOpen: Boolean): Boolean {
        if (editSessionOpen) return false
        val referenced = repository.readPersistedImageUris() ?: return false // fail closed
        withContext(ioDispatcher) { fileManager.gcOrphans(referenced) }
        return true
    }

    /**
     * The image URIs the persisted state references, or null when it can't be read (callers fail
     * closed on null). "Remove wallpaper" reads it before and after emptying the state (3b/29, D2),
     * a replace outside a session at its start and before applying (d′).
     */
    suspend fun readPersistedImageUris(): Set<String>? = repository.readPersistedImageUris()
}
