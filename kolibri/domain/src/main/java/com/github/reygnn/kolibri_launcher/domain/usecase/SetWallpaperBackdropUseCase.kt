package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import javax.inject.Inject

class SetWallpaperBackdropUseCase @Inject constructor(
    private val settingsRepository: WallpaperDisplaySettings
) {
    suspend operator fun invoke(backdrop: WallpaperBackdrop) {
        settingsRepository.setWallpaperBackdrop(backdrop)
    }
}
