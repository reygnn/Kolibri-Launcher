package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Observiert den Wallpaper-Zustand reaktiv.
 */
class ObserveWallpaperStateUseCase @Inject constructor(
    private val repository: WallpaperRepository
) {
    operator fun invoke(): Flow<WallpaperState> = repository.wallpaperState
}