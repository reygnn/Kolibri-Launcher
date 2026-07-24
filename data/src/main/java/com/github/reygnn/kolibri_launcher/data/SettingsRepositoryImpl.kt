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
import com.github.reygnn.kolibri_launcher.core.toEnumOrNull
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.coerceInSafe
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperSurfaceMode
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : SettingsRepository {

    /**
     * Definition der DataStore Keys.
     * Die Strings entsprechen den Keys, die auch in der settings.xml oder intern verwendet werden.
     */
    private object PreferenceKeys {
        // String Keys
        val SORT_ORDER_KEY = stringPreferencesKey(AppConstants.PrefKeys.SORT_ORDER)
        val FAVORITES_ALIGNMENT = stringPreferencesKey(AppConstants.PrefKeys.FAVORITES_ALIGNMENT)
        val APP_DRAWER_MODE = stringPreferencesKey(AppConstants.PrefKeys.APP_DRAWER_MODE)

        // Boolean Keys
        val ONBOARDING_COMPLETED = booleanPreferencesKey(AppConstants.PrefKeys.ONBOARDING_COMPLETED)
        val DOUBLE_TAP_TO_LOCK_ENABLED =
            booleanPreferencesKey(AppConstants.PrefKeys.DOUBLE_TAP_TO_LOCK)
        val SWIPE_DOWN_TO_NOTIFICATIONS_ENABLED =
            booleanPreferencesKey(AppConstants.PrefKeys.SWIPE_DOWN_TO_NOTIFICATIONS)
        val TEXT_SHADOW_ENABLED = booleanPreferencesKey(AppConstants.PrefKeys.TEXT_SHADOW_ENABLED)
        val IS_FONT_BOLD = booleanPreferencesKey(AppConstants.PrefKeys.IS_FONT_BOLD)
        val SHOW_CALENDAR_EVENT = booleanPreferencesKey(AppConstants.PrefKeys.SHOW_CALENDAR_EVENT)
        val SHOW_ALARM = booleanPreferencesKey(AppConstants.PrefKeys.SHOW_ALARM)
        val AUTO_SHOW_KEYBOARD = booleanPreferencesKey(AppConstants.PrefKeys.AUTO_SHOW_KEYBOARD)
        val AUTO_LAUNCH_APP = booleanPreferencesKey(AppConstants.PrefKeys.AUTO_LAUNCH_APP)
        val SECURE_WINDOW = booleanPreferencesKey(AppConstants.PrefKeys.SECURE_WINDOW)
        val ROTATION_LOCKED = booleanPreferencesKey(AppConstants.PrefKeys.ROTATION_LOCKED)


        // Int Keys
        val TEXT_COLOR = intPreferencesKey(AppConstants.PrefKeys.TEXT_COLOR)
        val CHIP_BACKGROUND_COLOR = intPreferencesKey(AppConstants.PrefKeys.CHIP_BACKGROUND_COLOR)

        // Float Keys
        val LAYOUT_SCALE = floatPreferencesKey(AppConstants.PrefKeys.LAYOUT_SCALE)
        val VERTICAL_PADDING_SCALE =
            floatPreferencesKey(AppConstants.PrefKeys.VERTICAL_PADDING_SCALE)
        val CONTENT_TOP_MARGIN_SCALE =
            floatPreferencesKey(AppConstants.PrefKeys.CONTENT_TOP_MARGIN_SCALE)
    }

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

    private fun boolFlow(key: Preferences.Key<Boolean>, default: Boolean): Flow<Boolean> =
        dataStore.data.safeData.map { it[key] ?: default }

    private fun intFlow(key: Preferences.Key<Int>, default: Int): Flow<Int> =
        dataStore.data.safeData.map { it[key] ?: default }

    private fun floatFlow(key: Preferences.Key<Float>, default: Float): Flow<Float> =
        dataStore.data.safeData.map { it[key] ?: default }

    private inline fun <reified T : Enum<T>> enumFlow(
        key: Preferences.Key<String>,
        default: T,
    ): Flow<T> = dataStore.data.safeData.map { it[key].toEnumOrNull<T>() ?: default }

    private suspend fun <V : Any> putValue(key: Preferences.Key<V>, value: V) =
        safeEdit { it[key] = value }

    // --- IMPLEMENTATION ---

    override val sortOrderFlow: Flow<SortOrder> =
        enumFlow(PreferenceKeys.SORT_ORDER_KEY, AppConstants.DEFAULT_SORT_ORDER)

    override suspend fun setSortOrder(sortOrder: SortOrder) =
        putValue(PreferenceKeys.SORT_ORDER_KEY, sortOrder.name)

    override val doubleTapToLockEnabledFlow: Flow<Boolean> =
        boolFlow(PreferenceKeys.DOUBLE_TAP_TO_LOCK_ENABLED, AppConstants.DEFAULT_DOUBLE_TAP_TO_LOCK)

    override suspend fun setDoubleTapToLock(isEnabled: Boolean) =
        putValue(PreferenceKeys.DOUBLE_TAP_TO_LOCK_ENABLED, isEnabled)

    override val swipeDownToNotificationsEnabledFlow: Flow<Boolean> =
        boolFlow(PreferenceKeys.SWIPE_DOWN_TO_NOTIFICATIONS_ENABLED, AppConstants.DEFAULT_SWIPE_DOWN_NOTIFICATIONS)

    override suspend fun setSwipeDownToNotifications(isEnabled: Boolean) =
        putValue(PreferenceKeys.SWIPE_DOWN_TO_NOTIFICATIONS_ENABLED, isEnabled)

    override val onboardingCompletedFlow: Flow<Boolean> =
        boolFlow(PreferenceKeys.ONBOARDING_COMPLETED, false)

    override suspend fun setOnboardingCompleted() =
        putValue(PreferenceKeys.ONBOARDING_COMPLETED, true)

    override val textShadowEnabledFlow: Flow<Boolean> =
        boolFlow(PreferenceKeys.TEXT_SHADOW_ENABLED, AppConstants.DEFAULT_TEXT_SHADOW_ENABLED)

    override suspend fun setTextShadowEnabled(isEnabled: Boolean) =
        putValue(PreferenceKeys.TEXT_SHADOW_ENABLED, isEnabled)

    override val textColorFlow: Flow<Int> =
        intFlow(PreferenceKeys.TEXT_COLOR, AppConstants.DEFAULT_TEXT_COLOR)

    override suspend fun setTextColor(color: Int) =
        putValue(PreferenceKeys.TEXT_COLOR, color)

    override val chipBackgroundColorFlow: Flow<Int> =
        intFlow(PreferenceKeys.CHIP_BACKGROUND_COLOR, AppConstants.DEFAULT_CHIP_BG_COLOR)

    override suspend fun setChipBackgroundColor(color: Int) =
        putValue(PreferenceKeys.CHIP_BACKGROUND_COLOR, color)

    override val showCalendarEventFlow: Flow<Boolean> =
        boolFlow(PreferenceKeys.SHOW_CALENDAR_EVENT, AppConstants.DEFAULT_SHOW_CALENDAR)

    override suspend fun setShowCalendarEvent(isEnabled: Boolean) =
        putValue(PreferenceKeys.SHOW_CALENDAR_EVENT, isEnabled)

    override val showAlarmFlow: Flow<Boolean> =
        boolFlow(PreferenceKeys.SHOW_ALARM, AppConstants.DEFAULT_SHOW_ALARM)

    override suspend fun setShowAlarm(isEnabled: Boolean) =
        putValue(PreferenceKeys.SHOW_ALARM, isEnabled)

    override val autoShowKeyboardFlow: Flow<Boolean> =
        boolFlow(PreferenceKeys.AUTO_SHOW_KEYBOARD, AppConstants.DEFAULT_AUTO_SHOW_KEYBOARD)

    override suspend fun setAutoShowKeyboard(isEnabled: Boolean) =
        putValue(PreferenceKeys.AUTO_SHOW_KEYBOARD, isEnabled)

    override val autoLaunchAppFlow: Flow<Boolean> =
        boolFlow(PreferenceKeys.AUTO_LAUNCH_APP, AppConstants.DEFAULT_AUTO_LAUNCH_APP)

    override suspend fun setAutoLaunchApp(isEnabled: Boolean) =
        putValue(PreferenceKeys.AUTO_LAUNCH_APP, isEnabled)

    override val layoutScaleStateFlow: Flow<Float> =
        floatFlow(PreferenceKeys.LAYOUT_SCALE, AppConstants.DEFAULT_LAYOUT_SCALE)

    override suspend fun setLayoutScale(scale: Float) =
        putValue(PreferenceKeys.LAYOUT_SCALE, scale)

    override val verticalPaddingStateFlow: Flow<Float> =
        floatFlow(PreferenceKeys.VERTICAL_PADDING_SCALE, AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)

    override suspend fun setVerticalPadding(scale: Float) =
        putValue(PreferenceKeys.VERTICAL_PADDING_SCALE, scale)

    override val isFontBoldStateFlow: Flow<Boolean> =
        boolFlow(PreferenceKeys.IS_FONT_BOLD, AppConstants.DEFAULT_FONT_BOLD)

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

    override val contentTopMarginScaleFlow: Flow<Float> =
        floatFlow(PreferenceKeys.CONTENT_TOP_MARGIN_SCALE, AppConstants.DEFAULT_TOP_MARGIN)

    override suspend fun setContentTopMarginScale(scale: Float) =
        putValue(PreferenceKeys.CONTENT_TOP_MARGIN_SCALE, scale)

    override val secureWindowFlow: Flow<Boolean> =
        boolFlow(PreferenceKeys.SECURE_WINDOW, AppConstants.DEFAULT_SECURE_WINDOW)

    override suspend fun setSecureWindow(isEnabled: Boolean) =
        putValue(PreferenceKeys.SECURE_WINDOW, isEnabled)

    override val rotationLockedFlow: Flow<Boolean> =
        boolFlow(PreferenceKeys.ROTATION_LOCKED, AppConstants.DEFAULT_ROTATION_LOCKED)

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
            preferences.remove(PreferenceKeys.DOUBLE_TAP_TO_LOCK_ENABLED)
            preferences.remove(PreferenceKeys.SWIPE_DOWN_TO_NOTIFICATIONS_ENABLED)
            preferences.remove(PreferenceKeys.TEXT_SHADOW_ENABLED)
            preferences.remove(PreferenceKeys.TEXT_COLOR)
            preferences.remove(PreferenceKeys.CHIP_BACKGROUND_COLOR)
            preferences.remove(PreferenceKeys.LAYOUT_SCALE)
            preferences.remove(PreferenceKeys.VERTICAL_PADDING_SCALE)
            preferences.remove(PreferenceKeys.IS_FONT_BOLD)
            preferences.remove(PreferenceKeys.CONTENT_TOP_MARGIN_SCALE)
            preferences.remove(PreferenceKeys.FAVORITES_ALIGNMENT)
            preferences.remove(PreferenceKeys.SHOW_CALENDAR_EVENT)
            preferences.remove(PreferenceKeys.SHOW_ALARM)
            preferences.remove(PreferenceKeys.AUTO_SHOW_KEYBOARD)
            preferences.remove(PreferenceKeys.AUTO_LAUNCH_APP)
            preferences.remove(PreferenceKeys.SECURE_WINDOW)
            preferences.remove(PreferenceKeys.ROTATION_LOCKED)
            // APP_DRAWER_MODE backs wallpaperSurfaceMode (legacy key name);
            // it is a user-facing setting and must reset like the rest.
            preferences.remove(PreferenceKeys.APP_DRAWER_MODE)

            // WICHTIG: Onboarding Status wird NICHT gelöscht
            // preferences.remove(PreferenceKeys.ONBOARDING_COMPLETED)
        }
    }
}