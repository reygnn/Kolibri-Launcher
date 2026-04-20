package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFailsWith

class CustomNamesManagerTest {

    @get:Rule
    val timberRule = TimberRule()

    // FakeDataStore statt mockk<DataStore<Preferences>>:
    // DataStore.edit() ist eine Extension Function — MockK kann sie nicht stubben.
    private lateinit var fakeDataStore: FakeDataStore

    @MockK
    private lateinit var mockAppsUpdateTrigger: MutableSharedFlow<Unit>
    @MockK(relaxed = true)
    private lateinit var mockContext: Context

    private lateinit var customNamesManager: CustomNamesManager

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        fakeDataStore = FakeDataStore()
        customNamesManager = CustomNamesManager(fakeDataStore, mockAppsUpdateTrigger, mockContext)
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `getDisplayNameForPackage returns custom name if it exists`() = runTest {
        val packageName = "com.test.app"
        val customName = "My Awesome App"
        val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
        fakeDataStore.setInitialData(preferencesOf(nameKey to customName))

        val displayName = customNamesManager.getDisplayNameForPackage(packageName, "Original Name")

        Assert.assertEquals(customName, displayName)
    }

    @Test
    fun `getDisplayNameForPackage returns original name if no custom name exists`() = runTest {
        val displayName = customNamesManager.getDisplayNameForPackage("com.test.app", "Original Name")

        Assert.assertEquals("Original Name", displayName)
    }

    @Test
    fun `setCustomNameForPackage calls edit to save the new name`() = runTest {
        val result = customNamesManager.setCustomNameForPackage("com.test.app", "New Name")

        Assert.assertTrue(result)
        Assert.assertTrue(fakeDataStore.updateDataCallCount > 0)
    }

    @Test
    fun `setCustomNameForPackage with blank string calls remove logic`() = runTest {
        val result = customNamesManager.setCustomNameForPackage("com.test.app", "  ")

        Assert.assertTrue(result)
        Assert.assertTrue(fakeDataStore.updateDataCallCount > 0)
    }

    @Test
    fun `hasCustomNameForPackage returns true when name exists`() = runTest {
        val packageName = "com.test.app"
        val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
        fakeDataStore.setInitialData(preferencesOf(nameKey to "Some Name"))

        Assert.assertTrue(customNamesManager.hasCustomNameForPackage(packageName))
    }

    @Test
    fun `hasCustomNameForPackage returns false when name does not exist`() = runTest {
        Assert.assertFalse(customNamesManager.hasCustomNameForPackage("com.test.app"))
    }

    @Test
    fun `setCustomNameForPackage - whenDataStoreFails - returnsFalse`() = runTest {
        fakeDataStore.makeEditFail()

        val result = customNamesManager.setCustomNameForPackage("com.test.app", "New Name")

        Assert.assertFalse(result)
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `setCustomNameForPackage - when CancellationException thrown - propagates it`() = runTest {
        fakeDataStore.makeCancellable()

        assertFailsWith<CancellationException> {
            customNamesManager.setCustomNameForPackage("com.test.app", "New Name")
        }
    }

    @Test
    fun `getDisplayNameForPackage - when DataStore throws IOException - returns original name`() = runTest {
        fakeDataStore.makeReadFail()

        val result = customNamesManager.getDisplayNameForPackage("com.test.app", "Original")

        Assert.assertEquals("Original", result)
    }

    @Test
    fun `getDisplayNameForPackage - when DataStore throws RuntimeException - returns original name`() = runTest {
        // Lokales Mock nur für data-Property (kein edit → kein Extension-Function-Problem)
        val brokenStore = mockk<DataStore<Preferences>>()
        every { brokenStore.data } returns flow { throw RuntimeException("Corrupted data") }
        val manager = CustomNamesManager(brokenStore, mockAppsUpdateTrigger, mockContext)

        val result = manager.getDisplayNameForPackage("com.test.app", "Original")

        Assert.assertEquals("Original", result)
    }

    @Test
    fun `hasCustomNameForPackage - when DataStore corrupted - returns false`() = runTest {
        fakeDataStore.makeReadFail()

        Assert.assertFalse(customNamesManager.hasCustomNameForPackage("com.test.app"))
    }

    @Test
    fun `removeCustomNameForPackage - when DataStore fails - returns false`() = runTest {
        fakeDataStore.makeEditFail()

        Assert.assertFalse(customNamesManager.removeCustomNameForPackage("com.test.app"))
    }

    @Test
    fun `removeCustomNameForPackage - when CancellationException - propagates it`() = runTest {
        fakeDataStore.makeCancellable()

        assertFailsWith<CancellationException> {
            customNamesManager.removeCustomNameForPackage("com.test.app")
        }
    }

    @Test
    fun `hasCustomNameForPackage - when CancellationException - propagates it`() = runTest {
        // Lokales Mock nur für data-Property (kein edit → kein Extension-Function-Problem)
        val brokenStore = mockk<DataStore<Preferences>>()
        every { brokenStore.data } returns flow { throw CancellationException("Flow cancelled") }
        val manager = CustomNamesManager(brokenStore, mockAppsUpdateTrigger, mockContext)

        assertFailsWith<CancellationException> {
            manager.hasCustomNameForPackage("com.test.app")
        }
    }

    @Test
    fun `triggerCustomNameUpdate - calls emit on trigger flow`() = runTest {
        coEvery { mockAppsUpdateTrigger.emit(Unit) } returns Unit

        customNamesManager.triggerCustomNameUpdate()

        coVerify { mockAppsUpdateTrigger.emit(Unit) }
    }

    // ========== MISSING TESTS (Batch & Cleanup) ==========

    @Test
    fun `getAllCustomNames - returns filtered map of names`() = runTest {
        fakeDataStore.setInitialData(
            preferencesOf(
                stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + "com.app1") to "Name 1",
                stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + "com.app2") to "Name 2",
                stringPreferencesKey("other_unrelated_key") to "Should be ignored"
            )
        )

        val result = customNamesManager.getAllCustomNames()

        Assert.assertEquals(2, result.size)
        Assert.assertEquals("Name 1", result["com.app1"])
        Assert.assertEquals("Name 2", result["com.app2"])
    }

    @Test
    fun `getAllCustomNames - handles exceptions gracefully`() = runTest {
        fakeDataStore.makeReadFail()

        val result = customNamesManager.getAllCustomNames()

        Assert.assertTrue(result.isEmpty())
    }

    @Test
    fun `setCustomNamesInBatch - saves multiple names and triggers ONCE`() = runTest {
        coEvery { mockAppsUpdateTrigger.emit(Unit) } returns Unit

        val result = customNamesManager.setCustomNamesInBatch(
            mapOf("com.app1" to "Name 1", "com.app2" to "Name 2")
        )

        Assert.assertTrue(result)
        Assert.assertTrue(fakeDataStore.updateDataCallCount > 0)
        // WICHTIG: Trigger darf nur exakt 1x aufgerufen werden!
        coVerify(exactly = 1) { mockAppsUpdateTrigger.emit(Unit) }
    }

    @Test
    fun `setCustomNamesInBatch - with empty map - does nothing and NO trigger`() = runTest {
        val result = customNamesManager.setCustomNamesInBatch(emptyMap())

        Assert.assertTrue(result)
        Assert.assertEquals(0, fakeDataStore.updateDataCallCount)
        coVerify(exactly = 0) { mockAppsUpdateTrigger.emit(Unit) }
    }

    @Test
    fun `purgeRepository - removes keys and triggers update`() = runTest {
        coEvery { mockAppsUpdateTrigger.emit(Unit) } returns Unit

        customNamesManager.purgeRepository()

        Assert.assertTrue(fakeDataStore.updateDataCallCount > 0)
        coVerify { mockAppsUpdateTrigger.emit(Unit) }
    }

    @Test
    fun `purgeRepository - handles exceptions gracefully`() = runTest {
        fakeDataStore.makeEditFail()

        // Should not crash
        customNamesManager.purgeRepository()

        // FakeDataStore zählt den Versuch auch bei Fehler
        Assert.assertEquals(1, fakeDataStore.updateDataCallCount)
    }
}