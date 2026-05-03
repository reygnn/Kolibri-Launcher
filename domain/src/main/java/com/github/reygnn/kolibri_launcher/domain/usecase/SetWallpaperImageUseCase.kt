package com.github.reygnn.kolibri_launcher.domain.usecase

import android.net.Uri
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import javax.inject.Inject

/**
 * Setzt ein neues Wallpaper-Bild.
 * Resettet automatisch die Transformation.
 */
class SetWallpaperImageUseCase @Inject constructor(
    private val repository: WallpaperRepository
) {
    suspend operator fun invoke(imageUri: Uri) {
        repository.saveWallpaperState(
            WallpaperState(
                imageUri = imageUri,
                scale = WallpaperState.Companion.DEFAULT_SCALE,
                translateX = 0f,
                translateY = 0f
            )
        )
    }
}