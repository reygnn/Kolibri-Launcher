package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SharedPreferences-basierte Implementierung des WallpaperRepository.
 *
 * Speichert:
 * - Image URI als String
 * - Scale, TranslateX, TranslateY als Float
 *
 * Thread-Safety: Alle Writes auf Dispatchers.IO
 */
@Singleton
class WallpaperManager @Inject constructor(
    @ApplicationContext private val context: Context
) : WallpaperRepository {

    companion object {
        private const val PREFS_NAME = "kolibri_wallpaper_prefs"
        private const val KEY_IMAGE_URI = "wallpaper_image_uri"
        private const val KEY_SCALE = "wallpaper_scale"
        private const val KEY_TRANSLATE_X = "wallpaper_translate_x"
        private const val KEY_TRANSLATE_Y = "wallpaper_translate_y"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // =========================================================================
    // REACTIVE STREAM
    // =========================================================================

    override val wallpaperState: Flow<WallpaperState> = callbackFlow {
        // Initial emit
        trySend(loadFromPrefs())

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in listOf(KEY_IMAGE_URI, KEY_SCALE, KEY_TRANSLATE_X, KEY_TRANSLATE_Y)) {
                trySend(loadFromPrefs())
            }
        }

        prefs.registerOnSharedPreferenceChangeListener(listener)

        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.distinctUntilChanged()

    // =========================================================================
    // WRITE OPERATIONS
    // =========================================================================

    override suspend fun saveWallpaperState(state: WallpaperState) {
        withContext(Dispatchers.IO) {
            try {
                val persistState = state.forPersistence()

                prefs.edit().apply {
                    if (persistState.imageUri != null) {
                        putString(KEY_IMAGE_URI, persistState.imageUri.toString())
                    } else {
                        remove(KEY_IMAGE_URI)
                    }
                    putFloat(KEY_SCALE, persistState.scale)
                    putFloat(KEY_TRANSLATE_X, persistState.translateX)
                    putFloat(KEY_TRANSLATE_Y, persistState.translateY)
                    apply()
                }
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error saving wallpaper state")
            }
        }
    }

    override suspend fun clearWallpaper() {
        withContext(Dispatchers.IO) {
            try {
                prefs.edit().apply {
                    remove(KEY_IMAGE_URI)
                    putFloat(KEY_SCALE, WallpaperState.DEFAULT_SCALE)
                    putFloat(KEY_TRANSLATE_X, 0f)
                    putFloat(KEY_TRANSLATE_Y, 0f)
                    apply()
                }
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error clearing wallpaper")
            }
        }
    }

    // =========================================================================
    // READ OPERATIONS
    // =========================================================================

    override fun getWallpaperStateSync(): WallpaperState {
        return try {
            loadFromPrefs()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error loading wallpaper state sync")
            WallpaperState.NONE
        }
    }

    // =========================================================================
    // INTERNAL
    // =========================================================================

    private fun loadFromPrefs(): WallpaperState {
        return try {
            val uriString = prefs.getString(KEY_IMAGE_URI, null)
            val uri = uriString?.let {
                try {
                    Uri.parse(it)
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error parsing wallpaper URI")
                    null
                }
            }

            WallpaperState(
                imageUri = uri,
                scale = prefs.getFloat(KEY_SCALE, WallpaperState.DEFAULT_SCALE),
                translateX = prefs.getFloat(KEY_TRANSLATE_X, 0f),
                translateY = prefs.getFloat(KEY_TRANSLATE_Y, 0f),
                isEditMode = false
            )
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error loading wallpaper from prefs")
            WallpaperState.NONE
        }
    }
}