package com.github.reygnn.launcher.core.wallpaper

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

    /**
     * Writes (save and clear) are swallowed like the real repository does in a release build
     * (3a-2c, 3a-2d): the call returns normally, nothing is persisted.
     */
    var failSavesSilently = false

    /** The persisted state can't be read: [readPersistedImageUris] reports null (3a-2c). */
    var persistedStateUnreadable = false

    // Like the real repository's read path: an unreadable store falls back to "nothing saved"
    // (3b-02, shared by both apps' contract runs — Nyx's GC used to rely on exactly this read).
    override suspend fun getWallpaperStateSync(): WallpaperState =
        if (persistedStateUnreadable) WallpaperState.NONE else _wallpaperState.value

    override suspend fun readPersistedImageUris(): Set<String>? =
        if (persistedStateUnreadable) null else _wallpaperState.value.referencedUris

    override suspend fun saveWallpaperState(state: WallpaperState) {
        if (failSavesSilently) return
        _wallpaperState.value = state
    }

    override suspend fun clearWallpaper() {
        if (failSavesSilently) return
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
        failSavesSilently = false
        persistedStateUnreadable = false
    }
}