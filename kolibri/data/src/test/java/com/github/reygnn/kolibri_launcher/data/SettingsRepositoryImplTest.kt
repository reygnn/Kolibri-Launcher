package com.github.reygnn.kolibri_launcher.data

import java.io.IOException
import com.github.reygnn.kolibri_launcher.domain.model.SettingsDefaults

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFailsWith

@ExperimentalCoroutinesApi
class SettingsRepositoryImplTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var fakeDataStore: FakeDataStore
    private lateinit var settingsManager: SettingsRepositoryImpl

    // mockContext wird nur als Konstruktor-Argument übergeben — kein Stubbing nötig
    private val context: Context = mockk(relaxed = true)

    private val SORT_ORDER_KEY = stringPreferencesKey("app_drawer_sort_order")
    private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    private val SHOW_CALENDAR_EVENT = booleanPreferencesKey("show_calendar_event")
    private val SHOW_ALARM = booleanPreferencesKey("show_alarm")

    @Before
    fun setup() {
        fakeDataStore = FakeDataStore()
        settingsManager = SettingsRepositoryImpl(fakeDataStore)
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `sortOrderFlow - when no value is set - returns default value`() = runTest {
        assertThat(settingsManager.sortOrderFlow.first()).isEqualTo(SortOrder.TIME_WEIGHTED_USAGE)
    }

    @Test
    fun `sortOrderFlow - when a value is set - returns that value`() = runTest {
        fakeDataStore.edit { it[SORT_ORDER_KEY] = SortOrder.ALPHABETICAL.name }

        assertThat(settingsManager.sortOrderFlow.first()).isEqualTo(SortOrder.ALPHABETICAL)
    }

    @Test
    fun `sortOrderFlow - when invalid value is stored - returns default value`() = runTest {
        fakeDataStore.edit { it[SORT_ORDER_KEY] = "INVALID_ENUM_VALUE" }

        assertThat(settingsManager.sortOrderFlow.first()).isEqualTo(SortOrder.TIME_WEIGHTED_USAGE)
    }

    @Test
    fun `setSortOrder - correctly saves the value`() = runTest {
        settingsManager.setSortOrder(SortOrder.ALPHABETICAL)

        val savedValue = fakeDataStore.data.first()[SORT_ORDER_KEY]
        assertThat(savedValue).isEqualTo(SortOrder.ALPHABETICAL.name)
    }

    private val FAVORITES_ALIGNMENT_KEY = stringPreferencesKey("favorites_alignment")

    @Test
    fun `favoritesAlignmentFlow - when invalid value is stored - returns default`() = runTest {
        fakeDataStore.edit { it[FAVORITES_ALIGNMENT_KEY] = "INVALID_ALIGNMENT_VALUE" }

        assertThat(settingsManager.favoritesAlignmentFlow.first()).isEqualTo(SettingsDefaults.DEFAULT_FAVORITES_ALIGNMENT)
    }

    @Test
    fun `setFavoritesAlignment - correctly saves the enum name`() = runTest {
        settingsManager.setFavoritesAlignment(FavoritesAlignment.CENTER)

        val savedValue = fakeDataStore.data.first()[FAVORITES_ALIGNMENT_KEY]
        assertThat(savedValue).isEqualTo(FavoritesAlignment.CENTER.name)
    }

    @Test
    fun `onboardingCompletedFlow - when no value is set - returns default false`() = runTest {
        assertFalse(settingsManager.onboardingCompletedFlow.first())
    }

    @Test
    fun `setOnboardingCompleted - correctly saves true`() = runTest {
        settingsManager.setOnboardingCompleted()

        val savedValue = fakeDataStore.data.first()[ONBOARDING_COMPLETED]
        assertThat(savedValue ?: false).isTrue()
    }

    @Test
    fun `flows - emit new values when they are changed`() = runTest {
        settingsManager.sortOrderFlow.test {
            assertThat(awaitItem()).isEqualTo(SortOrder.TIME_WEIGHTED_USAGE)

            settingsManager.setSortOrder(SortOrder.ALPHABETICAL)

            assertThat(awaitItem()).isEqualTo(SortOrder.ALPHABETICAL)
        }
    }

    // ========== CRASH-RESISTANCE TESTS ==========

    @Test
    fun `setSortOrder - when DataStore edit fails - does not crash`() = runTest {
        fakeDataStore.makeEditFail()

        settingsManager.setSortOrder(SortOrder.ALPHABETICAL)

        val savedValue = fakeDataStore.data.first()[SORT_ORDER_KEY]
        assertThat(savedValue == null || savedValue != SortOrder.ALPHABETICAL.name).isTrue()
    }

    @Test
    fun `setSortOrder - when CancellationException - propagates it`() = runTest {
        fakeDataStore.makeCancellable()

        assertFailsWith<CancellationException> {
            settingsManager.setSortOrder(SortOrder.ALPHABETICAL)
        }
    }

    @Test
    fun `setOnboardingCompleted - when DataStore edit fails - does not crash`() = runTest {
        fakeDataStore.makeEditFail()

        settingsManager.setOnboardingCompleted()

        assertFalse(settingsManager.onboardingCompletedFlow.first())
    }

    @Test
    fun `setOnboardingCompleted - when CancellationException - propagates it`() = runTest {
        fakeDataStore.makeCancellable()

        assertFailsWith<CancellationException> {
            settingsManager.setOnboardingCompleted()
        }
    }

    @Test
    fun `sortOrderFlow - when DataStore read fails - returns default value`() = runTest {
        fakeDataStore.makeReadFail()

        assertThat(settingsManager.sortOrderFlow.first()).isEqualTo(SortOrder.TIME_WEIGHTED_USAGE)
    }

    @Test
    fun `onboardingCompletedFlow - when DataStore read fails - returns default false`() = runTest {
        fakeDataStore.makeReadFail()

        assertFalse(settingsManager.onboardingCompletedFlow.first())
    }

    @Test
    fun `setSortOrder - called multiple times - all values are saved`() = runTest {
        settingsManager.sortOrderFlow.test {
            assertThat(awaitItem()).isEqualTo(SortOrder.TIME_WEIGHTED_USAGE)

            settingsManager.setSortOrder(SortOrder.ALPHABETICAL)
            assertThat(awaitItem()).isEqualTo(SortOrder.ALPHABETICAL)

            settingsManager.setSortOrder(SortOrder.TIME_WEIGHTED_USAGE)
            assertThat(awaitItem()).isEqualTo(SortOrder.TIME_WEIGHTED_USAGE)
        }
    }

    @Test
    fun `multiple flows - all work independently`() = runTest {
        settingsManager.setSortOrder(SortOrder.ALPHABETICAL)
        settingsManager.setOnboardingCompleted()

        assertThat(settingsManager.sortOrderFlow.first()).isEqualTo(SortOrder.ALPHABETICAL)
        assertThat(settingsManager.onboardingCompletedFlow.first()).isTrue()
    }

    @Test
    fun `sortOrderFlow - with corrupted data - returns default`() = runTest {
        fakeDataStore.edit { it[SORT_ORDER_KEY] = "" }

        assertThat(settingsManager.sortOrderFlow.first()).isEqualTo(SortOrder.TIME_WEIGHTED_USAGE)
    }

    @Test
    fun `sortOrderFlow - with null value - returns default`() = runTest {
        assertThat(settingsManager.sortOrderFlow.first()).isEqualTo(SortOrder.TIME_WEIGHTED_USAGE)
    }

    // ========== SHOW ALARM TESTS ==========

    @Test
    fun `showAlarmFlow - when no value is set - returns default false`() = runTest {
        assertFalse(settingsManager.showAlarmFlow.first())
    }

    @Test
    fun `showAlarmFlow - when value is set to false - returns false`() = runTest {
        fakeDataStore.edit { it[SHOW_ALARM] = false }
        assertFalse(settingsManager.showAlarmFlow.first())
    }

    @Test
    fun `showAlarmFlow - when value is set to true - returns true`() = runTest {
        fakeDataStore.edit { it[SHOW_ALARM] = true }
        assertThat(settingsManager.showAlarmFlow.first()).isTrue()
    }

    @Test
    fun `setShowAlarm - correctly saves false`() = runTest {
        settingsManager.setShowAlarm(false)
        assertFalse(fakeDataStore.data.first()[SHOW_ALARM] ?: true)
    }

    @Test
    fun `setShowAlarm - correctly saves true`() = runTest {
        settingsManager.setShowAlarm(true)
        assertThat(fakeDataStore.data.first()[SHOW_ALARM] ?: false).isTrue()
    }

    @Test
    fun `setShowAlarm - when DataStore edit fails - does not crash`() = runTest {
        fakeDataStore.makeEditFail()
        settingsManager.setShowAlarm(true)
        assertFalse(settingsManager.showAlarmFlow.first())
    }

    @Test
    fun `setShowAlarm - when CancellationException - propagates it`() = runTest {
        fakeDataStore.makeCancellable()
        assertFailsWith<CancellationException> { settingsManager.setShowAlarm(false) }
    }

    @Test
    fun `showAlarmFlow - when DataStore read fails - returns default true`() = runTest {
        fakeDataStore.makeReadFail()
        assertFalse(settingsManager.showAlarmFlow.first())
    }

    @Test
    fun `showAlarmFlow - emits new values when changed`() = runTest {
        settingsManager.showAlarmFlow.test {
            assertThat(awaitItem()).isEqualTo(false)
            settingsManager.setShowAlarm(true)
            assertThat(awaitItem()).isEqualTo(true)
            settingsManager.setShowAlarm(false)
            assertThat(awaitItem()).isEqualTo(false)
        }
    }

    @Test
    fun `setShowAlarm - toggling multiple times - works correctly`() = runTest {
        settingsManager.showAlarmFlow.test {
            assertThat(awaitItem()).isEqualTo(false)
            settingsManager.setShowAlarm(true)
            assertThat(awaitItem()).isEqualTo(true)
            settingsManager.setShowAlarm(false)
            assertThat(awaitItem()).isEqualTo(false)
            settingsManager.setShowAlarm(true)
            assertThat(awaitItem()).isEqualTo(true)
        }
    }

    @Test
    fun `showAlarmFlow - independent from showCalendarEventFlow`() = runTest {
        settingsManager.setShowCalendarEvent(true)
        assertThat(settingsManager.showCalendarEventFlow.first()).isTrue()
        assertFalse(settingsManager.showAlarmFlow.first())

        settingsManager.setShowAlarm(true)
        assertThat(settingsManager.showAlarmFlow.first()).isTrue()
        assertThat(settingsManager.showCalendarEventFlow.first()).isTrue()
    }

    @Test
    fun `multiple settings - showAlarm works with other settings`() = runTest {
        settingsManager.setSortOrder(SortOrder.ALPHABETICAL)
        settingsManager.setShowCalendarEvent(true)
        settingsManager.setShowAlarm(false)

        assertThat(settingsManager.sortOrderFlow.first()).isEqualTo(SortOrder.ALPHABETICAL)
        assertThat(settingsManager.showCalendarEventFlow.first()).isTrue()
        assertFalse(settingsManager.showAlarmFlow.first())
    }

    // ========== GESTURE & AUTO TESTS ==========

    @Test
    fun `autoShowKeyboardFlow - defaults to false and updates correctly`() = runTest {
        assertFalse(settingsManager.autoShowKeyboardFlow.first())
        settingsManager.setAutoShowKeyboard(true)
        assertThat(settingsManager.autoShowKeyboardFlow.first()).isTrue()
    }

    @Test
    fun `autoLaunchAppFlow - defaults to false and updates correctly`() = runTest {
        assertFalse(settingsManager.autoLaunchAppFlow.first())
        settingsManager.setAutoLaunchApp(true)
        assertThat(settingsManager.autoLaunchAppFlow.first()).isTrue()
    }

    // ========== THEME & APPEARANCE TESTS ==========

    @Test
    fun `textShadowEnabledFlow - defaults to TRUE and updates correctly`() = runTest {
        assertWithMessage("Default should be true").that(settingsManager.textShadowEnabledFlow.first()).isTrue()
        settingsManager.setTextShadowEnabled(false)
        assertFalse(settingsManager.textShadowEnabledFlow.first())
    }

    @Test
    fun `textColorFlow - defaults to 0 and updates correctly`() = runTest {
        assertThat(settingsManager.textColorFlow.first()).isEqualTo(0)
        settingsManager.setTextColor(-16777216)
        assertThat(settingsManager.textColorFlow.first()).isEqualTo(-16777216)
    }

    @Test
    fun `isFontBoldStateFlow - updates correctly`() = runTest {
        settingsManager.setFontBold(true)
        assertThat(settingsManager.isFontBoldStateFlow.first()).isTrue()
        settingsManager.setFontBold(false)
        assertFalse(settingsManager.isFontBoldStateFlow.first())
    }

    @Test
    fun `layoutScales - update correctly`() = runTest {
        settingsManager.setLayoutScale(1.5f)
        settingsManager.setVerticalPadding(2.0f)
        settingsManager.setContentTopMarginScale(0.5f)

        assertThat(settingsManager.layoutScaleStateFlow.first()).isEqualTo(1.5f)
        assertThat(settingsManager.verticalPaddingStateFlow.first()).isEqualTo(2.0f)
        assertThat(settingsManager.contentTopMarginScaleFlow.first()).isEqualTo(0.5f)
    }

    // ========== HOME EVENT TESTS ==========

    @Test
    fun `showCalendarEventFlow - defaults to false and updates correctly`() = runTest {
        assertFalse(settingsManager.showCalendarEventFlow.first())
        settingsManager.setShowCalendarEvent(true)
        assertThat(settingsManager.showCalendarEventFlow.first()).isTrue()
    }

    // ========== PURGE TEST ==========

    @Test
    fun `purgeRepository - clears all settings keys`() = runTest {
        settingsManager.setSortOrder(SortOrder.ALPHABETICAL)
        settingsManager.setShowAlarm(true)

        settingsManager.purgeRepository()

        assertThat(settingsManager.sortOrderFlow.first()).isEqualTo(SortOrder.TIME_WEIGHTED_USAGE)
        assertFalse(settingsManager.showAlarmFlow.first())
    }

    @Test
    fun `purgeRepository - clears legacy orphaned keys of removed features`() = runTest {
        // The double-tap-to-lock, swipe-down-to-notifications and secure-window
        // features were removed along with their PrefKeys; a pre-removal install
        // may still carry the persisted keys. purgeRepository must clear them by
        // literal name so "reset all settings" stays a complete wipe.
        val legacyKeys = listOf(
            booleanPreferencesKey("double_tap_to_lock_enabled"),
            booleanPreferencesKey("swipe_down_to_notifications_enabled"),
            booleanPreferencesKey("secure_window"),
        )
        fakeDataStore.edit { prefs -> legacyKeys.forEach { prefs[it] = true } }

        settingsManager.purgeRepository()

        val remaining = fakeDataStore.data.first()
        legacyKeys.forEach { key ->
            assertFalse("Legacy key $key should be cleared by purge", remaining.contains(key))
        }
    }

    @Test
    fun `purgeRepository - rethrows a store failure, the setters still swallow theirs`() = runTest {
        // 2b-4c, F1: only the purge reports a failure (the reset needs it); whether the setters
        // should swallow is a separate decision, so they keep doing it.
        fakeDataStore.makeEditFail()

        assertFailsWith<IOException> { settingsManager.purgeRepository() }
        settingsManager.setRotationLocked(true) // no exception

        fakeDataStore.resetErrorFlags()
        fakeDataStore.makeCancellable()
        assertFailsWith<CancellationException> { settingsManager.purgeRepository() }
    }

    // ========================================================================
    // DOOMSDAY TESTS
    // ========================================================================

    @Test
    fun `doomsday - corrupted types (ClassCastException) - safe fallback`() = runTest {
        // Inline mockk statt FakeDataStore, um die Exception zu erzwingen
        val mockDataStore = mockk<DataStore<Preferences>>()
        every { mockDataStore.data } returns flow {
            throw ClassCastException("Expected Boolean but got String")
        }

        val doomsdayManager = SettingsRepositoryImpl(mockDataStore)

        val result = doomsdayManager.showAlarmFlow.first()

        assertFalse("Should fallback to default false on ClassCastException", result)
    }

    @Test
    fun `doomsday - unexpected RuntimeException during read - safe fallback`() = runTest {
        val mockDataStore = mockk<DataStore<Preferences>>()
        every { mockDataStore.data } returns flow {
            throw SecurityException("Read permission denied")
        }

        val doomsdayManager = SettingsRepositoryImpl(mockDataStore)

        val result = doomsdayManager.sortOrderFlow.first()

        assertThat(result).isEqualTo(SortOrder.TIME_WEIGHTED_USAGE)
    }

    @Test
    fun `doomsday - fatal Error during read is re-thrown, not swallowed`() = runTest {
        // safeData recovers Exceptions to defaults, but non-Exception Throwables
        // (fatal Errors like OOM) must propagate — swallowing them and emitting
        // empty prefs would mask a fatal condition. Guards the AUDIT-7 #1 fix.
        val mockDataStore = mockk<DataStore<Preferences>>()
        every { mockDataStore.data } returns flow {
            throw OutOfMemoryError("simulated OOM during settings read")
        }

        val doomsdayManager = SettingsRepositoryImpl(mockDataStore)

        assertFailsWith<OutOfMemoryError> {
            doomsdayManager.sortOrderFlow.first()
        }
    }

    @Test
    fun `doomsday - rapid concurrent toggles - consistency check`() = runTest {
        repeat(100) { i ->
            settingsManager.setShowAlarm(i % 2 == 0)
        }

        val finalValue = settingsManager.showAlarmFlow.first()
        assertFalse("Final state should be false after odd number of toggles", finalValue)
    }

    // ========== AUDIT-14 V2: distinctUntilChanged regression ==========

    @Test
    fun `sortOrderFlow - unrelated shared-store write does not re-emit identical value`() = runTest {
        // sortOrderFlow drives the drawer combine on the hot tap-to-launch path.
        // distinctUntilChanged (per-flow, NOT in the shared enumFlow helper) must
        // suppress a re-emission caused by an unrelated write to the shared store.
        fakeDataStore.edit { it[SORT_ORDER_KEY] = SortOrder.ALPHABETICAL.name }

        settingsManager.sortOrderFlow.test {
            assertEquals(SortOrder.ALPHABETICAL, awaitItem())

            val usageKey = longPreferencesKey("usage_count_com.other/App")
            fakeDataStore.updateData { prefs ->
                prefs.toMutablePreferences().apply { set(usageKey, 1L) }
            }
            advanceUntilIdle()

            expectNoEvents()
        }
    }

    @Test
    fun `sortOrderFlow - still emits when the sort order actually changes`() = runTest {
        fakeDataStore.edit { it[SORT_ORDER_KEY] = SortOrder.ALPHABETICAL.name }

        settingsManager.sortOrderFlow.test {
            assertEquals(SortOrder.ALPHABETICAL, awaitItem())

            fakeDataStore.updateData { prefs ->
                prefs.toMutablePreferences().apply {
                    set(SORT_ORDER_KEY, SortOrder.TIME_WEIGHTED_USAGE.name)
                }
            }

            assertEquals(SortOrder.TIME_WEIGHTED_USAGE, awaitItem())
        }
    }

}
