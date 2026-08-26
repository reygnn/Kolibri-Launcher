package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.OwnsSettingsStoreKeys
import com.github.reygnn.kolibri_launcher.core.toEnumOrNull
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.coerceInSafe
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperBackdrop
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperSurfaceMode
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : SettingsRepository, OwnsSettingsStoreKeys {

    /**
     * Definition der DataStore Keys.
     * Die Strings entsprechen den Keys, die auch in der settings.xml oder intern verwendet werden.
     */
    private object PreferenceKeys {
        // String Keys
        val SORT_ORDER_KEY = stringPreferencesKey(AppConstants.PrefKeys.SORT_ORDER)
        val FAVORITES_ALIGNMENT = stringPreferencesKey(AppConstants.PrefKeys.FAVORITES_ALIGNMENT)
        val APP_DRAWER_MODE = stringPreferencesKey(AppConstants.PrefKeys.APP_DRAWER_MODE)
        val WALLPAPER_BACKDROP = stringPreferencesKey(AppConstants.PrefKeys.WALLPAPER_BACKDROP)

        // Boolean Keys
        val ONBOARDING_COMPLETED = booleanPreferencesKey(AppConstants.PrefKeys.ONBOARDING_COMPLETED)
        val TEXT_SHADOW_ENABLED = booleanPreferencesKey(AppConstants.PrefKeys.TEXT_SHADOW_ENABLED)
        val IS_FONT_BOLD = booleanPreferencesKey(AppConstants.PrefKeys.IS_FONT_BOLD)
        val SHOW_CALENDAR_EVENT = booleanPreferencesKey(AppConstants.PrefKeys.SHOW_CALENDAR_EVENT)
        val SHOW_ALARM = booleanPreferencesKey(AppConstants.PrefKeys.SHOW_ALARM)
        val AUTO_SHOW_KEYBOARD = booleanPreferencesKey(AppConstants.PrefKeys.AUTO_SHOW_KEYBOARD)
        val AUTO_LAUNCH_APP = booleanPreferencesKey(AppConstants.PrefKeys.AUTO_LAUNCH_APP)
        val ROTATION_LOCKED = booleanPreferencesKey(AppConstants.PrefKeys.ROTATION_LOCKED)


        // Int Keys
        val TEXT_COLOR = intPreferencesKey(AppConstants.PrefKeys.TEXT_COLOR)

        // Float Keys
        val LAYOUT_SCALE = floatPreferencesKey(AppConstants.PrefKeys.LAYOUT_SCALE)
        val WALLPAPER_SCRIM_ALPHA =
            floatPreferencesKey(AppConstants.PrefKeys.WALLPAPER_SCRIM_ALPHA)
        val VERTICAL_PADDING_SCALE =
            floatPreferencesKey(AppConstants.PrefKeys.VERTICAL_PADDING_SCALE)
        val CONTENT_TOP_MARGIN_SCALE =
            floatPreferencesKey(AppConstants.PrefKeys.CONTENT_TOP_MARGIN_SCALE)
    }

    // Keep-list for storage cleanup (OwnsSettingsStoreKeys): EVERY declared key
    // above, ONBOARDING_COMPLETED included — it is exempt from a factory reset
    // (purgeRepository) but is still a LIVE key, so the cleanup must never delete
    // it. Sourced straight from the key objects; the checkConventions linter
    // pins this list against PreferenceKeys so a new key can't be forgotten here.
    override fun ownedExactKeys(): Set<String> = setOf(
        PreferenceKeys.SORT_ORDER_KEY.name,
        PreferenceKeys.FAVORITES_ALIGNMENT.name,
        PreferenceKeys.APP_DRAWER_MODE.name,
        PreferenceKeys.WALLPAPER_BACKDROP.name,
        PreferenceKeys.ONBOARDING_COMPLETED.name,
        PreferenceKeys.TEXT_SHADOW_ENABLED.name,
        PreferenceKeys.IS_FONT_BOLD.name,
        PreferenceKeys.SHOW_CALENDAR_EVENT.name,
        PreferenceKeys.SHOW_ALARM.name,
        PreferenceKeys.AUTO_SHOW_KEYBOARD.name,
        PreferenceKeys.AUTO_LAUNCH_APP.name,
        PreferenceKeys.ROTATION_LOCKED.name,
        PreferenceKeys.TEXT_COLOR.name,
        PreferenceKeys.LAYOUT_SCALE.name,
        PreferenceKeys.WALLPAPER_SCRIM_ALPHA.name,
        PreferenceKeys.VERTICAL_PADDING_SCALE.name,
        PreferenceKeys.CONTENT_TOP_MARGIN_SCALE.name,
    )

    // --- HELPER ---

    /**
     * Schützt vor DataStore-Korruption beim Lesen.
     * Falls die Datei kaputt ist, wird ein leeres Preferences-Objekt emittiert,
     * was dazu führt, dass unten alle Defaults greifen.
     *
     * Deliberately broader than the shared [safeReadFlow] policy: settings reads
     * must also survive corrupted-type reads (`ClassCastException`) and other
     * read `RuntimeException`s by falling back to defaults — see the "doomsday"
     * tests. The recovery is scoped to [Exception]: [CancellationException] and
     * non-Exception [Throwable]s (e.g. `OutOfMemoryError`) are re-thrown so
     * cancellation and fatal `Error`s still propagate, matching the pre-AUDIT-7
     * behaviour. The real AUDIT-7 #1 bug was only that the previous
     * `if (e is Exception)` form also swallowed `CancellationException`; the
     * broad Exception recovery itself is intended, not drift.
     */
    private val Flow<Preferences>.safeData: Flow<Preferences>
        get() = this.catch { e ->
            if (e is CancellationException || e !is Exception) throw e
            TimberWrapper.silentError(
                e,
                "SettingsRepository: read error, falling back to empty prefs"
            )
            emit(emptyPreferences())
        }

    /**
     * Schützt vor Fehlern beim Schreiben und reduziert Boilerplate.
     * Re-throwt CancellationException korrekt für Coroutines.
     */
    private suspend fun safeEdit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        try {
            dataStore.edit(block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating settings in DataStore")
        }
    }

    // --- ACCESSOR HELPERS ---
    // Every typed setting getter is `safeData.map { it[KEY] ?: DEFAULT }` and
    // every setter is `safeEdit { it[KEY] = value }`. These fold the ~19
    // near-identical getter/setter pairs (AUDIT-7 #3) so each setting below
    // reads as one line; only key + default differ.

    /** Reads [key] from the recovery-guarded [safeData] flow, falling back to [default] when unset. */
    private fun <V : Any> valueFlow(key: Preferences.Key<V>, default: V): Flow<V> =
        dataStore.data.safeData.map { it[key] ?: default }

    /**
     * Enum variant of [valueFlow]: falls back to [default] both when the key is
     * unset and when the persisted name matches no constant of [T] (an unknown
     * or removed enum value) — see [toEnumOrNull].
     */
    private inline fun <reified T : Enum<T>> enumFlow(
        key: Preferences.Key<String>,
        default: T,
    ): Flow<T> = dataStore.data.safeData.map { it[key].toEnumOrNull<T>() ?: default }

    /** Writes [value] under [key] via the error-guarded [safeEdit]. */
    private suspend fun <V : Any> putValue(key: Preferences.Key<V>, value: V) =
        safeEdit { it[key] = value }

    // --- IMPLEMENTATION ---

    // distinctUntilChanged only on sortOrderFlow (NOT the shared valueFlow/enumFlow
    // helper): this is the one settings flow that drives the drawer combine on the
    // hot tap-to-launch path, where the shared store's per-write re-emission causes
    // a redundant full re-sort. Other settings flows are UI-cheap; leaving the
    // helper untouched keeps the blast radius to this key (AUDIT-14 F2).
    override val sortOrderFlow: Flow<SortOrder> =
        enumFlow(PreferenceKeys.SORT_ORDER_KEY, AppConstants.DEFAULT_SORT_ORDER)
            .distinctUntilChanged()

    override suspend fun setSortOrder(sortOrder: SortOrder) =
        putValue(PreferenceKeys.SORT_ORDER_KEY, sortOrder.name)

    override val onboardingCompletedFlow: Flow<Boolean> =
        valueFlow(PreferenceKeys.ONBOARDING_COMPLETED, false)

    override suspend fun setOnboardingCompleted() =
        putValue(PreferenceKeys.ONBOARDING_COMPLETED, true)

    override val textShadowEnabledFlow: Flow<Boolean> =
        valueFlow(PreferenceKeys.TEXT_SHADOW_ENABLED, AppConstants.DEFAULT_TEXT_SHADOW_ENABLED)

    override suspend fun setTextShadowEnabled(isEnabled: Boolean) =
        putValue(PreferenceKeys.TEXT_SHADOW_ENABLED, isEnabled)

    override val textColorFlow: Flow<Int> =
        valueFlow(PreferenceKeys.TEXT_COLOR, AppConstants.DEFAULT_TEXT_COLOR)

    override suspend fun setTextColor(color: Int) =
        putValue(PreferenceKeys.TEXT_COLOR, color)

    override val showCalendarEventFlow: Flow<Boolean> =
        valueFlow(PreferenceKeys.SHOW_CALENDAR_EVENT, AppConstants.DEFAULT_SHOW_CALENDAR)

    override suspend fun setShowCalendarEvent(isEnabled: Boolean) =
        putValue(PreferenceKeys.SHOW_CALENDAR_EVENT, isEnabled)

    override val showAlarmFlow: Flow<Boolean> =
        valueFlow(PreferenceKeys.SHOW_ALARM, AppConstants.DEFAULT_SHOW_ALARM)

    override suspend fun setShowAlarm(isEnabled: Boolean) =
        putValue(PreferenceKeys.SHOW_ALARM, isEnabled)

    override val autoShowKeyboardFlow: Flow<Boolean> =
        valueFlow(PreferenceKeys.AUTO_SHOW_KEYBOARD, AppConstants.DEFAULT_AUTO_SHOW_KEYBOARD)

    override suspend fun setAutoShowKeyboard(isEnabled: Boolean) =
        putValue(PreferenceKeys.AUTO_SHOW_KEYBOARD, isEnabled)

    override val autoLaunchAppFlow: Flow<Boolean> =
        valueFlow(PreferenceKeys.AUTO_LAUNCH_APP, AppConstants.DEFAULT_AUTO_LAUNCH_APP)

    override suspend fun setAutoLaunchApp(isEnabled: Boolean) =
        putValue(PreferenceKeys.AUTO_LAUNCH_APP, isEnabled)

    override val layoutScaleStateFlow: Flow<Float> =
        valueFlow(PreferenceKeys.LAYOUT_SCALE, AppConstants.DEFAULT_LAYOUT_SCALE)

    override suspend fun setLayoutScale(scale: Float) =
        putValue(PreferenceKeys.LAYOUT_SCALE, scale)

    override val wallpaperScrimAlphaStateFlow: Flow<Float> =
        valueFlow(PreferenceKeys.WALLPAPER_SCRIM_ALPHA, AppConstants.DEFAULT_WALLPAPER_SCRIM_ALPHA)

    override suspend fun setWallpaperScrimAlpha(alpha: Float) =
        putValue(PreferenceKeys.WALLPAPER_SCRIM_ALPHA, alpha)

    override val verticalPaddingStateFlow: Flow<Float> =
        valueFlow(PreferenceKeys.VERTICAL_PADDING_SCALE, AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)

    override suspend fun setVerticalPadding(scale: Float) =
        putValue(PreferenceKeys.VERTICAL_PADDING_SCALE, scale)

    override val isFontBoldStateFlow: Flow<Boolean> =
        valueFlow(PreferenceKeys.IS_FONT_BOLD, AppConstants.DEFAULT_FONT_BOLD)

    override suspend fun setFontBold(isBold: Boolean) =
        putValue(PreferenceKeys.IS_FONT_BOLD, isBold)

    override val favoritesAlignmentFlow: Flow<FavoritesAlignment> =
        enumFlow(PreferenceKeys.FAVORITES_ALIGNMENT, AppConstants.DEFAULT_FAVORITES_ALIGNMENT)

    override suspend fun setFavoritesAlignment(alignment: FavoritesAlignment) =
        putValue(PreferenceKeys.FAVORITES_ALIGNMENT, alignment.name)

    override val wallpaperSurfaceModeFlow: Flow<WallpaperSurfaceMode> =
        enumFlow(PreferenceKeys.APP_DRAWER_MODE, AppConstants.DEFAULT_WALLPAPER_SURFACE_MODE)

    override suspend fun setWallpaperSurfaceMode(mode: WallpaperSurfaceMode) =
        putValue(PreferenceKeys.APP_DRAWER_MODE, mode.name)

    override val wallpaperBackdropFlow: Flow<WallpaperBackdrop> =
        enumFlow(PreferenceKeys.WALLPAPER_BACKDROP, AppConstants.DEFAULT_WALLPAPER_BACKDROP)

    override suspend fun setWallpaperBackdrop(backdrop: WallpaperBackdrop) =
        putValue(PreferenceKeys.WALLPAPER_BACKDROP, backdrop.name)

    override val contentTopMarginScaleFlow: Flow<Float> =
        valueFlow(PreferenceKeys.CONTENT_TOP_MARGIN_SCALE, AppConstants.DEFAULT_TOP_MARGIN)

    override suspend fun setContentTopMarginScale(scale: Float) =
        putValue(PreferenceKeys.CONTENT_TOP_MARGIN_SCALE, scale)

    override val rotationLockedFlow: Flow<Boolean> =
        valueFlow(PreferenceKeys.ROTATION_LOCKED, AppConstants.DEFAULT_ROTATION_LOCKED)

    override suspend fun setRotationLocked(isEnabled: Boolean) =
        putValue(PreferenceKeys.ROTATION_LOCKED, isEnabled)

    /**
     * Setzt die Einstellungen auf Werkzustand zurück, indem die Keys gelöscht werden.
     * Dadurch greifen beim nächsten Lesen (Flow Update) automatisch die Defaults in `AppConstants`.
     * * AUSNAHME: Onboarding Status bleibt erhalten.
     */
    override suspend fun purgeRepository() {
        safeEdit { preferences ->
            preferences.remove(PreferenceKeys.SORT_ORDER_KEY)
            preferences.remove(PreferenceKeys.TEXT_SHADOW_ENABLED)
            preferences.remove(PreferenceKeys.TEXT_COLOR)
            preferences.remove(PreferenceKeys.LAYOUT_SCALE)
            preferences.remove(PreferenceKeys.WALLPAPER_SCRIM_ALPHA)
            preferences.remove(PreferenceKeys.VERTICAL_PADDING_SCALE)
            preferences.remove(PreferenceKeys.IS_FONT_BOLD)
            preferences.remove(PreferenceKeys.CONTENT_TOP_MARGIN_SCALE)
            preferences.remove(PreferenceKeys.FAVORITES_ALIGNMENT)
            preferences.remove(PreferenceKeys.SHOW_CALENDAR_EVENT)
            preferences.remove(PreferenceKeys.SHOW_ALARM)
            preferences.remove(PreferenceKeys.AUTO_SHOW_KEYBOARD)
            preferences.remove(PreferenceKeys.AUTO_LAUNCH_APP)
            preferences.remove(PreferenceKeys.ROTATION_LOCKED)
            // APP_DRAWER_MODE backs wallpaperSurfaceMode (legacy key name);
            // it is a user-facing setting and must reset like the rest.
            preferences.remove(PreferenceKeys.APP_DRAWER_MODE)
            preferences.remove(PreferenceKeys.WALLPAPER_BACKDROP)

            // Legacy orphans: the double-tap-to-lock, swipe-down-to-notifications
            // and secure-window features were removed along with their
            // PrefKeys, so a pre-removal install may still carry the persisted
            // values with no live key to remove them by. Clear them by literal
            // key once here — otherwise a "reset all settings" would leave them
            // behind forever. The keys are never read anymore; this only keeps
            // purge a complete wipe. Safe to delete once no install predates
            // the feature removals.
            preferences.remove(booleanPreferencesKey("double_tap_to_lock_enabled"))
            preferences.remove(booleanPreferencesKey("swipe_down_to_notifications_enabled"))
            preferences.remove(booleanPreferencesKey("secure_window"))

            // WICHTIG: Onboarding Status wird NICHT gelöscht
            // purge-exempt: ONBOARDING_COMPLETED — kept intentionally across a
            // reset (see the KDoc "AUSNAHME" above).
        }
    }
}