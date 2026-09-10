package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * Persists the user-controlled home wallpaper scrim alpha (opt-in dim, default 0).
 * Grouped with the text-shadow / readability controls (surfaced in the
 * colors & shadow dialog), not with layout scaling.
 */
class SetWallpaperScrimAlphaUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    suspend operator fun invoke(alpha: Float) = repository.setWallpaperScrimAlpha(alpha)
}
