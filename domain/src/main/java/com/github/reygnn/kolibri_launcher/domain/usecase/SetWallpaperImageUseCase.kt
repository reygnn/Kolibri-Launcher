package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import javax.inject.Inject

/**
 * Setzt ein neues Wallpaper-Bild.
 * Resettet automatisch die Transformation.
 *
 * `imageUri` is the URI as a string (`content://` or `file://`); UI-side
 * callers convert from `android.net.Uri` via `Uri.toString()` at the boundary.
 */
class SetWallpaperImageUseCase @Inject constructor(
    private val repository: WallpaperRepository
) {
    suspend operator fun invoke(imageUri: String) {
        repository.saveWallpaperState(
            WallpaperState.single(uri = imageUri)
        )
    }
}
