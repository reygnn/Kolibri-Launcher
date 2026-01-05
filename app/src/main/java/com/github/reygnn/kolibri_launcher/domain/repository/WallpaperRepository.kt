package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import kotlinx.coroutines.flow.Flow

/**
 * Repository-Interface für Wallpaper-Persistierung.
 *
 * Domain-Layer kennt keine Implementierungsdetails.
 * Ob SharedPreferences, DataStore oder Room – egal.
 */
interface WallpaperRepository {

    /**
     * Reaktiver Stream des aktuellen Wallpaper-Zustands.
     * Emittiert bei jeder Änderung.
     */
    val wallpaperState: Flow<WallpaperState>

    /**
     * Speichert den Wallpaper-Zustand.
     * @param state Der zu speichernde Zustand (isEditMode wird ignoriert)
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
    fun getWallpaperStateSync(): WallpaperState
}