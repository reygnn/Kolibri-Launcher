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
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsManager @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @param:ApplicationContext private val context: Context
) : SettingsRepository {

    private object PreferenceKeys {
        val SORT_ORDER_KEY = stringPreferencesKey("app_drawer_sort_order")
        val DOUBLE_TAP_TO_LOCK_ENABLED = booleanPreferencesKey("double_tap_to_lock_enabled")
        val SWIPE_DOWN_TO_NOTIFICATIONS_ENABLED = booleanPreferencesKey("swipe_down_to_notifications_enabled")
        val READABILITY_MODE = stringPreferencesKey("text_readability_mode")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val TEXT_SHADOW_ENABLED = booleanPreferencesKey("text_shadow_enabled")
        val TEXT_COLOR = intPreferencesKey("text_color")
        val SHOW_CALENDAR_EVENT = booleanPreferencesKey("show_calendar_event")
        val SHOW_ALARM = booleanPreferencesKey("show_alarm")
        val CHIP_BACKGROUND_COLOR = intPreferencesKey("chip_background_color")
        val CONTENT_TOP_MARGIN_SCALE = floatPreferencesKey("content_top_margin_scale")
        val LAYOUT_SCALE = floatPreferencesKey("layout_scale")
        val VERTICAL_PADDING_SCALE = floatPreferencesKey("vertical_padding_scale")
        val IS_FONT_BOLD = booleanPreferencesKey("is_font_bold")
        val AUTO_SHOW_KEYBOARD = booleanPreferencesKey("auto_show_keyboard_drawer")
        val AUTO_LAUNCH_APP = booleanPreferencesKey("auto_launch_app")
        val SPLIT_MODE_THRESHOLD = intPreferencesKey("split_mode_threshold")
    }

    /**
     * ROCKY BALBOA HELPER:
     * Fängt ALLE Exceptions ab (nicht nur IOException), damit die App niemals crasht,
     * selbst wenn DataStore korrupt ist, Rechte fehlen oder Typen falsch sind.
     */
    private val Flow<Preferences>.safeData: Flow<Preferences>
        get() = this.catch { e ->
            if (e is Exception) {
                TimberWrapper.silentError(e, "SafeDataStore: Fallback to empty prefs")
                emit(emptyPreferences())
            } else {
                throw e // Nur fatale Errors (OOM etc.) durchlassen
            }
        }

    override val sortOrderFlow: Flow<SortOrder> = dataStore.data.safeData
        .map { preferences ->
            val sortName = preferences[PreferenceKeys.SORT_ORDER_KEY] ?: SortOrder.TIME_WEIGHTED_USAGE.name
            try {
                SortOrder.valueOf(sortName)
            } catch (e: IllegalArgumentException) {
                TimberWrapper.silentError(e, "Invalid sort order value: $sortName, using default")
                SortOrder.TIME_WEIGHTED_USAGE
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Unexpected error parsing sort order")
                SortOrder.TIME_WEIGHTED_USAGE
            }
        }

    override suspend fun setSortOrder(sortOrder: SortOrder) {
        try {
            dataStore.edit { settings ->
                settings[PreferenceKeys.SORT_ORDER_KEY] = sortOrder.name
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting sort order: $sortOrder")
        }
    }

    override val doubleTapToLockEnabledFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.DOUBLE_TAP_TO_LOCK_ENABLED] ?: false
        }

    override suspend fun setDoubleTapToLock(isEnabled: Boolean) {
        try {
            dataStore.edit { settings ->
                settings[PreferenceKeys.DOUBLE_TAP_TO_LOCK_ENABLED] = isEnabled
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting double tap to lock: $isEnabled")
        }
    }

    override val swipeDownToNotificationsEnabledFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.SWIPE_DOWN_TO_NOTIFICATIONS_ENABLED] ?: false
        }

    override suspend fun setSwipeDownToNotifications(isEnabled: Boolean) {
        try {
            dataStore.edit { settings ->
                settings[PreferenceKeys.SWIPE_DOWN_TO_NOTIFICATIONS_ENABLED] = isEnabled
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting swipe down to notifications: $isEnabled")
        }
    }

    override val readabilityModeFlow: Flow<String> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.READABILITY_MODE] ?: "smart_contrast"
        }

    override suspend fun setReadabilityMode(mode: String) {
        try {
            dataStore.edit { preferences ->
                preferences[PreferenceKeys.READABILITY_MODE] = mode
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting readability mode: $mode")
        }
    }

    override val onboardingCompletedFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.ONBOARDING_COMPLETED] ?: false
        }

    override suspend fun setOnboardingCompleted() {
        try {
            dataStore.edit { settings ->
                settings[PreferenceKeys.ONBOARDING_COMPLETED] = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting onboarding completed")
        }
    }

    override val textShadowEnabledFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.TEXT_SHADOW_ENABLED] ?: true
        }

    override suspend fun setTextShadowEnabled(isEnabled: Boolean) {
        try {
            dataStore.edit { settings ->
                settings[PreferenceKeys.TEXT_SHADOW_ENABLED] = isEnabled
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting text shadow enabled: $isEnabled")
        }
    }

    override val textColorFlow: Flow<Int> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.TEXT_COLOR] ?: 0
        }

    override suspend fun setTextColor(color: Int) {
        try {
            dataStore.edit { settings ->
                settings[PreferenceKeys.TEXT_COLOR] = color
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting text color: $color")
        }
    }

    override val showCalendarEventFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.SHOW_CALENDAR_EVENT] ?: false
        }

    override val chipBackgroundColorFlow: Flow<Int> = dataStore.data.safeData
        .map { preferences ->
            // 0 = Auto (was dem alten halb-transparenten Look entspricht)
            preferences[PreferenceKeys.CHIP_BACKGROUND_COLOR] ?: 0
        }

    override suspend fun setChipBackgroundColor(color: Int) {
        try {
            dataStore.edit { settings ->
                settings[PreferenceKeys.CHIP_BACKGROUND_COLOR] = color
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting chip background color: $color")
        }
    }

    override val showAlarmFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.SHOW_ALARM] ?: false
        }

    override suspend fun setShowAlarm(isEnabled: Boolean) {
        try {
            dataStore.edit { settings ->
                settings[PreferenceKeys.SHOW_ALARM] = isEnabled
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting show alarm: $isEnabled")
        }
    }

    override suspend fun setShowCalendarEvent(isEnabled: Boolean) {
        try {
            dataStore.edit { settings ->
                settings[PreferenceKeys.SHOW_CALENDAR_EVENT] = isEnabled
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting show calendar event: $isEnabled")
        }
    }

    override val autoShowKeyboardFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.AUTO_SHOW_KEYBOARD] ?: false // Standardwert
        }

    override suspend fun setAutoShowKeyboard(isEnabled: Boolean) {
        try {
            dataStore.edit { settings ->
                settings[PreferenceKeys.AUTO_SHOW_KEYBOARD] = isEnabled
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting auto show keyboard: $isEnabled")
        }
    }

    override val autoLaunchAppFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.AUTO_LAUNCH_APP] ?: false // Standardwert false
        }

    override suspend fun setAutoLaunchApp(isEnabled: Boolean) {
        try {
            dataStore.edit { settings ->
                settings[PreferenceKeys.AUTO_LAUNCH_APP] = isEnabled
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting auto launch app: $isEnabled")
        }
    }

    override val splitModeThresholdFlow: Flow<Int> = dataStore.data.safeData
        .map { preferences ->
            val threshold = preferences[PreferenceKeys.SPLIT_MODE_THRESHOLD] ?: 0
            // Validierung: Stelle sicher, dass der Wert im gültigen Bereich liegt
            threshold.coerceIn(0, 512)
        }

    override suspend fun setSplitModeThreshold(thresholdPixels: Int) {
        try {
            // Validiere Input (0-512)
            val validThreshold = thresholdPixels.coerceIn(0, 512)

            dataStore.edit { settings ->
                settings[PreferenceKeys.SPLIT_MODE_THRESHOLD] = validThreshold
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting split mode threshold: $thresholdPixels")
        }
    }

    override val layoutScaleStateFlow: Flow<Float> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.LAYOUT_SCALE] ?: AppConstants.DEFAULT_LAYOUT_SCALE
        }

    override suspend fun setLayoutScale(scale: Float) {
        try {
            dataStore.edit { it[PreferenceKeys.LAYOUT_SCALE] = scale }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting layout scale")
        }
    }

    override val verticalPaddingStateFlow: Flow<Float> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.VERTICAL_PADDING_SCALE] ?: AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR
        }

    override suspend fun setVerticalPadding(scale: Float) {
        try {
            dataStore.edit { it[PreferenceKeys.VERTICAL_PADDING_SCALE] = scale }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting vertical padding")
        }
    }

    override val isFontBoldStateFlow: Flow<Boolean> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.IS_FONT_BOLD] ?: AppConstants.DEFAULT_FONT_BOLD
        }

    override suspend fun setFontBold(isBold: Boolean) {
        try {
            dataStore.edit { it[PreferenceKeys.IS_FONT_BOLD] = isBold }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting font bold")
        }
    }

    override val contentTopMarginScaleFlow: Flow<Float> = dataStore.data.safeData
        .map { preferences ->
            preferences[PreferenceKeys.CONTENT_TOP_MARGIN_SCALE] ?: 0.0f
        }

    override suspend fun setContentTopMarginScale(scale: Float) {
        try {
            dataStore.edit { it[PreferenceKeys.CONTENT_TOP_MARGIN_SCALE] = scale }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting content top margin")
        }
    }

    override suspend fun purgeRepository() {
        try {
            dataStore.edit { preferences ->
                // App Drawer
                preferences.remove(PreferenceKeys.SORT_ORDER_KEY)

                // Gestures
                preferences.remove(PreferenceKeys.DOUBLE_TAP_TO_LOCK_ENABLED)
                preferences.remove(PreferenceKeys.SWIPE_DOWN_TO_NOTIFICATIONS_ENABLED)

                // Onboarding nicht resetten.
                // preferences.remove(PreferenceKeys.ONBOARDING_COMPLETED)

                // Theme / Appearance
                preferences.remove(PreferenceKeys.READABILITY_MODE)
                preferences.remove(PreferenceKeys.TEXT_SHADOW_ENABLED)
                preferences.remove(PreferenceKeys.TEXT_COLOR)
                preferences.remove(PreferenceKeys.CHIP_BACKGROUND_COLOR)
                preferences.remove(PreferenceKeys.LAYOUT_SCALE)
                preferences.remove(PreferenceKeys.VERTICAL_PADDING_SCALE)
                preferences.remove(PreferenceKeys.IS_FONT_BOLD)
                preferences.remove(PreferenceKeys.CONTENT_TOP_MARGIN_SCALE)

                // Home Screen Events
                preferences.remove(PreferenceKeys.SHOW_CALENDAR_EVENT)
                preferences.remove(PreferenceKeys.SHOW_ALARM)

                preferences.remove(PreferenceKeys.AUTO_SHOW_KEYBOARD)
                preferences.remove(PreferenceKeys.AUTO_LAUNCH_APP)

                // Power-User Settings
                preferences.remove(PreferenceKeys.SPLIT_MODE_THRESHOLD)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to purge SettingsManager repository")
        }
    }
}