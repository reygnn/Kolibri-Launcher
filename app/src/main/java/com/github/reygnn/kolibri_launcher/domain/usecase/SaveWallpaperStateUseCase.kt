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

    /**
     * Convenience: Nur die Transformation updaten, URI beibehalten.
     * Funktioniert nur im Single-Layer-Modus.
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

    /**
     * Convenience: Transform eines bestimmten Layers updaten.
     * Funktioniert nur im Multi-Layer-Modus.
     */
    suspend fun updateLayerTransform(
        currentState: WallpaperState,
        layerIndex: Int,
        scale: Float,
        translateX: Float,
        translateY: Float
    ) {
        val newState = currentState.withUpdatedLayer(layerIndex) {
            it.copy(scale = scale, translateX = translateX, translateY = translateY)
        }
        repository.saveWallpaperState(newState)
    }

    /**
     * Convenience: Alle Layer-Transforms auf einmal updaten.
     * @param transforms Liste von (scale, translateX, translateY) pro Layer-Index
     */
    suspend fun updateAllLayerTransforms(
        currentState: WallpaperState,
        transforms: List<Triple<Float, Float, Float>>
    ) {
        var state = currentState
        transforms.forEachIndexed { index, (scale, tx, ty) ->
            state = state.withUpdatedLayer(index) {
                it.copy(scale = scale, translateX = tx, translateY = ty)
            }
        }
        repository.saveWallpaperState(state)
    }
}