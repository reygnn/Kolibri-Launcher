package com.github.reygnn.launcher.core.wallpaper

import com.github.reygnn.launcher.core.Purgeable
import kotlinx.coroutines.flow.Flow

/**
 * Repository-Interface für Wallpaper-Persistierung.
 *
 * Domain-Layer kennt keine Implementierungsdetails.
 * Ob SharedPreferences, DataStore oder Room – egal.
 */
interface WallpaperRepository : Purgeable {

    /**
     * Reaktiver Stream des aktuellen Wallpaper-Zustands.
     * Emittiert bei jeder Änderung.
     */
    val wallpaperState: Flow<WallpaperState>

    /**
     * Saves the wallpaper state.
     * @param state The state to persist (its layers with their transforms).
     */
    suspend fun saveWallpaperState(state: WallpaperState)

    /**
     * Löscht das Custom Wallpaper und setzt auf Default zurück.
     */
    suspend fun clearWallpaper()

    /**
     * Synchroner Getter für den aktuellen Zustand.
     * Nutzen: Initial-Load beim Fragment-Start.
     */
    suspend fun getWallpaperStateSync(): WallpaperState

    /**
     * The image URIs the PERSISTED state references, read with failure reporting (3a-2c): null when
     * the store can't be read or holds something unreadable. Unlike [getWallpaperStateSync] it never
     * falls back to [WallpaperState.NONE] — "nothing referenced" would let a caller that deletes
     * unreferenced files delete everything. Callers that delete must delete NOTHING on null.
     *
     * The default is null ("can't tell"), so an implementation without a real read fails closed:
     * nothing is ever deleted through it, at worst orphans remain.
     */
    suspend fun readPersistedImageUris(): Set<String>? = null
}
