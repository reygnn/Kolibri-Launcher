package com.github.reygnn.kolibri_launcher.data

import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository für Wallpaper-Einstellungen mit DataStore-Persistenz.
 *
 * == BACKWARD COMPATIBILITY ==
 * Bestehende Single-Layer Daten (KEY_WALLPAPER_URI etc.) werden beim Lesen
 * automatisch erkannt und als WallpaperState ohne Layer-Liste geladen.
 * Beim ersten Multi-Layer-Speichern werden die alten Keys beibehalten
 * UND die Layer-Liste zusätzlich gespeichert.
 *
 * == MULTI-LAYER ==
 * Layer werden als JSON-Array in einem einzigen DataStore-Key gespeichert.
 * Jedes Layer enthält: id, imageUri, scale, translateX/Y, alpha, blendMode,
 * isVisible, label.
 *
 * Migrations-Pfad:
 * 1. App-Start: Alte Keys vorhanden, kein LAYERS_JSON → Single-Layer (wie bisher)
 * 2. User fügt Layer hinzu → LAYERS_JSON wird geschrieben
 * 3. Nächster App-Start: LAYERS_JSON vorhanden → Multi-Layer
 * 4. User entfernt alle Layer → LAYERS_JSON wird entfernt, zurück zu Single/None
 */
@Singleton
class WallpaperManager @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : WallpaperRepository {

    companion object {
        // --- Legacy Keys (Single-Layer, beibehalten für Migration) ---
        private val KEY_WALLPAPER_URI = stringPreferencesKey("wallpaper_uri")
        private val KEY_WALLPAPER_SCALE = floatPreferencesKey("wallpaper_scale")
        private val KEY_WALLPAPER_TRANSLATE_X = floatPreferencesKey("wallpaper_translate_x")
        private val KEY_WALLPAPER_TRANSLATE_Y = floatPreferencesKey("wallpaper_translate_y")

        // --- Multi-Layer Key ---
        private val KEY_LAYERS_JSON = stringPreferencesKey("wallpaper_layers_json")

        // Defaults
        private const val DEFAULT_SCALE = 1.0f
        private const val DEFAULT_TRANSLATE = 0.0f
    }

    // ===========================================
    // READ: Flow<WallpaperState>
    // ===========================================

    override val wallpaperState: Flow<WallpaperState> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                TimberWrapper.silentError(exception, "Error reading wallpaper preferences")
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            try {
                parseWallpaperState(preferences)
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error parsing wallpaper state")
                WallpaperState.NONE
            }
        }

    /**
     * Parst den WallpaperState aus den DataStore Preferences.
     * Prüft zuerst auf Multi-Layer (JSON), dann Fallback auf Single-Layer Keys.
     */
    private fun parseWallpaperState(preferences: Preferences): WallpaperState {
        val layersJson = preferences[KEY_LAYERS_JSON]

        // ── Multi-Layer: JSON vorhanden ──
        if (!layersJson.isNullOrBlank()) {
            return try {
                val layers = parseLayersFromJson(layersJson)
                if (layers.isNotEmpty()) {
                    WallpaperState(layers = layers)
                } else {
                    // Leeres JSON-Array → Fallback auf Single-Layer
                    parseSingleLayerState(preferences)
                }
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error parsing layers JSON, falling back to single-layer")
                parseSingleLayerState(preferences)
            }
        }

        // ── Single-Layer: Legacy Keys ──
        return parseSingleLayerState(preferences)
    }

    /**
     * Liest den Single-Layer State aus den Legacy-Keys.
     */
    private fun parseSingleLayerState(preferences: Preferences): WallpaperState {
        val uriString = preferences[KEY_WALLPAPER_URI]

        return if (uriString.isNullOrBlank()) {
            WallpaperState.NONE
        } else {
            WallpaperState(
                imageUri = uriString.toUri(),
                scale = preferences[KEY_WALLPAPER_SCALE] ?: DEFAULT_SCALE,
                translateX = preferences[KEY_WALLPAPER_TRANSLATE_X] ?: DEFAULT_TRANSLATE,
                translateY = preferences[KEY_WALLPAPER_TRANSLATE_Y] ?: DEFAULT_TRANSLATE
            )
        }
    }

    // ===========================================
    // READ: Sync
    // ===========================================

    override suspend fun getWallpaperStateSync(): WallpaperState {
        return try {
            wallpaperState.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error getting wallpaper state sync")
            WallpaperState.NONE
        }
    }

    // ===========================================
    // WRITE: Save
    // ===========================================

    override suspend fun saveWallpaperState(state: WallpaperState) {
        try {
            dataStore.edit { preferences ->
                if (state.isMultiLayer) {
                    // ── Multi-Layer speichern ──
                    saveMultiLayerState(preferences, state)
                } else if (state.imageUri != null) {
                    // ── Single-Layer speichern (Legacy) ──
                    saveSingleLayerState(preferences, state)
                } else {
                    // ── Kein Wallpaper → alles entfernen ──
                    removeAllKeys(preferences)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error saving wallpaper state")
        }
    }

    /**
     * Speichert Multi-Layer State als JSON.
     * Legacy-Keys werden ebenfalls aktualisiert (Layer 0 als Fallback).
     */
    private fun saveMultiLayerState(preferences: MutablePreferences, state: WallpaperState) {
        // JSON-Array der Layer
        val jsonArray = layersToJson(state.layers)
        preferences[KEY_LAYERS_JSON] = jsonArray.toString()

        // Legacy-Keys mit Layer 0 füllen (Fallback für ältere Code-Pfade)
        val firstLayer = state.layers.firstOrNull { it.hasImage }
        if (firstLayer != null) {
            preferences[KEY_WALLPAPER_URI] = firstLayer.imageUri.toString()
            preferences[KEY_WALLPAPER_SCALE] = firstLayer.scale
            preferences[KEY_WALLPAPER_TRANSLATE_X] = firstLayer.translateX
            preferences[KEY_WALLPAPER_TRANSLATE_Y] = firstLayer.translateY
        }

        Timber.d("Saved ${state.layers.size} wallpaper layers")
    }

    /**
     * Speichert Single-Layer State in die Legacy-Keys.
     * Entfernt den Multi-Layer JSON-Key.
     */
    private fun saveSingleLayerState(preferences: MutablePreferences, state: WallpaperState) {
        preferences[KEY_WALLPAPER_URI] = state.imageUri.toString()
        preferences[KEY_WALLPAPER_SCALE] = state.scale
        preferences[KEY_WALLPAPER_TRANSLATE_X] = state.translateX
        preferences[KEY_WALLPAPER_TRANSLATE_Y] = state.translateY

        // Multi-Layer Key entfernen (wir sind im Single-Modus)
        preferences.remove(KEY_LAYERS_JSON)
    }

    // ===========================================
    // WRITE: Clear
    // ===========================================

    override suspend fun clearWallpaper() {
        try {
            dataStore.edit { preferences ->
                removeAllKeys(preferences)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error clearing wallpaper")
        }
    }

    override suspend fun purgeRepository() {
        try {
            dataStore.edit { preferences ->
                removeAllKeys(preferences)
            }
            Timber.d("Wallpaper data purged successfully")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error purging wallpaper data")
        }
    }

    /**
     * Entfernt alle Wallpaper-Keys (Single + Multi).
     */
    private fun removeAllKeys(preferences: MutablePreferences) {
        preferences.remove(KEY_WALLPAPER_URI)
        preferences.remove(KEY_WALLPAPER_SCALE)
        preferences.remove(KEY_WALLPAPER_TRANSLATE_X)
        preferences.remove(KEY_WALLPAPER_TRANSLATE_Y)
        preferences.remove(KEY_LAYERS_JSON)
    }

    // ===========================================
    // JSON SERIALIZATION
    // ===========================================

    /**
     * Serialisiert eine Layer-Liste in ein JSONArray.
     *
     * Format pro Layer:
     * ```json
     * {
     *   "id": "layer_123_0",
     *   "imageUri": "content://...",
     *   "scale": 2.5,
     *   "translateX": -100.0,
     *   "translateY": -50.0,
     *   "alpha": 0.85,
     *   "blendModeName": "MULTIPLY",
     *   "isVisible": true,
     *   "label": "Oben"
     * }
     * ```
     */
    private fun layersToJson(layers: List<WallpaperLayerState>): JSONArray {
        val array = JSONArray()
        for (layer in layers) {
            val obj = JSONObject().apply {
                put("id", layer.id)
                put("imageUri", layer.imageUri?.toString() ?: "")
                put("scale", layer.scale.toDouble())
                put("translateX", layer.translateX.toDouble())
                put("translateY", layer.translateY.toDouble())
                put("alpha", layer.alpha.toDouble())
                put("blendModeName", layer.blendModeName ?: "")
                put("isVisible", layer.isVisible)
                put("label", layer.label ?: "")
            }
            array.put(obj)
        }
        return array
    }

    /**
     * Deserialisiert ein JSON-String in eine Layer-Liste.
     * Fehlerhafte Einträge werden übersprungen (nicht die ganze Liste verwerfen).
     */
    private fun parseLayersFromJson(json: String): List<WallpaperLayerState> {
        val array = JSONArray(json)
        val layers = mutableListOf<WallpaperLayerState>()

        for (i in 0 until array.length()) {
            try {
                val obj = array.getJSONObject(i)

                val uriString = obj.optString("imageUri", "")
                val uri = if (uriString.isNotBlank()) uriString.toUri() else null

                layers.add(
                    WallpaperLayerState(
                        id = obj.optString("id", "layer_restored_$i"),
                        imageUri = uri,
                        scale = obj.optDouble("scale", DEFAULT_SCALE.toDouble()).toFloat(),
                        translateX = obj.optDouble("translateX", DEFAULT_TRANSLATE.toDouble()).toFloat(),
                        translateY = obj.optDouble("translateY", DEFAULT_TRANSLATE.toDouble()).toFloat(),
                        alpha = obj.optDouble("alpha", 1.0).toFloat(),
                        blendModeName = obj.optString("blendModeName", "").takeIf { it.isNotBlank() },
                        isVisible = obj.optBoolean("isVisible", true),
                        label = obj.optString("label", "").takeIf { it.isNotBlank() }
                    )
                )
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error parsing layer at index $i, skipping")
            }
        }

        return layers
    }
}