package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class SettingsRepositoryImplTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var fakeDataStore: FakeDataStore
    private lateinit var settingsManager: SettingsRepositoryImpl

    // mockContext wird nur als Konstruktor-Argument übergeben — kein Stubbing nötig
    private val mockContext: Context = mockk(relaxed = true)

    private val SORT_ORDER_KEY = stringPreferencesKey("app_drawer_sort_order")
    private val DOUBLE_TAP_TO_LOCK_ENABLED = booleanPreferencesKey("double_tap_to_lock_enabled")
    private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    private val READABILITY_MODE_KEY = stringPreferencesKey("text_readability_mode")
    private val SHOW_CALENDAR_EVENT = booleanPreferencesKey("show_calendar_event")
    private val SHOW_ALARM = booleanPreferencesKey("show_alarm")

    @Before
    fun setup() {
        fakeDataStore = FakeDataStore()
        settingsManager = SettingsRepositoryImpl(fakeDataStore, mockContext)
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `sortOrderFlow - when no value is set - returns default value`() = runTest {
        Assert.assertEquals(SortOrder.TIME_WEIGHTED_USAGE, settingsManager.sortOrderFlow.first())
    }

    @Test
    fun `sortOrderFlow - when a value is set - returns that value`() = runTest {
        fakeDataStore.edit { it[SORT_ORDER_KEY] = SortOrder.ALPHABETICAL.name }

        Assert.assertEquals(SortOrder.ALPHABETICAL, settingsManager.sortOrderFlow.first())
    }

    @Test
    fun `sortOrderFlow - when invalid value is stored - returns default value`() = runTest {
        fakeDataStore.edit { it[SORT_ORDER_KEY] = "INVALID_ENUM_VALUE" }

        Assert.assertEquals(SortOrder.TIME_WEIGHTED_USAGE, settingsManager.sortOrderFlow.first())
    }

    @Test
    fun `setSortOrder - correctly saves the value`() = runTest {
        settingsManager.setSortOrder(SortOrder.ALPHABETICAL)

        val savedValue = fakeDataStore.data.first()[SORT_ORDER_KEY]
        Assert.assertEquals(SortOrder.ALPHABETICAL.name, savedValue)
    }

    @Test
    fun `doubleTapToLockEnabledFlow - when no value is set - returns default false`() = runTest {
        assertFalse(settingsManager.doubleTapToLockEnabledFlow.first())
    }

    @Test
    fun `setDoubleTapToLock - correctly saves true`() = runTest {
        settingsManager.setDoubleTapToLock(true)

        val savedValue = fakeDataStore.data.first()[DOUBLE_TAP_TO_LOCK_ENABLED]
        assertTrue(savedValue ?: false)
    }

    @Test
    fun `onboardingCompletedFlow - when no value is set - returns default false`() = runTest {
        assertFalse(settingsManager.onboardingCompletedFlow.first())
    }

    @Test
    fun `setOnboardingCompleted - correctly saves true`() = runTest {
        settingsManager.setOnboardingCompleted()

        val savedValue = fakeDataStore.data.first()[ONBOARDING_COMPLETED]
        assertTrue(savedValue ?: false)
    }

    @Test
    fun `flows - emit new values when they are changed`() = runTest {
        settingsManager.sortOrderFlow.test {
            Assert.assertEquals(SortOrder.TIME_WEIGHTED_USAGE, awaitItem())

            settingsManager.setSortOrder(SortOrder.ALPHABETICAL)

            Assert.assertEquals(SortOrder.ALPHABETICAL, awaitItem())
        }
    }

    // ========== CRASH-RESISTANCE TESTS ==========

    @Test
    fun `setSortOrder - when DataStore edit fails - does not crash`() = runTest {
        fakeDataStore.makeEditFail()

        settingsManager.setSortOrder(SortOrder.ALPHABETICAL)

        val savedValue = fakeDataStore.data.first()[SORT_ORDER_KEY]
        assertTrue(savedValue == null || savedValue != SortOrder.ALPHABETICAL.name)
    }

    @Test
    fun `setSortOrder - when CancellationException - propagates it`() = runTest {
        fakeDataStore.makeCancellable()

        assertFailsWith<CancellationException> {
            settingsManager.setSortOrder(SortOrder.ALPHABETICAL)
        }
    }

    @Test
    fun `setDoubleTapToLock - when DataStore edit fails - does not crash`() = runTest {
        fakeDataStore.makeEditFail()

        settingsManager.setDoubleTapToLock(true)

        assertFalse(settingsManager.doubleTapToLockEnabledFlow.first())
    }

    @Test
    fun `setDoubleTapToLock - when CancellationException - propagates it`() = runTest {
        fakeDataStore.makeCancellable()

        assertFailsWith<CancellationException> {
            settingsManager.setDoubleTapToLock(false)
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

        Assert.assertEquals(SortOrder.TIME_WEIGHTED_USAGE, settingsManager.sortOrderFlow.first())
    }

    @Test
    fun `doubleTapToLockEnabledFlow - when DataStore read fails - returns default false`() = runTest {
        fakeDataStore.makeReadFail()

        assertFalse(settingsManager.doubleTapToLockEnabledFlow.first())
    }

    @Test
    fun `onboardingCompletedFlow - when DataStore read fails - returns default false`() = runTest {
        fakeDataStore.makeReadFail()

        assertFalse(settingsManager.onboardingCompletedFlow.first())
    }

    @Test
    fun `setSortOrder - called multiple times - all values are saved`() = runTest {
        settingsManager.sortOrderFlow.test {
            Assert.assertEquals(SortOrder.TIME_WEIGHTED_USAGE, awaitItem())

            settingsManager.setSortOrder(SortOrder.ALPHABETICAL)
            Assert.assertEquals(SortOrder.ALPHABETICAL, awaitItem())

            settingsManager.setSortOrder(SortOrder.TIME_WEIGHTED_USAGE)
            Assert.assertEquals(SortOrder.TIME_WEIGHTED_USAGE, awaitItem())
        }
    }

    @Test
    fun `setDoubleTapToLock - toggling multiple times - works correctly`() = runTest {
        settingsManager.doubleTapToLockEnabledFlow.test {
            Assert.assertEquals(false, awaitItem())

            settingsManager.setDoubleTapToLock(true)
            Assert.assertEquals(true, awaitItem())

            settingsManager.setDoubleTapToLock(false)
            Assert.assertEquals(false, awaitItem())

            settingsManager.setDoubleTapToLock(true)
            Assert.assertEquals(true, awaitItem())
        }
    }

    @Test
    fun `readabilityModeFlow - when no value is set - returns default`() = runTest {
        Assert.assertEquals("smart_contrast", settingsManager.readabilityModeFlow.first())
    }

    @Test
    fun `setReadabilityMode - correctly saves value`() = runTest {
        settingsManager.setReadabilityMode("light")

        val savedValue = fakeDataStore.data.first()[READABILITY_MODE_KEY]
        Assert.assertEquals("light", savedValue)
    }

    @Test
    fun `setReadabilityMode - when DataStore edit fails - does not crash`() = runTest {
        fakeDataStore.makeEditFail()

        settingsManager.setReadabilityMode("dark")

        Assert.assertEquals("smart_contrast", settingsManager.readabilityModeFlow.first())
    }

    @Test
    fun `readabilityModeFlow - emits new values when changed`() = runTest {
        settingsManager.readabilityModeFlow.test {
            Assert.assertEquals("smart_contrast", awaitItem())

            settingsManager.setReadabilityMode("dark")
            Assert.assertEquals("dark", awaitItem())

            settingsManager.setReadabilityMode("light")
            Assert.assertEquals("light", awaitItem())
        }
    }

    @Test
    fun `multiple flows - all work independently`() = runTest {
        settingsManager.setSortOrder(SortOrder.ALPHABETICAL)
        settingsManager.setDoubleTapToLock(false)
        settingsManager.setOnboardingCompleted()
        settingsManager.setReadabilityMode("dark")

        Assert.assertEquals(SortOrder.ALPHABETICAL, settingsManager.sortOrderFlow.first())
        assertFalse(settingsManager.doubleTapToLockEnabledFlow.first())
        assertTrue(settingsManager.onboardingCompletedFlow.first())
        Assert.assertEquals("dark", settingsManager.readabilityModeFlow.first())
    }

    @Test
    fun `sortOrderFlow - with corrupted data - returns default`() = runTest {
        fakeDataStore.edit { it[SORT_ORDER_KEY] = "" }

        Assert.assertEquals(SortOrder.TIME_WEIGHTED_USAGE, settingsManager.sortOrderFlow.first())
    }

    @Test
    fun `sortOrderFlow - with null value - returns default`() = runTest {
        Assert.assertEquals(SortOrder.TIME_WEIGHTED_USAGE, settingsManager.sortOrderFlow.first())
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
        assertTrue(settingsManager.showAlarmFlow.first())
    }

    @Test
    fun `setShowAlarm - correctly saves false`() = runTest {
        settingsManager.setShowAlarm(false)
        assertFalse(fakeDataStore.data.first()[SHOW_ALARM] ?: true)
    }

    @Test
    fun `setShowAlarm - correctly saves true`() = runTest {
        settingsManager.setShowAlarm(true)
        assertTrue(fakeDataStore.data.first()[SHOW_ALARM] ?: false)
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
            Assert.assertEquals(false, awaitItem())
            settingsManager.setShowAlarm(true)
            Assert.assertEquals(true, awaitItem())
            settingsManager.setShowAlarm(false)
            Assert.assertEquals(false, awaitItem())
        }
    }

    @Test
    fun `setShowAlarm - toggling multiple times - works correctly`() = runTest {
        settingsManager.showAlarmFlow.test {
            Assert.assertEquals(false, awaitItem())
            settingsManager.setShowAlarm(true)
            Assert.assertEquals(true, awaitItem())
            settingsManager.setShowAlarm(false)
            Assert.assertEquals(false, awaitItem())
            settingsManager.setShowAlarm(true)
            Assert.assertEquals(true, awaitItem())
        }
    }

    @Test
    fun `showAlarmFlow - independent from showCalendarEventFlow`() = runTest {
        settingsManager.setShowCalendarEvent(true)
        assertTrue(settingsManager.showCalendarEventFlow.first())
        assertFalse(settingsManager.showAlarmFlow.first())

        settingsManager.setShowAlarm(true)
        assertTrue(settingsManager.showAlarmFlow.first())
        assertTrue(settingsManager.showCalendarEventFlow.first())
    }

    @Test
    fun `multiple settings - showAlarm works with other settings`() = runTest {
        settingsManager.setSortOrder(SortOrder.ALPHABETICAL)
        settingsManager.setDoubleTapToLock(false)
        settingsManager.setShowCalendarEvent(true)
        settingsManager.setShowAlarm(false)

        Assert.assertEquals(SortOrder.ALPHABETICAL, settingsManager.sortOrderFlow.first())
        assertFalse(settingsManager.doubleTapToLockEnabledFlow.first())
        assertTrue(settingsManager.showCalendarEventFlow.first())
        assertFalse(settingsManager.showAlarmFlow.first())
    }

    // ========== GESTURE & AUTO TESTS ==========

    @Test
    fun `swipeDownToNotificationsEnabledFlow - defaults to false and updates correctly`() = runTest {
        assertFalse(settingsManager.swipeDownToNotificationsEnabledFlow.first())
        settingsManager.setSwipeDownToNotifications(true)
        assertTrue(settingsManager.swipeDownToNotificationsEnabledFlow.first())
    }

    @Test
    fun `autoShowKeyboardFlow - defaults to false and updates correctly`() = runTest {
        assertFalse(settingsManager.autoShowKeyboardFlow.first())
        settingsManager.setAutoShowKeyboard(true)
        assertTrue(settingsManager.autoShowKeyboardFlow.first())
    }

    @Test
    fun `autoLaunchAppFlow - defaults to false and updates correctly`() = runTest {
        assertFalse(settingsManager.autoLaunchAppFlow.first())
        settingsManager.setAutoLaunchApp(true)
        assertTrue(settingsManager.autoLaunchAppFlow.first())
    }

    // ========== THEME & APPEARANCE TESTS ==========

    @Test
    fun `textShadowEnabledFlow - defaults to TRUE and updates correctly`() = runTest {
        assertTrue(settingsManager.textShadowEnabledFlow.first(), "Default should be true")
        settingsManager.setTextShadowEnabled(false)
        assertFalse(settingsManager.textShadowEnabledFlow.first())
    }

    @Test
    fun `textColorFlow - defaults to 0 and updates correctly`() = runTest {
        Assert.assertEquals(0, settingsManager.textColorFlow.first())
        settingsManager.setTextColor(-16777216)
        Assert.assertEquals(-16777216, settingsManager.textColorFlow.first())
    }

    @Test
    fun `chipBackgroundColorFlow - defaults to 0 and updates correctly`() = runTest {
        Assert.assertEquals(0, settingsManager.chipBackgroundColorFlow.first())
        settingsManager.setChipBackgroundColor(-1)
        Assert.assertEquals(-1, settingsManager.chipBackgroundColorFlow.first())
    }

    @Test
    fun `isFontBoldStateFlow - updates correctly`() = runTest {
        settingsManager.setFontBold(true)
        assertTrue(settingsManager.isFontBoldStateFlow.first())
        settingsManager.setFontBold(false)
        assertFalse(settingsManager.isFontBoldStateFlow.first())
    }

    @Test
    fun `layoutScales - update correctly`() = runTest {
        settingsManager.setLayoutScale(1.5f)
        settingsManager.setVerticalPadding(2.0f)
        settingsManager.setContentTopMarginScale(0.5f)

        Assert.assertEquals(1.5f, settingsManager.layoutScaleStateFlow.first())
        Assert.assertEquals(2.0f, settingsManager.verticalPaddingStateFlow.first())
        Assert.assertEquals(0.5f, settingsManager.contentTopMarginScaleFlow.first())
    }

    // ========== HOME EVENT TESTS ==========

    @Test
    fun `showCalendarEventFlow - defaults to false and updates correctly`() = runTest {
        assertFalse(settingsManager.showCalendarEventFlow.first())
        settingsManager.setShowCalendarEvent(true)
        assertTrue(settingsManager.showCalendarEventFlow.first())
    }

    // ========== SPLIT MODE VALIDATION TEST ==========

    @Test
    fun `setSplitModeThreshold - validates input range correctly`() = runTest {
        settingsManager.setSplitModeThreshold(100)
        Assert.assertEquals(100, settingsManager.splitModeThresholdFlow.first())

        settingsManager.setSplitModeThreshold(-50)
        Assert.assertEquals(0, settingsManager.splitModeThresholdFlow.first())

        settingsManager.setSplitModeThreshold(1000)
        Assert.assertEquals(512, settingsManager.splitModeThresholdFlow.first())
    }

    // ========== PURGE TEST ==========

    @Test
    fun `purgeRepository - clears all settings keys`() = runTest {
        settingsManager.setSortOrder(SortOrder.ALPHABETICAL)
        settingsManager.setDoubleTapToLock(true)
        settingsManager.setShowAlarm(true)
        settingsManager.setSplitModeThreshold(100)

        settingsManager.purgeRepository()

        Assert.assertEquals(SortOrder.TIME_WEIGHTED_USAGE, settingsManager.sortOrderFlow.first())
        assertFalse(settingsManager.doubleTapToLockEnabledFlow.first())
        assertFalse(settingsManager.showAlarmFlow.first())
        Assert.assertEquals(0, settingsManager.splitModeThresholdFlow.first())
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

        val doomsdayManager = SettingsRepositoryImpl(mockDataStore, mockContext)

        val result = doomsdayManager.showAlarmFlow.first()

        assertFalse("Should fallback to default false on ClassCastException", result)
    }

    @Test
    fun `doomsday - unexpected RuntimeException during read - safe fallback`() = runTest {
        val mockDataStore = mockk<DataStore<Preferences>>()
        every { mockDataStore.data } returns flow {
            throw SecurityException("Read permission denied")
        }

        val doomsdayManager = SettingsRepositoryImpl(mockDataStore, mockContext)

        val result = doomsdayManager.sortOrderFlow.first()

        Assert.assertEquals(SortOrder.TIME_WEIGHTED_USAGE, result)
    }

    @Test
    fun `doomsday - integer overflow - threshold handles max int`() = runTest {
        settingsManager.setSplitModeThreshold(Int.MAX_VALUE)
        Assert.assertEquals(512, settingsManager.splitModeThresholdFlow.first())
    }

    @Test
    fun `doomsday - integer underflow - threshold handles min int`() = runTest {
        settingsManager.setSplitModeThreshold(Int.MIN_VALUE)
        Assert.assertEquals(0, settingsManager.splitModeThresholdFlow.first())
    }

    @Test
    fun `doomsday - rapid concurrent toggles - consistency check`() = runTest {
        repeat(100) { i ->
            settingsManager.setShowAlarm(i % 2 == 0)
        }

        val finalValue = settingsManager.showAlarmFlow.first()
        assertFalse("Final state should be false after odd number of toggles", finalValue)
    }

    private val SECURE_WINDOW = booleanPreferencesKey(AppConstants.PrefKeys.SECURE_WINDOW)

    // ========== SECURE WINDOW TESTS ==========

    @Test
    fun `secureWindowFlow - when no value is set - returns default false`() = runTest {
        assertFalse(settingsManager.secureWindowFlow.first())
    }

    @Test
    fun `setSecureWindow - correctly saves true`() = runTest {
        settingsManager.setSecureWindow(true)
        assertTrue(fakeDataStore.data.first()[SECURE_WINDOW] ?: false)
    }

    @Test
    fun `setSecureWindow - correctly saves false`() = runTest {
        settingsManager.setSecureWindow(false)
        assertFalse(fakeDataStore.data.first()[SECURE_WINDOW] ?: true)
    }

    @Test
    fun `setSecureWindow - when DataStore edit fails - does not crash`() = runTest {
        fakeDataStore.makeEditFail()
        settingsManager.setSecureWindow(true)
        assertFalse(settingsManager.secureWindowFlow.first())
    }

    @Test
    fun `setSecureWindow - when CancellationException - propagates it`() = runTest {
        fakeDataStore.makeCancellable()
        assertFailsWith<CancellationException> { settingsManager.setSecureWindow(true) }
    }

    @Test
    fun `secureWindowFlow - when DataStore read fails - returns default false`() = runTest {
        fakeDataStore.makeReadFail()
        assertFalse(settingsManager.secureWindowFlow.first())
    }

    @Test
    fun `secureWindowFlow - emits new values when changed`() = runTest {
        settingsManager.secureWindowFlow.test {
            assertEquals(false, awaitItem())
            settingsManager.setSecureWindow(true)
            assertEquals(true, awaitItem())
            settingsManager.setSecureWindow(false)
            assertEquals(false, awaitItem())
        }
    }
}
