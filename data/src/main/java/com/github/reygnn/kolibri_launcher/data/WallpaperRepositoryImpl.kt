package com.github.reygnn.kolibri_launcher.data

import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository für Wallpaper-Einstellungen mit DataStore-Persistenz.
 *
 * == BACKWARD COMPATIBILITY ==
 * Bestehende Single-Layer Daten (KEY_WALLPAPER_URI etc.) werden beim Lesen
 * automatisch erkannt und als WallpaperState ohne Layer-Liste geladen.
 * Beim Multi-Layer-Speichern werden die Legacy-Keys mit den Werten von
 * Layer 0 synchronisiert — als Notbett für den Korruptions-Fallback in
 * [parseWallpaperState], wenn LAYERS_JSON unparsbar zurückkommt.
 *
 * == MULTI-LAYER ==
 * Layer werden als JSON-Array in einem einzigen DataStore-Key gespeichert.
 * Jedes Layer enthält: id, imageUri, scale, translateX/Y, alpha, blendMode,
 * isVisible, label.
 *
 * Migrations- und Fallback-Pfade:
 * 1. App-Start: Alte Keys vorhanden, kein LAYERS_JSON → Single-Layer (wie bisher)
 * 2. User fügt Layer hinzu → LAYERS_JSON wird geschrieben, Legacy-Keys
 *    werden mit Layer 0 synchronisiert
 * 3. Nächster App-Start: LAYERS_JSON vorhanden → Multi-Layer
 * 4. User entfernt alle Layer → LAYERS_JSON wird entfernt, zurück zu Single/None
 * 5. Korruptions-Fallback: LAYERS_JSON existiert, ist aber unparsbar →
 *    Repository fällt auf die Layer-0-Synchronisation in den Legacy-Keys
 *    zurück. User sieht in dem Fall nur Layer 0 statt der vollen Komposition.
 */
@Singleton
class WallpaperRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val wallpaperFileManager: WallpaperFileManager
) : WallpaperRepository {

    companion object {
        // --- Legacy Keys (Single-Layer, beibehalten für Migration) ---
        private val KEY_WALLPAPER_URI = stringPreferencesKey("wallpaper_uri")
        private val KEY_WALLPAPER_SCALE = floatPreferencesKey("wallpaper_scale")
        private val KEY_WALLPAPER_TRANSLATE_X = floatPreferencesKey("wallpaper_translate_x")
        private val KEY_WALLPAPER_TRANSLATE_Y = floatPreferencesKey("wallpaper_translate_y")

        // Decode downsample factor the single-layer transform was captured at
        // (WALLPAPER_RENDER_RES_SPEC §4-Y). Absent = legacy field-less transform.
        private val KEY_WALLPAPER_CAPTURE_SAMPLE_SIZE =
            intPreferencesKey("wallpaper_capture_sample_size")

        // --- Multi-Layer Key ---
        private val KEY_LAYERS_JSON = stringPreferencesKey("wallpaper_layers_json")

        // Defaults
        private const val DEFAULT_SCALE = 1.0f
        private const val DEFAULT_TRANSLATE = 0.0f
    }

    // ===========================================
    // READ: Flow<WallpaperState>
    // ===========================================

    override val wallpaperState: Flow<WallpaperState> =
        dataStore.safeReadFlow("Error reading wallpaper preferences")
        .map { preferences ->
            try {
                parseWallpaperState(preferences)
            } catch (e: Throwable) {
                // No suspension point: guarded body is synchronous today; if a call here becomes suspend, switch to a CancellationException rethrow arm (AUDIT-12 whitelist review).
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

        if (!layersJson.isNullOrBlank()) {
            return try {
                val layers = parseLayersFromJson(layersJson)
                if (layers.isNotEmpty()) {
                    val validLayers = layers.filter { layer ->
                        val uriString = layer.imageUri
                        // A layer without a URI shows nothing — keeping it would
                        // create a state that is technically multi-layer but
                        // displays no wallpaper (isMultiLayer && !hasWallpaper).
                        if (uriString == null) {
                            Timber.w("Dropping layer without image URI (id='${layer.id}')")
                            return@filter false
                        }
                        val uri = uriString.toUri()
                        // Defensive: non-file URIs should not exist in the
                        // state (copyToInternal converts everything to file://
                        // before persistence). If one slips through — old
                        // version, bad restore, remapped volume — drop the
                        // layer rather than crashing at setImageURI.
                        if (uri.scheme != "file") {
                            Timber.w(
                                "Dropping layer with non-file URI (scheme='${uri.scheme}', " +
                                        "id='${layer.id}') — likely old-version or restore artifact"
                            )
                            return@filter false
                        }
                        wallpaperFileManager.fileExists(uri)
                    }
                    if (validLayers.isEmpty()) {
                        Timber.w("All layer files missing — resetting wallpaper")
                        WallpaperState.NONE
                    } else {
                        if (validLayers.size < layers.size) {
                            Timber.w(
                                "${layers.size - validLayers.size} layer file(s) missing — " +
                                        "removed from state (files not found on disk)"
                            )
                        }
                        WallpaperState(layers = validLayers)
                    }
                } else {
                    parseSingleLayerState(preferences)
                }
            } catch (e: Throwable) {
                // No suspension point: guarded body is synchronous today; if a call here becomes suspend, switch to a CancellationException rethrow arm (AUDIT-12 whitelist review).
                // Highlight this case more visibly: the user may have configured
                // several layers and will now suddenly see only one (or none).
                // Logging here is non-fatal but helps post-mortem diagnosis.
                Timber.w(
                    e,
                    "Multi-layer wallpaper state corrupted — falling back to single-layer. " +
                            "User may see only Layer 0 of their previous composition."
                )
                TimberWrapper.silentError(e, "Error parsing layers JSON, falling back to single-layer")
                parseSingleLayerState(preferences)
            }
        }

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
            val uri = uriString.toUri()
            // Defensive: we always convert to file:// via copyToInternal. A
            // content:// URI reaching persistence points to either a very
            // old app version, an out-of-band restore, or a volume rename
            // (e.g. `content://media/external_primary/...` after SD-card
            // remap). Rendering these often throws at setImageURI time,
            // so treat them as "no wallpaper" and let the user re-pick.
            if (uri.scheme != "file") {
                Timber.w(
                    "Non-file wallpaper URI in state (scheme='${uri.scheme}') — " +
                            "resetting. Likely an old-version leftover or restored backup."
                )
                return WallpaperState.NONE
            }
            if (!wallpaperFileManager.fileExists(uri)) {
                Timber.w("Wallpaper file missing: $uri — resetting")
                return WallpaperState.NONE
            }
            WallpaperState(
                imageUri = uriString,
                scale = preferences[KEY_WALLPAPER_SCALE] ?: DEFAULT_SCALE,
                translateX = preferences[KEY_WALLPAPER_TRANSLATE_X] ?: DEFAULT_TRANSLATE,
                translateY = preferences[KEY_WALLPAPER_TRANSLATE_Y] ?: DEFAULT_TRANSLATE,
                captureSampleSize = preferences[KEY_WALLPAPER_CAPTURE_SAMPLE_SIZE]
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

        // Legacy-Keys mit Layer 0 synchronisieren (Korruptions-Fallback).
        val firstLayer = state.layers.firstOrNull { it.hasImage }
        val firstLayerUri = firstLayer?.imageUri
        if (firstLayer != null && firstLayerUri != null) {
            preferences[KEY_WALLPAPER_URI] = firstLayerUri
            preferences[KEY_WALLPAPER_SCALE] = firstLayer.scale
            preferences[KEY_WALLPAPER_TRANSLATE_X] = firstLayer.translateX
            preferences[KEY_WALLPAPER_TRANSLATE_Y] = firstLayer.translateY
            firstLayer.captureSampleSize
                ?.let { preferences[KEY_WALLPAPER_CAPTURE_SAMPLE_SIZE] = it }
                ?: preferences.remove(KEY_WALLPAPER_CAPTURE_SAMPLE_SIZE)
        } else {
            // Kein Layer hat ein Bild → Legacy-Keys räumen, sonst würde
            // ein späterer Korruptions-Fallback in parseWallpaperState
            // ein altes Single-Layer-Wallpaper aus einer längst beendeten
            // Konfiguration zurückbringen.
            preferences.remove(KEY_WALLPAPER_URI)
            preferences.remove(KEY_WALLPAPER_SCALE)
            preferences.remove(KEY_WALLPAPER_TRANSLATE_X)
            preferences.remove(KEY_WALLPAPER_TRANSLATE_Y)
            preferences.remove(KEY_WALLPAPER_CAPTURE_SAMPLE_SIZE)
        }

        Timber.d("Saved ${state.layers.size} wallpaper layers")
    }

    /**
     * Speichert Single-Layer State in die Legacy-Keys.
     * Entfernt den Multi-Layer JSON-Key.
     */
    private fun saveSingleLayerState(preferences: MutablePreferences, state: WallpaperState) {
        // saveWallpaperState only routes here when state.imageUri != null.
        preferences[KEY_WALLPAPER_URI] = state.imageUri ?: ""
        preferences[KEY_WALLPAPER_SCALE] = state.scale
        preferences[KEY_WALLPAPER_TRANSLATE_X] = state.translateX
        preferences[KEY_WALLPAPER_TRANSLATE_Y] = state.translateY
        state.captureSampleSize
            ?.let { preferences[KEY_WALLPAPER_CAPTURE_SAMPLE_SIZE] = it }
            ?: preferences.remove(KEY_WALLPAPER_CAPTURE_SAMPLE_SIZE)

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
        dataStore.safePurge("WallpaperRepositoryImpl") { preferences ->
            removeAllKeys(preferences)
        }
        // Delete the on-disk wallpaper images as well, not just the DataStore
        // keys. Otherwise a factory reset leaves orphaned files in
        // filesDir/wallpapers/ until the next cold-start gcOrphans sweep (a
        // 60s-cutoff, best-effort net — not a prompt guarantee). This is the
        // Factory Reset caller clearAll()'s KDoc already documents. IO-wrapped
        // because clearAll() does blocking file deletion.
        withContext(Dispatchers.IO) {
            wallpaperFileManager.clearAll()
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
        preferences.remove(KEY_WALLPAPER_CAPTURE_SAMPLE_SIZE)
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
                put("imageUri", layer.imageUri ?: "")
                put("scale", layer.scale.toDouble())
                put("translateX", layer.translateX.toDouble())
                put("translateY", layer.translateY.toDouble())
                // -1 sentinel = absent (JSON has no null int here); read back as
                // null via takeIf { it > 0 } — a legacy field-less transform.
                put("captureSampleSize", layer.captureSampleSize ?: -1)
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

                val uriString = obj.optString("imageUri", "").takeIf { it.isNotBlank() }

                layers.add(
                    WallpaperLayerState(
                        id = obj.optString("id", "layer_restored_$i"),
                        imageUri = uriString,
                        scale = obj.optDouble("scale", DEFAULT_SCALE.toDouble()).toFloat(),
                        translateX = obj.optDouble("translateX", DEFAULT_TRANSLATE.toDouble()).toFloat(),
                        translateY = obj.optDouble("translateY", DEFAULT_TRANSLATE.toDouble()).toFloat(),
                        captureSampleSize = obj.optInt("captureSampleSize", -1).takeIf { it > 0 },
                        alpha = obj.optDouble("alpha", 1.0).toFloat(),
                        blendModeName = obj.optString("blendModeName", "").takeIf { it.isNotBlank() },
                        isVisible = obj.optBoolean("isVisible", true),
                        label = obj.optString("label", "").takeIf { it.isNotBlank() }
                    )
                )
            } catch (e: Throwable) {
                // No suspension point: guarded body is synchronous today; if a call here becomes suspend, switch to a CancellationException rethrow arm (AUDIT-12 whitelist review).
                TimberWrapper.silentError(e, "Error parsing layer at index $i, skipping")
            }
        }

        return layers
    }
}
