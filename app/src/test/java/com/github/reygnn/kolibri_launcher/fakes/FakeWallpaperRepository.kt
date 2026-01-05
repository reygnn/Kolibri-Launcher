package com.github.reygnn.kolibri_launcher.fakes

import android.net.Uri
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake implementation of WallpaperRepository for unit tests.
 *
 * Provides in-memory storage for wallpaper state without requiring
 * Android framework components like DataStore.
 */
class FakeWallpaperRepository : WallpaperRepository {

    private val _wallpaperState = MutableStateFlow(WallpaperState.NONE)

    override val wallpaperState: Flow<WallpaperState> = _wallpaperState.asStateFlow()

    // Direct access for test assertions
    var currentState: WallpaperState
        get() = _wallpaperState.value
        set(value) {
            _wallpaperState.value = value
        }

    override suspend fun getWallpaperStateSync(): WallpaperState = _wallpaperState.value

    override suspend fun saveWallpaperState(state: WallpaperState) {
        _wallpaperState.value = state
    }

    override suspend fun clearWallpaper() {
        _wallpaperState.value = WallpaperState.NONE
    }

    override suspend fun purgeRepository() {
        _wallpaperState.value = WallpaperState.NONE
    }

    /**
     * Reset to default state for test isolation.
     */
    fun reset() {
        _wallpaperState.value = WallpaperState.NONE
    }
}