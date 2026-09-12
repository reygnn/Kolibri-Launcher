package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveWallpaperBackdropUseCase @Inject constructor(
    private val settingsRepository: WallpaperDisplaySettings
) {
    operator fun invoke(): Flow<WallpaperBackdrop> = settingsRepository.wallpaperBackdropFlow
}
