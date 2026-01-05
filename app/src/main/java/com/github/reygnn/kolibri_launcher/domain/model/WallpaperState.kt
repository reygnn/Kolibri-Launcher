package com.github.reygnn.kolibri_launcher.domain.model

import android.net.Uri

/**
 * Domain Model für den Wallpaper-Zustand.
 *
 * Immutable data class – jede Änderung erzeugt eine neue Instanz.
 * Das passt perfekt zu StateFlow im ViewModel.
 */
data class WallpaperState(
    /** URI zum Bild (content:// oder file://) – null = kein Custom Wallpaper */
    val imageUri: Uri? = null,

    /** Zoom-Faktor (1.0 = Original) */
    val scale: Float = DEFAULT_SCALE,

    /** Horizontale Verschiebung in Pixel */
    val translateX: Float = 0f,

    /** Vertikale Verschiebung in Pixel */
    val translateY: Float = 0f,

    /** Edit-Modus aktiv? (transient – nicht persistiert) */
    val isEditMode: Boolean = false
) {
    companion object {
        const val DEFAULT_SCALE = 1.0f

        /** Leerer Zustand – kein Wallpaper gesetzt */
        val NONE = WallpaperState()
    }

    /** Hat der User ein Wallpaper konfiguriert? */
    val hasWallpaper: Boolean
        get() = imageUri != null

    /** Wurde das Bild transformiert (nicht mehr default)? */
    val isTransformed: Boolean
        get() = scale != DEFAULT_SCALE || translateX != 0f || translateY != 0f

    /**
     * Kopie ohne Edit-Modus (für Persistierung).
     * isEditMode ist UI-State, gehört nicht in die Prefs.
     */
    fun forPersistence(): WallpaperState = copy(isEditMode = false)
}