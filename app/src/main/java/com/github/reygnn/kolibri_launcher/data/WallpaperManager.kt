package com.github.reygnn.kolibri_launcher.data

import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository für Wallpaper-Einstellungen mit DataStore-Persistenz.
 *
 * Speichert:
 * - Image URI (Content-Provider URI als String)
 * - Scale (Zoom-Faktor)
 * - TranslateX/Y (Pan-Offset)
 *
 * Alle Schreiboperationen sind crash-safe und loggen Fehler still.
 * CancellationException wird immer re-thrown für korrektes Coroutine-Verhalten.
 */
@Singleton
class WallpaperManager @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : WallpaperRepository {

    companion object {
        // Keys für DataStore
        private val KEY_WALLPAPER_URI = stringPreferencesKey("wallpaper_uri")
        private val KEY_WALLPAPER_SCALE = floatPreferencesKey("wallpaper_scale")
        private val KEY_WALLPAPER_TRANSLATE_X = floatPreferencesKey("wallpaper_translate_x")
        private val KEY_WALLPAPER_TRANSLATE_Y = floatPreferencesKey("wallpaper_translate_y")

        // Defaults
        private const val DEFAULT_SCALE = 1.0f
        private const val DEFAULT_TRANSLATE = 0.0f
    }

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
                val uriString = preferences[KEY_WALLPAPER_URI]

                if (uriString.isNullOrBlank()) {
                    WallpaperState.NONE
                } else {
                    WallpaperState(
                        imageUri = uriString.toUri(),
                        scale = preferences[KEY_WALLPAPER_SCALE] ?: DEFAULT_SCALE,
                        translateX = preferences[KEY_WALLPAPER_TRANSLATE_X] ?: DEFAULT_TRANSLATE,
                        translateY = preferences[KEY_WALLPAPER_TRANSLATE_Y] ?: DEFAULT_TRANSLATE
                    )
                }
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error parsing wallpaper state")
                WallpaperState.NONE
            }
        }

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

    override suspend fun saveWallpaperState(state: WallpaperState) {
        try {
            dataStore.edit { preferences ->
                if (state.imageUri != null) {
                    preferences[KEY_WALLPAPER_URI] = state.imageUri.toString()
                    preferences[KEY_WALLPAPER_SCALE] = state.scale
                    preferences[KEY_WALLPAPER_TRANSLATE_X] = state.translateX
                    preferences[KEY_WALLPAPER_TRANSLATE_Y] = state.translateY
                } else {
                    // Kein Wallpaper = alle Keys entfernen
                    preferences.remove(KEY_WALLPAPER_URI)
                    preferences.remove(KEY_WALLPAPER_SCALE)
                    preferences.remove(KEY_WALLPAPER_TRANSLATE_X)
                    preferences.remove(KEY_WALLPAPER_TRANSLATE_Y)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error saving wallpaper state")
        }
    }

    override suspend fun clearWallpaper() {
        try {
            dataStore.edit { preferences ->
                preferences.remove(KEY_WALLPAPER_URI)
                preferences.remove(KEY_WALLPAPER_SCALE)
                preferences.remove(KEY_WALLPAPER_TRANSLATE_X)
                preferences.remove(KEY_WALLPAPER_TRANSLATE_Y)
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
                preferences.remove(KEY_WALLPAPER_URI)
                preferences.remove(KEY_WALLPAPER_SCALE)
                preferences.remove(KEY_WALLPAPER_TRANSLATE_X)
                preferences.remove(KEY_WALLPAPER_TRANSLATE_Y)
            }
            Timber.d("Wallpaper data purged successfully")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error purging wallpaper data")
        }
    }
}