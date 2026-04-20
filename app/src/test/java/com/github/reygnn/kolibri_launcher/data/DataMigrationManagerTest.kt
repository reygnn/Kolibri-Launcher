package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFailsWith

class DataMigrationManagerTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var context: Context
    @MockK
    private lateinit var sharedPreferences: SharedPreferences
    @MockK
    private lateinit var sharedPreferencesEditor: SharedPreferences.Editor

    private lateinit var fakeDataStore: FakeDataStore
    private lateinit var dataMigrationManager: DataMigrationManager

    private val VERSION_PREFS_NAME = "kolibri_data_version"
    private val KEY_DATA_VERSION = "data_version"
    private val TARGET_DATA_VERSION = 1

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        fakeDataStore = FakeDataStore()

        every { context.getSharedPreferences(eq(VERSION_PREFS_NAME), any()) } returns sharedPreferences
        every { sharedPreferences.edit() } returns sharedPreferencesEditor
        every { sharedPreferencesEditor.putInt(any(), any()) } returns sharedPreferencesEditor
        every { sharedPreferencesEditor.commit() } returns true

        dataMigrationManager = DataMigrationManager(context, fakeDataStore)
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `runMigrationIfNeeded - when first installation - sets version without clearing`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 0
        fakeDataStore.setInitialData(preferencesOf(stringPreferencesKey("some_key") to "some_value"))

        dataMigrationManager.runMigrationIfNeeded()

        val data = fakeDataStore.data.first()
        Assert.assertFalse("DataStore should not be cleared on first installation", data.asMap().isEmpty())

        verify { sharedPreferencesEditor.putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION)) }
        verify { sharedPreferencesEditor.commit() }
    }

    @Test
    fun `runMigrationIfNeeded - when version is current - does nothing`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns TARGET_DATA_VERSION
        fakeDataStore.setInitialData(preferencesOf(stringPreferencesKey("some_key") to "some_value"))

        dataMigrationManager.runMigrationIfNeeded()

        val data = fakeDataStore.data.first()
        Assert.assertFalse(data.asMap().isEmpty())

        verify(exactly = 0) { sharedPreferencesEditor.putInt(any(), any()) }
    }

    @Test
    fun `isFirstLaunch - returns true when version is old`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 0
        Assert.assertTrue(dataMigrationManager.isFirstLaunch())
    }

    @Test
    fun `isFirstLaunch - returns false when version is current`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns TARGET_DATA_VERSION
        Assert.assertFalse(dataMigrationManager.isFirstLaunch())
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `runMigrationIfNeeded - when DataStore clear fails - still updates version`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 0
        fakeDataStore.makeEditFail()

        dataMigrationManager.runMigrationIfNeeded()

        verify { sharedPreferencesEditor.putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION)) }
    }

    @Test
    fun `runMigrationIfNeeded - when SharedPreferences edit fails - does not crash`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 0
        every { sharedPreferencesEditor.commit() } throws RuntimeException("Cannot write preferences")

        dataMigrationManager.runMigrationIfNeeded()
    }

    @Test
    fun `runMigrationIfNeeded - when SharedPreferences putInt fails - does not crash`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 0
        every { sharedPreferencesEditor.putInt(any(), any()) } throws RuntimeException("Cannot put int")

        dataMigrationManager.runMigrationIfNeeded()
    }

    @Test
    fun `runMigrationIfNeeded - when CancellationException - propagates it`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns -1
        fakeDataStore.makeCancellable()

        assertFailsWith<CancellationException> {
            dataMigrationManager.runMigrationIfNeeded()
        }
    }

    @Test
    fun `isFirstLaunch - when SharedPreferences throws exception - returns true`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } throws RuntimeException("Cannot read preferences")

        Assert.assertTrue(dataMigrationManager.isFirstLaunch())
    }

    @Test
    fun `isFirstLaunch - when SharedPreferences is null - returns true`() = runTest {
        every { context.getSharedPreferences(eq(VERSION_PREFS_NAME), any()) } returns null
        val managerWithNullPrefs = DataMigrationManager(context, fakeDataStore)

        Assert.assertTrue(managerWithNullPrefs.isFirstLaunch())
    }

    @Test
    fun `runMigrationIfNeeded - called multiple times - only migrates once`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) }
            .returnsMany(0, TARGET_DATA_VERSION)

        dataMigrationManager.runMigrationIfNeeded()
        dataMigrationManager.runMigrationIfNeeded()

        verify(exactly = 1) { sharedPreferencesEditor.putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION)) }
    }

    @Test
    fun `runMigrationIfNeeded - with negative version number - treats as old version`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns -1

        dataMigrationManager.runMigrationIfNeeded()

        verify { sharedPreferencesEditor.putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION)) }
    }

    @Test
    fun `runMigrationIfNeeded - with very high version number - does nothing`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 999
        fakeDataStore.setInitialData(preferencesOf(stringPreferencesKey("key") to "value"))

        dataMigrationManager.runMigrationIfNeeded()

        val data = fakeDataStore.data.first()
        Assert.assertFalse(data.asMap().isEmpty())
        verify(exactly = 0) { sharedPreferencesEditor.putInt(any(), any()) }
    }

    @Test
    fun `isFirstLaunch - with version equals to target - returns false`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns TARGET_DATA_VERSION
        Assert.assertFalse(dataMigrationManager.isFirstLaunch())
    }

    @Test
    fun `isFirstLaunch - with version higher than target - returns false`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns TARGET_DATA_VERSION + 1
        Assert.assertFalse(dataMigrationManager.isFirstLaunch())
    }

    @Test
    fun `runMigrationIfNeeded - when DataStore has no data - still sets version`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 0

        dataMigrationManager.runMigrationIfNeeded()

        verify { sharedPreferencesEditor.putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION)) }
        verify { sharedPreferencesEditor.commit() }
    }

    @Test
    fun `runMigrationIfNeeded - when DataStore read fails - still updates version`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns 0
        fakeDataStore.makeReadFail()

        dataMigrationManager.runMigrationIfNeeded()

        verify { sharedPreferencesEditor.putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION)) }
    }

    @Test
    fun `runMigrationIfNeeded - when old version detected - clears DataStore content`() = runTest {
        every { sharedPreferences.getInt(eq(KEY_DATA_VERSION), any()) } returns -1
        fakeDataStore.setInitialData(preferencesOf(stringPreferencesKey("existing_key") to "must_be_deleted"))
        Assert.assertFalse(fakeDataStore.data.first().asMap().isEmpty())

        dataMigrationManager.runMigrationIfNeeded()

        val data = fakeDataStore.data.first()
        Assert.assertTrue("DataStore should be empty after migration from old version", data.asMap().isEmpty())
        verify { sharedPreferencesEditor.putInt(eq(KEY_DATA_VERSION), eq(TARGET_DATA_VERSION)) }
    }
}