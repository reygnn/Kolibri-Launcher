package com.github.reygnn.nyx_launcher.data.home

import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nyx's thin "set / clear the home wallpaper" coordinator (WV5). Keeps the two
 * steps — copy the picked image into app-internal storage (so the transient
 * content-Uri grant doesn't matter after restart), then persist a single-layer
 * [WallpaperState] — out of the settings UI. Kolibri's equivalent is
 * SetWallpaperImageUseCase; Nyx's v1 is single-layer only (multi-layer collages
 * come with the reduced edit mode).
 *
 * Owns disk reclamation too: the shared [WallpaperRepository]/[WallpaperFileManager]
 * split means clearing/replacing the DataStore state does NOT delete the old image
 * file (only [WallpaperFileManager.clearAll] via purge does). So this coordinator
 * deletes the replaced/cleared file explicitly, and [reclaimOrphans] runs a
 * startup sweep (mirroring Kolibri's WallpaperDelegate) to catch any file stranded
 * by a crash between copy and save.
 */
@Singleton
class NyxWallpaperImageSetter @Inject constructor(
    private val fileManager: WallpaperFileManager,
    private val repository: WallpaperRepository,
) {
    /**
     * Copies [sourceUri] into internal storage and saves it as the single
     * wallpaper layer. Returns true on success, false if the copy failed (e.g.
     * revoked permission / decode failure) — the caller can surface a toast.
     * The previously-referenced image file (if any) is deleted once state points
     * at the new file, so replacing a wallpaper doesn't strand the old one.
     */
    suspend fun setFromUri(sourceUri: Uri): Boolean {
        val internalUri = fileManager.copyToInternal(sourceUri) ?: return false
        val newUri = internalUri.toString()
        val previous = referencedUris()
        repository.saveWallpaperState(WallpaperState.single(newUri))
        previous.filter { it != newUri }.forEach { fileManager.deleteFile(it) }
        return true
    }

    /** Clears the wallpaper state and deletes the backing image file(s). */
    suspend fun clear() {
        val previous = referencedUris()
        repository.clearWallpaper()
        previous.forEach { fileManager.deleteFile(it) }
    }

    /**
     * Startup sweep: delete any internal wallpaper file not referenced by the
     * current state (older than the file-manager's age cutoff), reclaiming files
     * stranded by a crash between copy and save.
     */
    suspend fun reclaimOrphans() {
        val referenced = referencedUris().toSet()
        withContext(Dispatchers.IO) { fileManager.gcOrphans(referenced) }
    }

    private suspend fun referencedUris(): List<String> =
        repository.getWallpaperStateSync().layers.mapNotNull { it.imageUri }
}
