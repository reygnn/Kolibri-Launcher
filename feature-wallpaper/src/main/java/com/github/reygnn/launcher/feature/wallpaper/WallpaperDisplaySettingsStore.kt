package com.github.reygnn.launcher.feature.wallpaper

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.launcher.common.data.safePurge
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.OwnsSettingsStoreKeys
import com.github.reygnn.launcher.core.Purgeable
import com.github.reygnn.launcher.core.SettingsStore
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The key names of the wallpaper display settings in an app's settings store (3a-4, D3). They
 * stay per app because unifying them would need a data migration: Kolibri keeps its old surface
 * name `app_drawer_mode`, Nyx uses `wallpaper_surface_mode`.
 */
data class WallpaperDisplayKeys(
    val scrimAlpha: String,
    val backdrop: String,
    val surfaceMode: String,
)

/**
 * The wallpaper display settings — scrim, backdrop, surface mode — in the app's settings store
 * (SPEC_NYX_REWRITE 3a-4, Ue7 moved out of Kolibri's `SettingsRepositoryImpl`). One instance per
 * app (`@Singleton`): Kolibri binds `WallpaperDisplaySettings` to it AND delegates its
 * `SettingsRepository` members to it, so both reach the same object.
 *
 * Behaviour-neutral for Kolibri, deliberately (02.10.):
 *  - **Reads** fail open the way Kolibri's settings did: an upstream read error that is an
 *    [Exception] (not a cancellation) is logged with `silentError` (crashes in DEBUG) and falls
 *    back to the defaults. Unifying reads under the house standard `readFlowFailOpen` is open for
 *    3b (type history of the keys first, `app_drawer_mode` especially).
 *  - **Writes** swallow their failure after logging (D4); whether Nyx keeps throwing is decided
 *    in 3b.
 *  - **Purge** rethrows (F1), so the reset reports a partial failure.
 *
 * Keep-list (D6): the store owns its three keys ([ownedExactKeys]). The key properties are
 * UPPER_CASE on purpose, so the keep-list gate checks each of them for registration like a
 * constant key. Known limit: the names themselves come from [WallpaperDisplayKeys], not as
 * literals here, so the gate cannot see the strings — a unit test and the
 * `ResetCompletenessContract` pin them.
 */
@Singleton
class WallpaperDisplaySettingsStore @Inject constructor(
    @param:SettingsStore private val dataStore: DataStore<Preferences>,
    keys: WallpaperDisplayKeys,
) : WallpaperDisplaySettings, Purgeable, OwnsSettingsStoreKeys {

    private val SCRIM_ALPHA = floatPreferencesKey(keys.scrimAlpha)
    private val BACKDROP = stringPreferencesKey(keys.backdrop)
    private val SURFACE_MODE = stringPreferencesKey(keys.surfaceMode)

    override val wallpaperScrimAlphaStateFlow: Flow<Float> =
        safeData.map { it[SCRIM_ALPHA] ?: AppConstants.DEFAULT_WALLPAPER_SCRIM_ALPHA }

    override suspend fun setWallpaperScrimAlpha(alpha: Float) = safeEdit { it[SCRIM_ALPHA] = alpha }

    override val wallpaperSurfaceModeFlow: Flow<WallpaperSurfaceMode> =
        safeData.map { it[SURFACE_MODE].toEnumOr(WallpaperSurfaceMode.AUTO) }

    override suspend fun setWallpaperSurfaceMode(mode: WallpaperSurfaceMode) = safeEdit { it[SURFACE_MODE] = mode.name }

    override val wallpaperBackdropFlow: Flow<WallpaperBackdrop> =
        safeData.map { it[BACKDROP].toEnumOr(WallpaperBackdrop.SYSTEM_WALLPAPER) }

    override suspend fun setWallpaperBackdrop(backdrop: WallpaperBackdrop) = safeEdit { it[BACKDROP] = backdrop.name }

    override fun ownedExactKeys(): Set<String> = setOf(SCRIM_ALPHA.name, BACKDROP.name, SURFACE_MODE.name)

    /** Factory reset: removes the three keys; a failure is rethrown by `safePurge` (F1). */
    override suspend fun purgeRepository() {
        dataStore.safePurge("WallpaperDisplaySettingsStore") { preferences ->
            preferences.remove(SCRIM_ALPHA)
            preferences.remove(BACKDROP)
            preferences.remove(SURFACE_MODE)
        }
    }

    /** Kolibri's read policy: an upstream [Exception] falls back to the defaults (see the KDoc). */
    private val safeData: Flow<Preferences>
        get() = dataStore.data.catch { e ->
            if (e is CancellationException || e !is Exception) throw e
            TimberWrapper.silentError(e, "Wallpaper display settings: read error, falling back to defaults")
            emit(emptyPreferences())
        }

    private suspend fun safeEdit(block: (MutablePreferences) -> Unit) {
        try {
            dataStore.edit(block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Catch kept (Expected error, four-category frame): a setter swallows its failure like
            // Kolibri's settings did (D4); OOM extends Error → Throwable.
            TimberWrapper.silentError(e, "Wallpaper display settings: write error")
        }
    }
}

private inline fun <reified E : Enum<E>> String?.toEnumOr(default: E): E =
    // No suspension point — enum parse of a stored string; an unknown name falls back.
    if (this == null) default else enumValues<E>().firstOrNull { it.name == this } ?: default
