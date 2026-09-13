package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.TimberWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nyx's factory reset (mirrors Kolibri's, adapted to Nyx's much smaller surface).
 *
 * Nyx persists everything in a single `home_layout` DataStore — home layout,
 * settings, wallpaper display settings, FAB position, the wallpaper layer JSON and
 * the first-run seed flag all live there — plus the wallpaper image files on disk.
 * So a full reset is two moves: clear every DataStore key, then delete the wallpaper
 * blobs. No `Purgeable`-per-repository fan-out is needed at this scale.
 *
 * Like Kolibri, this does NOT restart the process or recreate the Activity: clearing
 * the DataStore makes every backing `Flow` re-emit its default, so the reactive UI
 * (home grid, dock, switches, wallpaper) repaints to the default state on its own.
 * Clearing the seed flag means the next cold start re-seeds the default dock.
 */
@Singleton
class NyxResetManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val fileManager: WallpaperFileManager,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** Wipes all persisted state. Returns true on success, false on any failure. */
    suspend fun reset(): Boolean = withContext(ioDispatcher) {
        try {
            dataStore.edit { it.clear() }
            fileManager.clearAll()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error during Nyx factory reset")
            false
        }
    }
}
