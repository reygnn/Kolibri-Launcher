package com.github.reygnn.nyx_launcher.data.home

import android.net.Uri
import com.github.reygnn.launcher.feature.wallpaper.WallpaperOperations
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nyx's thin "set / clear the home wallpaper" facade (WV5) for Settings and the customization
 * sheet. Since 3b-3 it drives the ONE shared edit session ([NyxWallpaperEditing]) — the same the
 * editor uses — so there is a single truth: choosing a wallpaper during an open session is a
 * session change (kept on commit, undone on cancel), and removing it is refused while a session
 * is open (WallpaperOperations.clear). The interim guard of 3b-1 is gone.
 *
 * Call on the main thread (the session is main-confined); the copy runs on I/O inside the store.
 */
@Singleton
class NyxWallpaperImageSetter @Inject constructor(
    private val editing: NyxWallpaperEditing,
) {
    /**
     * Copies [sourceUri] in and applies it as the single wallpaper layer. Returns the shared
     * [WallpaperOperations.ImageResult] (audit A21, the same mapping as Kolibri's): `Applied`;
     * `CopyFailed` (revoked permission / decode failure — the caller shows its error); or
     * `Discarded` (a removal or cancel won the race — no error, nothing to report).
     */
    suspend fun setFromUri(sourceUri: Uri): WallpaperOperations.ImageResult =
        editing.operations.replace(sourceUri, editing.session.rollbackGeneration)

    /**
     * "Remove wallpaper": state first, files second, only against an empty persisted state, and
     * refused while an edit session is open. Returns whether the removal took effect; false leaves
     * the wallpaper as it is, and the caller says so.
     */
    suspend fun clear(): Boolean = editing.operations.clear()
}
