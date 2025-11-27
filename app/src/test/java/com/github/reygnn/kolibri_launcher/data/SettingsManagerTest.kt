package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import junit.framework.TestCase.assertFalse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
@RunWith(MockitoJUnitRunner::class)
class SettingsManagerTest {

    private lateinit var fakeDataStore: FakeDataStore
    private lateinit var settingsManager: SettingsManager

    @Mock
    private lateinit var mockContext: Context

    private val SORT_ORDER_KEY = stringPreferencesKey("app_drawer_sort_order")
    private val DOUBLE_TAP_TO_LOCK_ENABLED = booleanPreferencesKey("double_tap_to_lock_enabled")
    private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    private val READABILITY_MODE_KEY = stringPreferencesKey("text_readability_mode")
    private val SHOW_CALENDAR_EVENT = booleanPreferencesKey("show_calendar_event")
    private val SHOW_ALARM = booleanPreferencesKey("show_alarm")

    @Before
    fun setup() {
        fakeDataStore = FakeDataStore()
        settingsManager = SettingsManager(fakeDataStore, mockContext)
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

        // Should not crash
        settingsManager.setSortOrder(SortOrder.ALPHABETICAL)

        // Value should not be saved
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

        // Should maintain default value
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

        val result = settingsManager.sortOrderFlow.first()

        Assert.assertEquals(SortOrder.TIME_WEIGHTED_USAGE, result)
    }

    @Test
    fun `doubleTapToLockEnabledFlow - when DataStore read fails - returns default false`() =
        runTest {
            fakeDataStore.makeReadFail()

            val result = settingsManager.doubleTapToLockEnabledFlow.first()

            assertFalse(result)
        }

    @Test
    fun `onboardingCompletedFlow - when DataStore read fails - returns default false`() = runTest {
        fakeDataStore.makeReadFail()

        val result = settingsManager.onboardingCompletedFlow.first()

        assertFalse(result)
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
        val result = settingsManager.readabilityModeFlow.first()

        Assert.assertEquals("smart_contrast", result)
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

        // Should maintain default
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
        // Explicitly don't set any value

        Assert.assertEquals(SortOrder.TIME_WEIGHTED_USAGE, settingsManager.sortOrderFlow.first())
    }

    // ========== SHOW ALARM TESTS ==========

    @Test
    fun `showAlarmFlow - when no value is set - returns default false`() = runTest {
        val result = settingsManager.showAlarmFlow.first()

        assertFalse(result)
    }

    @Test
    fun `showAlarmFlow - when value is set to false - returns false`() = runTest {
        fakeDataStore.edit { it[SHOW_ALARM] = false }

        val result = settingsManager.showAlarmFlow.first()

        assertFalse(result)
    }

    @Test
    fun `showAlarmFlow - when value is set to true - returns true`() = runTest {
        fakeDataStore.edit { it[SHOW_ALARM] = true }

        val result = settingsManager.showAlarmFlow.first()

        assertTrue(result)
    }

    @Test
    fun `setShowAlarm - correctly saves false`() = runTest {
        settingsManager.setShowAlarm(false)

        val savedValue = fakeDataStore.data.first()[SHOW_ALARM]
        assertFalse(savedValue ?: true)
    }

    @Test
    fun `setShowAlarm - correctly saves true`() = runTest {
        settingsManager.setShowAlarm(true)

        val savedValue = fakeDataStore.data.first()[SHOW_ALARM]
        assertTrue(savedValue ?: false)
    }

    @Test
    fun `setShowAlarm - when DataStore edit fails - does not crash`() = runTest {
        fakeDataStore.makeEditFail()
        settingsManager.setShowAlarm(true)   // ✓ Versuche ÄNDERN zu true
        assertFalse(settingsManager.showAlarmFlow.first())  // ✓ Bleibt beim Default false
    }

    @Test
    fun `setShowAlarm - when CancellationException - propagates it`() = runTest {
        fakeDataStore.makeCancellable()

        assertFailsWith<CancellationException> {
            settingsManager.setShowAlarm(false)
        }
    }

    @Test
    fun `showAlarmFlow - when DataStore read fails - returns default true`() = runTest {
        fakeDataStore.makeReadFail()

        val result = settingsManager.showAlarmFlow.first()

        assertFalse(result)
    }

    @Test
    fun `showAlarmFlow - emits new values when changed`() = runTest {
        settingsManager.showAlarmFlow.test {
            Assert.assertEquals(false, awaitItem())  // Default

            settingsManager.setShowAlarm(true)   // ✓ Ändere zu true
            Assert.assertEquals(true, awaitItem())

            settingsManager.setShowAlarm(false)  // ✓ Zurück zu false
            Assert.assertEquals(false, awaitItem())
        }
    }

    @Test
    fun `setShowAlarm - toggling multiple times - works correctly`() = runTest {
        settingsManager.showAlarmFlow.test {
            Assert.assertEquals(false, awaitItem())  // Default

            settingsManager.setShowAlarm(true)   // ✓ Toggle zu true
            Assert.assertEquals(true, awaitItem())

            settingsManager.setShowAlarm(false)  // ✓ Toggle zu false
            Assert.assertEquals(false, awaitItem())

            settingsManager.setShowAlarm(true)   // ✓ Toggle zu true
            Assert.assertEquals(true, awaitItem())
        }
    }

    @Test
    fun `showAlarmFlow - independent from showCalendarEventFlow`() = runTest {
        // Set calendar to true
        settingsManager.setShowCalendarEvent(true)
        assertTrue(settingsManager.showCalendarEventFlow.first())

        // Alarm should still be default true
        assertFalse(settingsManager.showAlarmFlow.first())

        // Change alarm to true
        settingsManager.setShowAlarm(true)
        assertTrue(settingsManager.showAlarmFlow.first())

        // Calendar should still be true
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
        // Default check
        assertFalse(settingsManager.swipeDownToNotificationsEnabledFlow.first())

        // Act
        settingsManager.setSwipeDownToNotifications(true)

        // Assert
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
        // WICHTIG: Default ist hier TRUE im Manager!
        assertTrue(settingsManager.textShadowEnabledFlow.first(), "Default should be true")

        settingsManager.setTextShadowEnabled(false)

        assertFalse(settingsManager.textShadowEnabledFlow.first())
    }

    @Test
    fun `textColorFlow - defaults to 0 and updates correctly`() = runTest {
        Assert.assertEquals(0, settingsManager.textColorFlow.first())

        val newColor = -16777216 // Black
        settingsManager.setTextColor(newColor)

        Assert.assertEquals(newColor, settingsManager.textColorFlow.first())
    }

    @Test
    fun `chipBackgroundColorFlow - defaults to 0 and updates correctly`() = runTest {
        Assert.assertEquals(0, settingsManager.chipBackgroundColorFlow.first())

        val newColor = -1 // White
        settingsManager.setChipBackgroundColor(newColor)

        Assert.assertEquals(newColor, settingsManager.chipBackgroundColorFlow.first())
    }

    @Test
    fun `isFontBoldStateFlow - updates correctly`() = runTest {
        // Default ist AppConstant dependent, wir testen hier Set/Get
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
        // Test 1: Valid value
        settingsManager.setSplitModeThreshold(100)
        Assert.assertEquals(100, settingsManager.splitModeThresholdFlow.first())

        // Test 2: Too low (negative) -> should clamp to 0
        settingsManager.setSplitModeThreshold(-50)
        Assert.assertEquals(0, settingsManager.splitModeThresholdFlow.first())

        // Test 3: Too high (> 512) -> should clamp to 512
        settingsManager.setSplitModeThreshold(1000)
        Assert.assertEquals(512, settingsManager.splitModeThresholdFlow.first())
    }

    // ========== PURGE TEST ==========

    @Test
    fun `purgeRepository - clears all settings keys`() = runTest {
        // Arrange: Setze diverse Werte, um sicherzugehen, dass sie gelöscht werden
        settingsManager.setSortOrder(SortOrder.ALPHABETICAL)
        settingsManager.setDoubleTapToLock(true)
        settingsManager.setShowAlarm(true)
        settingsManager.setSplitModeThreshold(100)

        // Act
        settingsManager.purgeRepository()

        // Assert: Alle Flows sollten auf ihre Defaults zurückfallen
        Assert.assertEquals(SortOrder.TIME_WEIGHTED_USAGE, settingsManager.sortOrderFlow.first()) // Default
        assertFalse(settingsManager.doubleTapToLockEnabledFlow.first()) // Default false
        assertFalse(settingsManager.showAlarmFlow.first()) // Default false
        Assert.assertEquals(0, settingsManager.splitModeThresholdFlow.first()) // Default 0
    }

    // ========================================================================
    // DOOMSDAY TESTS - ROCKY BALBOA EDITION
    // ========================================================================

    @Test
    fun `doomsday - corrupted types (ClassCastException) - safe fallback`() = runTest {
        // SZENARIO: Die DataStore Datei ist "valide" (keine IOException), aber die Daten-Typen
        // sind falsch (z.B. String statt Boolean). Das passiert oft bei manuellen Hacks oder
        // kaputten Migrationen. DataStore wirft ClassCastException beim Zugriff.

        // Wir nutzen hier einen Mock statt FakeDataStore, um die Exception zu erzwingen.
        val mockDataStore: DataStore<Preferences> = mock()

        // Wir simulieren einen Flow, der beim Zugriff knallt
        whenever(mockDataStore.data).thenReturn(flow {
            throw ClassCastException("Expected Boolean but got String")
        })

        // Instanziiere den Manager mit dem "kaputten" DataStore
        val doomsdayManager = SettingsManager(mockDataStore, mockContext)

        // Act: Versuche zu lesen
        // Die Flow 'catch' Logik im SettingsManager sollte greifen.
        // Falls nicht, würde der Test crashen.
        val result = doomsdayManager.showAlarmFlow.first()

        // Assert: Sollte auf Default zurückfallen statt zu crashen
        assertFalse("Should fallback to default false on ClassCastException", result)
    }

    @Test
    fun `doomsday - unexpected RuntimeException during read - safe fallback`() = runTest {
        // SZENARIO: SecurityException (Permission entzogen zur Laufzeit) oder
        // andere Runtime-Fehler aus dem Framework.

        val mockDataStore: DataStore<Preferences> = mock()
        whenever(mockDataStore.data).thenReturn(flow {
            throw SecurityException("Read permission denied")
        })

        val doomsdayManager = SettingsManager(mockDataStore, mockContext)

        // Act
        val result = doomsdayManager.sortOrderFlow.first()

        // Assert: Default statt Crash
        Assert.assertEquals(SortOrder.TIME_WEIGHTED_USAGE, result)
    }

    @Test
    fun `doomsday - integer overflow - threshold handles max int`() = runTest {
        // SZENARIO: Irgendjemand (oder ein Bitflip) übergibt MAX_INT.
        // Die Validierung darf nicht überlaufen.

        settingsManager.setSplitModeThreshold(Int.MAX_VALUE)

        val result = settingsManager.splitModeThresholdFlow.first()

        // Sollte auf 512 gecapped sein (deine definierte Obergrenze)
        Assert.assertEquals(512, result)
    }

    @Test
    fun `doomsday - integer underflow - threshold handles min int`() = runTest {
        // SZENARIO: Integer Underflow / MIN_INT

        settingsManager.setSplitModeThreshold(Int.MIN_VALUE)

        val result = settingsManager.splitModeThresholdFlow.first()

        // Sollte auf 0 gecapped sein
        Assert.assertEquals(0, result)
    }

    @Test
    fun `doomsday - rapid concurrent toggles - consistency check`() = runTest {
        // SZENARIO: User hämmert auf den Toggle-Button (100x in 10ms).
        // DataStore serialisiert Writes, aber wir wollen sicherstellen, dass
        // der letzte Wert gewinnt und keine Race Conditions entstehen.

        // 100x toggeln
        repeat(100) { i ->
            settingsManager.setShowAlarm(i % 2 == 0) // true, false, true, false...
        }

        // Der letzte Aufruf (99) ist ungerade -> false.
        // Da DataStore asynchron ist, warten wir auf den Flow.
        val finalValue = settingsManager.showAlarmFlow.first()

        // Hinweis: Im echten DataStore garantiert 'edit' die Reihenfolge.
        // Der Fake sollte das auch tun.
        assertFalse("Final state should be false after odd number of toggles", finalValue)
    }
}
