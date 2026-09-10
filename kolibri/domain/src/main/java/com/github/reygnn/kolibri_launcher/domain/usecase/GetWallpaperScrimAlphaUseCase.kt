package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Observes the user-controlled home wallpaper scrim alpha (opt-in dim, default 0).
 * Grouped with the text-shadow / readability controls (surfaced in the
 * colors & shadow dialog), not with layout scaling.
 */
class GetWallpaperScrimAlphaUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    operator fun invoke(): Flow<Float> = repository.wallpaperScrimAlphaStateFlow
}
