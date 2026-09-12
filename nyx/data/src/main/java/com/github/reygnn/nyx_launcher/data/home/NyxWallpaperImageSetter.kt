package com.github.reygnn.nyx_launcher.data.home

import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nyx's thin "set / clear the home wallpaper" coordinator (WV5). Keeps the two
 * steps — copy the picked image into app-internal storage (so the transient
 * content-Uri grant doesn't matter after restart), then persist a single-layer
 * [WallpaperState] — out of the settings UI. Kolibri's equivalent is
 * SetWallpaperImageUseCase; Nyx's v1 is single-layer only (multi-layer collages
 * come with the reduced edit mode).
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
     */
    suspend fun setFromUri(sourceUri: Uri): Boolean {
        val internalUri = fileManager.copyToInternal(sourceUri) ?: return false
        repository.saveWallpaperState(WallpaperState.single(internalUri.toString()))
        return true
    }

    suspend fun clear() {
        repository.clearWallpaper()
    }
}
