package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import javax.inject.Inject

/**
 * Entfernt das Custom Wallpaper.
 */
class ClearWallpaperUseCase @Inject constructor(
    private val repository: WallpaperRepository
) {
    suspend operator fun invoke() {
        repository.clearWallpaper()
    }
}