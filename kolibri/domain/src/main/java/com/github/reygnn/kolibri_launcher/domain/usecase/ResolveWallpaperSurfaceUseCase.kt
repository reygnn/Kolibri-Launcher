package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.WallpaperSurfaceMode
import com.github.reygnn.kolibri_launcher.domain.model.LuminanceClassification
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/**
 * Resolves the user's [WallpaperSurfaceMode] setting into a final
 * [LuminanceClassification]. AUTO delegates to
 * [ClassifyWallpaperUseCase], which combines Kolibri-internal
 * wallpaper luminance and system-wallpaper colour hints.
 *
 * LIGHT/DARK are explicit user overrides that bypass the
 * classifier entirely.
 */
class ResolveWallpaperSurfaceUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val classifyWallpaperUseCase: ClassifyWallpaperUseCase,
) {
    operator fun invoke(): Flow<LuminanceClassification> =
        combine(
            settingsRepository.wallpaperSurfaceModeFlow,
            classifyWallpaperUseCase(),
        ) { mode, classification ->
            when (mode) {
                WallpaperSurfaceMode.LIGHT -> LuminanceClassification.LIGHT
                WallpaperSurfaceMode.DARK -> LuminanceClassification.DARK
                WallpaperSurfaceMode.AUTO -> classification
            }
        }
}
