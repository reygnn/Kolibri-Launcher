package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import javax.inject.Inject

/**
 * Speichert eine neue Wallpaper-Konfiguration.
 *
 * Unterstützt sowohl Single-Layer als auch Multi-Layer States.
 * Die Persistierung wird vom Repository gehandhabt (DataStore).
 */
class SaveWallpaperStateUseCase @Inject constructor(
    private val repository: WallpaperRepository
) {
    /**
     * Speichert den kompletten WallpaperState (Single oder Multi-Layer).
     */
    suspend operator fun invoke(state: WallpaperState) {
        repository.saveWallpaperState(state)
    }
}