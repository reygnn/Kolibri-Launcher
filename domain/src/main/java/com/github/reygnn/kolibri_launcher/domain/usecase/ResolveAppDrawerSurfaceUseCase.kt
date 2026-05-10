package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppDrawerMode
import com.github.reygnn.kolibri_launcher.domain.model.AppDrawerSurfaceClassification
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/**
 * Resolves the user's [AppDrawerMode] setting into a final
 * [AppDrawerSurfaceClassification]. AUTO delegates to
 * [ClassifyWallpaperUseCase], which combines Kolibri-internal
 * wallpaper luminance and system-wallpaper colour hints.
 *
 * LIGHT/DARK are explicit user overrides that bypass the
 * classifier entirely.
 */
class ResolveAppDrawerSurfaceUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val classifyWallpaperUseCase: ClassifyWallpaperUseCase,
) {
    operator fun invoke(): Flow<AppDrawerSurfaceClassification> =
        combine(
            settingsRepository.appDrawerModeFlow,
            classifyWallpaperUseCase(),
        ) { mode, classification ->
            when (mode) {
                AppDrawerMode.LIGHT -> AppDrawerSurfaceClassification.LIGHT
                AppDrawerMode.DARK -> AppDrawerSurfaceClassification.DARK
                AppDrawerMode.AUTO -> classification
            }
        }
}
