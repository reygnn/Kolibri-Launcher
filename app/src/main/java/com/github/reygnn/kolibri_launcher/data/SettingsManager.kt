package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.SortOrder
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.concurrent.CancellationException
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
        val READABILITY_MODE = stringPreferencesKey("text_readability_mode")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val TEXT_SHADOW_ENABLED = booleanPreferencesKey("text_shadow_enabled")
        val TEXT_COLOR = intPreferencesKey("text_color")
    }

    override val sortOrderFlow: Flow<SortOrder> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                TimberWrapper.silentError(e, "Error reading SortOrder preferences")
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
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

    override val doubleTapToLockEnabledFlow: Flow<Boolean> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                TimberWrapper.silentError(e, "Error reading DoubleTapToLock preferences")
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { preferences ->
            preferences[PreferenceKeys.DOUBLE_TAP_TO_LOCK_ENABLED] ?: true
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

    override val readabilityModeFlow: Flow<String> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                TimberWrapper.silentError(e, "Error reading ReadabilityMode preferences")
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
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

    override val onboardingCompletedFlow: Flow<Boolean> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                TimberWrapper.silentError(e, "Error reading OnboardingCompleted preferences")
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
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

    override val textShadowEnabledFlow: Flow<Boolean> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                TimberWrapper.silentError(e, "Error reading TextShadowEnabled preferences")
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
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

    override val textColorFlow: Flow<Int> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                TimberWrapper.silentError(e, "Error reading TextColor preferences")
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
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

    override fun purgeRepository() { }
}