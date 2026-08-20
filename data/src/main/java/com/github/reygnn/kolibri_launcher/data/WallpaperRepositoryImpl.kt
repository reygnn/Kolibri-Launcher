package com.github.reygnn.kolibri_launcher.data

import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.core.IoDispatcher
import com.github.reygnn.kolibri_launcher.core.OwnsSettingsStoreKeys
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for wallpaper settings with DataStore persistence.
 *
 * A wallpaper is persisted as a single JSON array under [KEY_LAYERS_JSON].
 * Each element carries: id, imageUri, scale, translateX/Y, captureSampleSize.
 * A single-image wallpaper is a one-element array; two-plus layers composite.
 *
 * The legacy flat single-layer keys (`wallpaper_uri` etc.) were dropped when
 * the flat [WallpaperState] representation was removed. There is NO in-code
 * migration (project Rule 5): an existing single-image wallpaper stored under
 * those keys is not read back — the sanctioned path across the breaking change
 * is export → factory reset → restore, and the restore importer still reads the
 * old flat backup fields (see `BackupRepositoryImpl.importSingleLayerWallpaper`).
 * The now-unowned legacy keys are swept as orphans by the storage-cleanup.
 *
 * On a JSON that is present but unparsable, the read falls back to
 * [WallpaperState.NONE] (no partial single-layer recovery anymore).
 */
@Singleton
class WallpaperRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val wallpaperFileManager: WallpaperFileManager,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : WallpaperRepository, OwnsSettingsStoreKeys {

    override fun ownedExactKeys(): Set<String> = setOf(
        KEY_LAYERS_JSON.name,
    )

    companion object {
        // The single wallpaper key: a JSON array of layers.
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
        // Project to only the wallpaper keys and dedup BEFORE the parse
        // (AUDIT-19 F2). parseWallpaperState does a JSON parse plus a per-layer
        // fileExists() disk stat; the shared settings DataStore re-emits the
        // whole Preferences on ANY write (a favorite toggle, a colour change,
        // …), so without this projection every unrelated write re-ran that
        // parse + N stats for an unchanged wallpaper — the one sibling flow
        // that missed the AUDIT-14 projection+distinct fix. A trailing distinct
        // would not help: it dedups the output but the parse+stat already ran.
        .map { preferences -> preferences.filterToWallpaperKeys() }
        .distinctUntilChanged()
        .map { wallpaperPreferences ->
            try {
                parseWallpaperState(wallpaperPreferences)
            } catch (e: Throwable) {
                // No suspension point: guarded body is synchronous today; if a call here becomes suspend, switch to a CancellationException rethrow arm (AUDIT-12 whitelist review).
                TimberWrapper.silentError(e, "Error parsing wallpaper state")
                WallpaperState.NONE
            }
        }
        // parseWallpaperState does a per-layer fileExists() disk stat (File.exists,
        // a stat() syscall). The sole collector (WallpaperDelegate.start) runs on
        // the main dispatcher (viewModelScope), so without this the stat ran on
        // Main — a StrictMode DiskReadViolation the delegate already avoids for its
        // own disk I/O (gcOrphans, getDisplayName both hop to ioDispatcher). flowOn
        // moves the whole transform (filter + distinct + JSON parse + stats) off
        // Main; it introduces no suspension into the map lambdas, so the catch arms
        // above stay non-suspend (their "no suspension point" markers remain valid).
        .flowOn(ioDispatcher)

    /**
     * The subset of [this] holding only the wallpaper keys (AUDIT-19 F2). Gates
     * [wallpaperState] via `distinctUntilChanged` so the parse + per-layer
     * `fileExists()` stat runs only when a wallpaper key actually changed, not
     * on every unrelated write to the shared settings store. Typed per key —
     * no unchecked generic copy. [parseWallpaperState] reads only these keys,
     * so it works on the filtered subset identically.
     */
    private fun Preferences.filterToWallpaperKeys(): Preferences {
        val out = mutablePreferencesOf()
        this[KEY_LAYERS_JSON]?.let { out[KEY_LAYERS_JSON] = it }
        return out.toPreferences()
    }

    /**
     * Parses the [WallpaperState] from the layers JSON. Empty/absent JSON is
     * [WallpaperState.NONE]; an unparsable JSON also collapses to NONE (no
     * partial single-layer recovery — the legacy flat keys are gone).
     */
    private fun parseWallpaperState(preferences: Preferences): WallpaperState {
        val layersJson = preferences[KEY_LAYERS_JSON]
        if (layersJson.isNullOrBlank()) return WallpaperState.NONE

        return try {
            val layers = parseLayersFromJson(layersJson)
            val validLayers = layers.filter { layer ->
                val uriString = layer.imageUri
                // A layer without a URI shows nothing — keeping it would create a
                // state that has layers but displays no wallpaper.
                if (uriString == null) {
                    Timber.w("Dropping layer without image URI (id='${layer.id}')")
                    return@filter false
                }
                val uri = uriString.toUri()
                // Defensive: non-file URIs should not exist in the state
                // (copyToInternal converts everything to file:// before
                // persistence). If one slips through — old version, bad restore,
                // remapped volume — drop the layer rather than crashing at
                // setImageURI.
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
                Timber.w("No valid layer files — resetting wallpaper")
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
        } catch (e: Throwable) {
            // No suspension point: guarded body is synchronous today; if a call here becomes suspend, switch to a CancellationException rethrow arm (AUDIT-12 whitelist review).
            // The user may have configured several layers and will now see none.
            // Logging here is non-fatal but helps post-mortem diagnosis.
            TimberWrapper.silentError(e, "Error parsing layers JSON — resetting wallpaper")
            WallpaperState.NONE
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
                if (state.hasWallpaper) {
                    preferences[KEY_LAYERS_JSON] = layersToJson(state.layers).toString()
                    Timber.d("Saved ${state.layers.size} wallpaper layer(s)")
                } else {
                    // No wallpaper → remove the key.
                    preferences.remove(KEY_LAYERS_JSON)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error saving wallpaper state")
        }
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
        withContext(ioDispatcher) {
            wallpaperFileManager.clearAll()
        }
    }

    /** Removes the wallpaper key. */
    private fun removeAllKeys(preferences: MutablePreferences) {
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
