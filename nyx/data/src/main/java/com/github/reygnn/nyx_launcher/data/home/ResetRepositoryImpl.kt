package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.Purgeable
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.nyx_launcher.home.repository.AppUsageRepository
import com.github.reygnn.nyx_launcher.home.repository.DrawerFoldersRepository
import com.github.reygnn.nyx_launcher.home.repository.HiddenAppsRepository
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import com.github.reygnn.nyx_launcher.home.repository.ResetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nyx's factory reset (2b-4c, step 3): every store purges its own keys (`Purgeable`), with
 * Kolibri's mechanism — one store after the other, a failing store logged and counted, never
 * stopping the rest. The scope is exactly the one of the former `NyxResetManager` (the
 * `home_layout` DataStore, `nyx_usage`, the wallpaper files); the crash-report consent in
 * `acra_consent` stays untouched. `NyxResetCompletenessTest` proves it deletes as much as the
 * former single `clear()`.
 *
 * Like Kolibri, this does NOT restart the process: every store's flow re-emits its default, so
 * the reactive UI repaints on its own. The settings seed the default dock and folders again after
 * every reset (R2); that is safe because each data key and its seed flag go in the same edit.
 */
@Singleton
class ResetRepositoryImpl @Inject constructor(
    private val homeLayoutRepository: HomeLayoutRepository,
    private val drawerFoldersRepository: DrawerFoldersRepository,
    private val hiddenAppsRepository: HiddenAppsRepository,
    private val preferencesRepository: PreferencesRepository,
    private val wallpaperDisplaySettings: NyxWallpaperDisplaySettings,
    private val fabPositionStore: NyxFabPositionStore,
    private val wallpaperRepository: WallpaperRepository,
    private val appUsageRepository: AppUsageRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ResetRepository {

    override suspend fun factoryReset(): Boolean = withContext(ioDispatcher) {
        purgeAll(
            listOf(
                "preferences" to preferencesRepository,
                "wallpaper display settings" to wallpaperDisplaySettings,
                "FAB position" to fabPositionStore,
                "hidden apps" to hiddenAppsRepository,
                "drawer folders" to drawerFoldersRepository,
                "home layout" to homeLayoutRepository,
                // The layers key and the image files (R4); the in-memory layer cache follows the
                // NONE state in MainActivity.
                "wallpaper" to wallpaperRepository,
                // Usage lives in its OWN DataStore (nyx_usage).
                "app usage" to appUsageRepository,
            ),
        )
    }

    /** Purges every store in order, each in isolation; true only if every purge succeeded. */
    private suspend fun purgeAll(stores: List<Pair<String, Purgeable>>): Boolean {
        var allSuccessful = true
        for ((name, store) in stores) {
            try {
                store.purgeRepository()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Catch kept (Expected error, four-category frame): one failing store must not stop
                // the others; OOM extends Error → Throwable.
                TimberWrapper.silentError(e, "Nyx factory reset: purging $name failed")
                allSuccessful = false
            }
        }
        return allSuccessful
    }
}
