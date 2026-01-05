package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import javax.inject.Inject

/**
 * Speichert eine neue Wallpaper-Konfiguration.
 */
class SaveWallpaperStateUseCase @Inject constructor(
    private val repository: WallpaperRepository
) {
    suspend operator fun invoke(state: WallpaperState) {
        repository.saveWallpaperState(state)
    }

    /**
     * Convenience: Nur die Transformation updaten, URI beibehalten.
     */
    suspend fun updateTransform(
        currentState: WallpaperState,
        scale: Float,
        translateX: Float,
        translateY: Float
    ) {
        repository.saveWallpaperState(
            currentState.copy(
                scale = scale,
                translateX = translateX,
                translateY = translateY
            )
        )
    }
}