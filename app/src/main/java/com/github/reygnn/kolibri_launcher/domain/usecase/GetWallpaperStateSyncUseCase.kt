package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import javax.inject.Inject

/**
 * Holt den aktuellen Zustand synchron.
 * Nutzen: Initial-Load, wenn kein Flow gewünscht.
 */
class GetWallpaperStateSyncUseCase @Inject constructor(
    private val repository: WallpaperRepository
) {
    suspend operator fun invoke(): WallpaperState = repository.getWallpaperStateSync()
}