package com.github.reygnn.kolibri_launcher

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertFailsWith

class DataMigrationManagerTest {

    @Mock private lateinit var context: Context
    @Mock private lateinit var sharedPreferences: SharedPreferences
    @Mock private lateinit var sharedPreferencesEditor: SharedPreferences.Editor

    private lateinit var fakeDataStore: FakeDataStore
    private lateinit var dataMigrationManager: DataMigrationManager

    private val VERSION_PREFS_NAME = "kolibri_data_version"
    private val KEY_DATA_VERSION = "data_version"
    private val TARGET_DATA_VERSION = 1

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        fakeDataStore = FakeDataStore()

        whenever(context.getSharedPreferences(eq(VERSION_PREFS_NAME), anyInt())).thenReturn(sharedPreferences)
        whenever(sharedPreferences.edit()).thenReturn(sharedPreferencesEditor)
        whenever(sharedPreferencesEditor.putInt(any(), anyInt())).thenReturn(sharedPreferencesEditor)

        dataMigrationManager = DataMigrationManager(context, fakeDataStore)
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `runMigrationIfNeeded - when first installation - sets version without clearing`() = runTest {
        whenever(sharedPreferences.getInt(eq(KEY_DATA_VERSION), anyInt())).thenReturn(0)
        fakeDataStore.setInitialData(preferencesOf(stringPreferencesKey("some_key") to "some_value"))

        dataMigrationManager.runMigrationIfNeeded()

        val data = fakeDataStore.data.first()
        assertFalse("DataStore should not be cleared on first installation", data.asMap().isEmpty())

        verify(sharedPreferencesEditor).putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION))
        verify(sharedPreferencesEditor).apply()
    }

    @Test
    fun `runMigrationIfNeeded - when version is current - does nothing`() = runTest {
        whenever(sharedPreferences.getInt(eq(KEY_DATA_VERSION), anyInt())).thenReturn(TARGET_DATA_VERSION)
        fakeDataStore.setInitialData(preferencesOf(stringPreferencesKey("some_key") to "some_value"))

        dataMigrationManager.runMigrationIfNeeded()

        val data = fakeDataStore.data.first()
        assertFalse(data.asMap().isEmpty())

        verify(sharedPreferencesEditor, never()).putInt(any(), anyInt())
    }

    @Test
    fun `isFirstLaunch - returns true when version is old`() {
        whenever(sharedPreferences.getInt(eq(KEY_DATA_VERSION), anyInt())).thenReturn(0)
        assertTrue(dataMigrationManager.isFirstLaunch())
    }

    @Test
    fun `isFirstLaunch - returns false when version is current`() {
        whenever(sharedPreferences.getInt(eq(KEY_DATA_VERSION), anyInt())).thenReturn(TARGET_DATA_VERSION)
        assertFalse(dataMigrationManager.isFirstLaunch())
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `runMigrationIfNeeded - when DataStore clear fails - still updates version`() = runTest {
        whenever(sharedPreferences.getInt(eq(KEY_DATA_VERSION), anyInt())).thenReturn(0)
        fakeDataStore.makeEditFail()

        // Should not crash
        dataMigrationManager.runMigrationIfNeeded()

        verify(sharedPreferencesEditor).putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION))
    }

    @Test
    fun `runMigrationIfNeeded - when SharedPreferences edit fails - does not crash`() = runTest {
        whenever(sharedPreferences.getInt(eq(KEY_DATA_VERSION), anyInt())).thenReturn(0)
        whenever(sharedPreferencesEditor.apply()).doAnswer {
            throw RuntimeException("Cannot write preferences")
        }

        // Should not crash
        dataMigrationManager.runMigrationIfNeeded()
    }

    @Test
    fun `runMigrationIfNeeded - when SharedPreferences putInt fails - does not crash`() = runTest {
        whenever(sharedPreferences.getInt(eq(KEY_DATA_VERSION), anyInt())).thenReturn(0)
        whenever(sharedPreferencesEditor.putInt(any(), anyInt())).doAnswer {
            throw RuntimeException("Cannot put int")
        }

        // Should not crash
        dataMigrationManager.runMigrationIfNeeded()
    }

    @Test
    fun `runMigrationIfNeeded - when CancellationException - propagates it`() = runTest {
        whenever(sharedPreferences.getInt(eq(KEY_DATA_VERSION), anyInt())).thenReturn(-1)  // ✅ Alte Version, nicht 0!
        fakeDataStore.makeCancellable()

        assertFailsWith<CancellationException> {
            dataMigrationManager.runMigrationIfNeeded()
        }
    }

    @Test
    fun `isFirstLaunch - when SharedPreferences throws exception - returns true`() {
        whenever(sharedPreferences.getInt(eq(KEY_DATA_VERSION), anyInt())).doAnswer {
            throw RuntimeException("Cannot read preferences")
        }

        // Should default to true (safe fallback)
        val result = dataMigrationManager.isFirstLaunch()

        assertTrue(result)
    }

    @Test
    fun `isFirstLaunch - when SharedPreferences is null - returns true`() {
        whenever(context.getSharedPreferences(eq(VERSION_PREFS_NAME), anyInt())).thenReturn(null)
        val managerWithNullPrefs = DataMigrationManager(context, fakeDataStore)

        val result = managerWithNullPrefs.isFirstLaunch()

        assertTrue(result)
    }

    @Test
    fun `runMigrationIfNeeded - called multiple times - only migrates once`() = runTest {
        whenever(sharedPreferences.getInt(eq(KEY_DATA_VERSION), anyInt()))
            .thenReturn(0)
            .thenReturn(TARGET_DATA_VERSION)

        dataMigrationManager.runMigrationIfNeeded()
        dataMigrationManager.runMigrationIfNeeded()

        // Should only update version once
        verify(sharedPreferencesEditor).putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION))
    }

    @Test
    fun `runMigrationIfNeeded - with negative version number - treats as old version`() = runTest {
        whenever(sharedPreferences.getInt(eq(KEY_DATA_VERSION), anyInt())).thenReturn(-1)

        dataMigrationManager.runMigrationIfNeeded()

        verify(sharedPreferencesEditor).putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION))
    }

    @Test
    fun `runMigrationIfNeeded - with very high version number - does nothing`() = runTest {
        whenever(sharedPreferences.getInt(eq(KEY_DATA_VERSION), anyInt())).thenReturn(999)
        fakeDataStore.setInitialData(preferencesOf(stringPreferencesKey("key") to "value"))

        dataMigrationManager.runMigrationIfNeeded()

        val data = fakeDataStore.data.first()
        assertFalse(data.asMap().isEmpty())

        verify(sharedPreferencesEditor, never()).putInt(any(), anyInt())
    }

    @Test
    fun `isFirstLaunch - with version equals to target - returns false`() {
        whenever(sharedPreferences.getInt(eq(KEY_DATA_VERSION), anyInt())).thenReturn(TARGET_DATA_VERSION)

        assertFalse(dataMigrationManager.isFirstLaunch())
    }

    @Test
    fun `isFirstLaunch - with version higher than target - returns false`() {
        whenever(sharedPreferences.getInt(eq(KEY_DATA_VERSION), anyInt())).thenReturn(TARGET_DATA_VERSION + 1)

        assertFalse(dataMigrationManager.isFirstLaunch())
    }

    @Test
    fun `runMigrationIfNeeded - when DataStore has no data - still sets version`() = runTest {
        whenever(sharedPreferences.getInt(eq(KEY_DATA_VERSION), anyInt())).thenReturn(0)
        // fakeDataStore is empty by default

        dataMigrationManager.runMigrationIfNeeded()

        verify(sharedPreferencesEditor).putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION))
        verify(sharedPreferencesEditor).apply()
    }

    @Test
    fun `runMigrationIfNeeded - when DataStore read fails - still updates version`() = runTest {
        whenever(sharedPreferences.getInt(eq(KEY_DATA_VERSION), anyInt())).thenReturn(0)
        fakeDataStore.makeReadFail()

        // Should not crash
        dataMigrationManager.runMigrationIfNeeded()

        verify(sharedPreferencesEditor).putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION))
    }
}