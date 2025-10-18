package com.github.reygnn.kolibri_launcher

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
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

    @Before
    fun setup() {
        fakeDataStore = FakeDataStore()
        settingsManager = SettingsManager(fakeDataStore, mockContext)
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `sortOrderFlow - when no value is set - returns default value`() = runTest {
        assertEquals(SortOrder.TIME_WEIGHTED_USAGE, settingsManager.sortOrderFlow.first())
    }

    @Test
    fun `sortOrderFlow - when a value is set - returns that value`() = runTest {
        fakeDataStore.edit { it[SORT_ORDER_KEY] = SortOrder.ALPHABETICAL.name }

        assertEquals(SortOrder.ALPHABETICAL, settingsManager.sortOrderFlow.first())
    }

    @Test
    fun `sortOrderFlow - when invalid value is stored - returns default value`() = runTest {
        fakeDataStore.edit { it[SORT_ORDER_KEY] = "INVALID_ENUM_VALUE" }

        assertEquals(SortOrder.TIME_WEIGHTED_USAGE, settingsManager.sortOrderFlow.first())
    }

    @Test
    fun `setSortOrder - correctly saves the value`() = runTest {
        settingsManager.setSortOrder(SortOrder.ALPHABETICAL)

        val savedValue = fakeDataStore.data.first()[SORT_ORDER_KEY]
        assertEquals(SortOrder.ALPHABETICAL.name, savedValue)
    }

    @Test
    fun `doubleTapToLockEnabledFlow - when no value is set - returns default true`() = runTest {
        assertTrue(settingsManager.doubleTapToLockEnabledFlow.first())
    }

    @Test
    fun `setDoubleTapToLock - correctly saves false`() = runTest {
        settingsManager.setDoubleTapToLock(false)

        val savedValue = fakeDataStore.data.first()[DOUBLE_TAP_TO_LOCK_ENABLED]
        assertFalse(savedValue ?: true)
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
            assertEquals(SortOrder.TIME_WEIGHTED_USAGE, awaitItem())

            settingsManager.setSortOrder(SortOrder.ALPHABETICAL)

            assertEquals(SortOrder.ALPHABETICAL, awaitItem())
        }
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

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

        settingsManager.setDoubleTapToLock(false)

        // Should maintain default value
        assertTrue(settingsManager.doubleTapToLockEnabledFlow.first())
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

        assertEquals(SortOrder.TIME_WEIGHTED_USAGE, result)
    }

    @Test
    fun `doubleTapToLockEnabledFlow - when DataStore read fails - returns default true`() = runTest {
        fakeDataStore.makeReadFail()

        val result = settingsManager.doubleTapToLockEnabledFlow.first()

        assertTrue(result)
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
            assertEquals(SortOrder.TIME_WEIGHTED_USAGE, awaitItem())

            settingsManager.setSortOrder(SortOrder.ALPHABETICAL)
            assertEquals(SortOrder.ALPHABETICAL, awaitItem())

            settingsManager.setSortOrder(SortOrder.TIME_WEIGHTED_USAGE)
            assertEquals(SortOrder.TIME_WEIGHTED_USAGE, awaitItem())
        }
    }

    @Test
    fun `setDoubleTapToLock - toggling multiple times - works correctly`() = runTest {
        settingsManager.doubleTapToLockEnabledFlow.test {
            assertEquals(true, awaitItem())

            settingsManager.setDoubleTapToLock(false)
            assertEquals(false, awaitItem())

            settingsManager.setDoubleTapToLock(true)
            assertEquals(true, awaitItem())

            settingsManager.setDoubleTapToLock(false)
            assertEquals(false, awaitItem())
        }
    }

    @Test
    fun `readabilityModeFlow - when no value is set - returns default`() = runTest {
        val result = settingsManager.readabilityModeFlow.first()

        assertEquals("smart_contrast", result)
    }

    @Test
    fun `setReadabilityMode - correctly saves value`() = runTest {
        settingsManager.setReadabilityMode("light")

        val savedValue = fakeDataStore.data.first()[READABILITY_MODE_KEY]
        assertEquals("light", savedValue)
    }

    @Test
    fun `setReadabilityMode - when DataStore edit fails - does not crash`() = runTest {
        fakeDataStore.makeEditFail()

        settingsManager.setReadabilityMode("dark")

        // Should maintain default
        assertEquals("smart_contrast", settingsManager.readabilityModeFlow.first())
    }

    @Test
    fun `readabilityModeFlow - emits new values when changed`() = runTest {
        settingsManager.readabilityModeFlow.test {
            assertEquals("smart_contrast", awaitItem())

            settingsManager.setReadabilityMode("dark")
            assertEquals("dark", awaitItem())

            settingsManager.setReadabilityMode("light")
            assertEquals("light", awaitItem())
        }
    }

    @Test
    fun `multiple flows - all work independently`() = runTest {
        settingsManager.setSortOrder(SortOrder.ALPHABETICAL)
        settingsManager.setDoubleTapToLock(false)
        settingsManager.setOnboardingCompleted()
        settingsManager.setReadabilityMode("dark")

        assertEquals(SortOrder.ALPHABETICAL, settingsManager.sortOrderFlow.first())
        assertFalse(settingsManager.doubleTapToLockEnabledFlow.first())
        assertTrue(settingsManager.onboardingCompletedFlow.first())
        assertEquals("dark", settingsManager.readabilityModeFlow.first())
    }

    @Test
    fun `sortOrderFlow - with corrupted data - returns default`() = runTest {
        fakeDataStore.edit { it[SORT_ORDER_KEY] = "" }

        assertEquals(SortOrder.TIME_WEIGHTED_USAGE, settingsManager.sortOrderFlow.first())
    }

    @Test
    fun `sortOrderFlow - with null value - returns default`() = runTest {
        // Explicitly don't set any value

        assertEquals(SortOrder.TIME_WEIGHTED_USAGE, settingsManager.sortOrderFlow.first())
    }
}