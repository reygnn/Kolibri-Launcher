package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.coerceInSafe
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperSurfaceMode
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @param:ApplicationContext private val context: Context
) : SettingsRepository {

    /**
     * Definition der DataStore Keys.
     * Die Strings entsprechen den Keys, die auch in der settings.xml oder intern verwendet werden.
     */
    private object PreferenceKeys {
        // String Keys
        val SORT_ORDER_KEY = stringPreferencesKey(AppConstants.PrefKeys.SORT_ORDER)
        val READABILITY_MODE = stringPreferencesKey(AppConstants.PrefKeys.READABILITY_MODE)
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
     */
    private val Flow<Preferences>.safeData: Flow<Preferences>
        get() = this.catch { e ->
            if (e is Exception) {
                TimberWrapper.silentError(
                    e,
                    "SafeDataStore: Fallback to empty prefs due to read error"
                )
                emit(emptyPreferences())
            } else {
                throw e
            }
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

    // --- IMPLEMENTATION ---

    override val sortOrderFlow: Flow<SortOrder> = dataStore.data.safeData
        .map { preferences ->
            val sortName = preferences[PreferenceKeys.SORT_ORDER_KEY]
            // Default ist TIME_WEIGHTED_USAGE
            if (sortName == null) return@map AppConstants.DEFAULT_SORT_ORDER

            try {
                SortOrder.valueOf(sortName)
            } catch (e: Throwable) {
                // Fallback bei Parsing-Fehler
                AppConstants.DEFAULT_SORT_ORDER
            }
        }

    override suspend fun setSortOrder(sortOrder: SortOrder) {
        safeEdit { it[PreferenceKeys.SORT_ORDER_KEY] = sortOrder.name }
    }

    override val doubleTapToLockEnabledFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.DOUBLE_TAP_TO_LOCK_ENABLED]
                ?: AppConstants.DEFAULT_DOUBLE_TAP_TO_LOCK
        }

    override suspend fun setDoubleTapToLock(isEnabled: Boolean) {
        safeEdit { it[PreferenceKeys.DOUBLE_TAP_TO_LOCK_ENABLED] = isEnabled }
    }

    override val swipeDownToNotificationsEnabledFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.SWIPE_DOWN_TO_NOTIFICATIONS_ENABLED]
                ?: AppConstants.DEFAULT_SWIPE_DOWN_NOTIFICATIONS
        }

    override suspend fun setSwipeDownToNotifications(isEnabled: Boolean) {
        safeEdit { it[PreferenceKeys.SWIPE_DOWN_TO_NOTIFICATIONS_ENABLED] = isEnabled }
    }

    override val readabilityModeFlow: Flow<String> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.READABILITY_MODE]
                ?: AppConstants.DEFAULT_READABILITY_MODE
        }

    override suspend fun setReadabilityMode(mode: String) {
        safeEdit { it[PreferenceKeys.READABILITY_MODE] = mode }
    }

    override val onboardingCompletedFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.ONBOARDING_COMPLETED] ?: false
        }

    override suspend fun setOnboardingCompleted() {
        safeEdit { it[PreferenceKeys.ONBOARDING_COMPLETED] = true }
    }

    override val textShadowEnabledFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.TEXT_SHADOW_ENABLED]
                ?: AppConstants.DEFAULT_TEXT_SHADOW_ENABLED
        }

    override suspend fun setTextShadowEnabled(isEnabled: Boolean) {
        safeEdit { it[PreferenceKeys.TEXT_SHADOW_ENABLED] = isEnabled }
    }

    override val textColorFlow: Flow<Int> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.TEXT_COLOR]
                ?: AppConstants.DEFAULT_TEXT_COLOR
        }

    override suspend fun setTextColor(color: Int) {
        safeEdit { it[PreferenceKeys.TEXT_COLOR] = color }
    }

    override val chipBackgroundColorFlow: Flow<Int> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.CHIP_BACKGROUND_COLOR]
                ?: AppConstants.DEFAULT_CHIP_BG_COLOR
        }

    override suspend fun setChipBackgroundColor(color: Int) {
        safeEdit { it[PreferenceKeys.CHIP_BACKGROUND_COLOR] = color }
    }

    override val showCalendarEventFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.SHOW_CALENDAR_EVENT]
                ?: AppConstants.DEFAULT_SHOW_CALENDAR
        }

    override suspend fun setShowCalendarEvent(isEnabled: Boolean) {
        safeEdit { it[PreferenceKeys.SHOW_CALENDAR_EVENT] = isEnabled }
    }

    override val showAlarmFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.SHOW_ALARM]
                ?: AppConstants.DEFAULT_SHOW_ALARM
        }

    override suspend fun setShowAlarm(isEnabled: Boolean) {
        safeEdit { it[PreferenceKeys.SHOW_ALARM] = isEnabled }
    }

    override val autoShowKeyboardFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.AUTO_SHOW_KEYBOARD]
                ?: AppConstants.DEFAULT_AUTO_SHOW_KEYBOARD
        }

    override suspend fun setAutoShowKeyboard(isEnabled: Boolean) {
        safeEdit { it[PreferenceKeys.AUTO_SHOW_KEYBOARD] = isEnabled }
    }

    override val autoLaunchAppFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.AUTO_LAUNCH_APP]
                ?: AppConstants.DEFAULT_AUTO_LAUNCH_APP
        }

    override suspend fun setAutoLaunchApp(isEnabled: Boolean) {
        safeEdit { it[PreferenceKeys.AUTO_LAUNCH_APP] = isEnabled }
    }

    override val layoutScaleStateFlow: Flow<Float> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.LAYOUT_SCALE]
                ?: AppConstants.DEFAULT_LAYOUT_SCALE
        }

    override suspend fun setLayoutScale(scale: Float) {
        safeEdit { it[PreferenceKeys.LAYOUT_SCALE] = scale }
    }

    override val verticalPaddingStateFlow: Flow<Float> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.VERTICAL_PADDING_SCALE]
                ?: AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR
        }

    override suspend fun setVerticalPadding(scale: Float) {
        safeEdit { it[PreferenceKeys.VERTICAL_PADDING_SCALE] = scale }
    }

    override val isFontBoldStateFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.IS_FONT_BOLD]
                ?: AppConstants.DEFAULT_FONT_BOLD
        }

    override suspend fun setFontBold(isBold: Boolean) {
        safeEdit { it[PreferenceKeys.IS_FONT_BOLD] = isBold }
    }

    override val favoritesAlignmentFlow: Flow<FavoritesAlignment> = dataStore.data.safeData
        .map { preferences ->
            val name = preferences[PreferenceKeys.FAVORITES_ALIGNMENT]
                ?: return@map AppConstants.DEFAULT_FAVORITES_ALIGNMENT
            try {
                FavoritesAlignment.valueOf(name)
            } catch (e: Throwable) {
                AppConstants.DEFAULT_FAVORITES_ALIGNMENT
            }
        }

    override suspend fun setFavoritesAlignment(alignment: FavoritesAlignment) {
        safeEdit { it[PreferenceKeys.FAVORITES_ALIGNMENT] = alignment.name }
    }

    override val wallpaperSurfaceModeFlow: Flow<WallpaperSurfaceMode> = dataStore.data.safeData
        .map { preferences ->
            val name = preferences[PreferenceKeys.APP_DRAWER_MODE]
                ?: return@map AppConstants.DEFAULT_WALLPAPER_SURFACE_MODE
            try {
                WallpaperSurfaceMode.valueOf(name)
            } catch (e: Throwable) {
                AppConstants.DEFAULT_WALLPAPER_SURFACE_MODE
            }
        }

    override suspend fun setWallpaperSurfaceMode(mode: WallpaperSurfaceMode) {
        safeEdit { it[PreferenceKeys.APP_DRAWER_MODE] = mode.name }
    }

    override val contentTopMarginScaleFlow: Flow<Float> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.CONTENT_TOP_MARGIN_SCALE]
                ?: AppConstants.DEFAULT_TOP_MARGIN
        }

    override suspend fun setContentTopMarginScale(scale: Float) {
        safeEdit { it[PreferenceKeys.CONTENT_TOP_MARGIN_SCALE] = scale }
    }

    override val secureWindowFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.SECURE_WINDOW]
                ?: AppConstants.DEFAULT_SECURE_WINDOW
        }

    override suspend fun setSecureWindow(isEnabled: Boolean) {
        safeEdit {
            it[PreferenceKeys.SECURE_WINDOW] = isEnabled
        }
    }

    override val rotationLockedFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.ROTATION_LOCKED]
                ?: AppConstants.DEFAULT_ROTATION_LOCKED
        }

    override suspend fun setRotationLocked(isEnabled: Boolean) {
        safeEdit { it[PreferenceKeys.ROTATION_LOCKED] = isEnabled }
    }

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
            preferences.remove(PreferenceKeys.READABILITY_MODE)
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

            // WICHTIG: Onboarding Status wird NICHT gelöscht
            // preferences.remove(PreferenceKeys.ONBOARDING_COMPLETED)
        }
    }
}