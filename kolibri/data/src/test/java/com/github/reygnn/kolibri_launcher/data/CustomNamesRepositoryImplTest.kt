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
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith

class CustomNamesRepositoryImplTest {

    @get:Rule
    val timberRule = TimberRule()

    // FakeDataStore statt mockk<DataStore<Preferences>>:
    // DataStore.edit() ist eine Extension Function — MockK kann sie nicht stubben.
    private lateinit var fakeDataStore: FakeDataStore

    @MockK(relaxed = true)
    private lateinit var context: Context

    private lateinit var customNamesManager: CustomNamesRepositoryImpl

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        fakeDataStore = FakeDataStore()
        customNamesManager = CustomNamesRepositoryImpl(fakeDataStore)
    }

    @Test
    fun `reconcileCustomNames - when DataStore edit fails - propagates (fail-closed)`() = runTest {
        val key = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + "com.gone")
        fakeDataStore.setInitialData(preferencesOf(key to "Drop"))
        fakeDataStore.makeEditFail()
        // Orphan com.gone, predicate reports it absent -> edit attempted -> throws
        // (no swallow; the skip is enforced upstream by runCleanup, §6.6).
        assertFailsWith<IOException> {
            customNamesManager.reconcileCustomNames(listOf("com.installed")) { false }
        }
    }

    @Test
    fun `reconcileCustomNames - when the candidate read fails - propagates (fail-closed)`() = runTest {
        // The candidate read is fail-CLOSED (dataStore.data.first(), NOT the
        // swallow-to-empty getAllCustomNames the spec forbids): a read error
        // propagates so the caller's runCleanup skips the store and deletes
        // nothing. A fail-open read would yield empty -> no candidate ->
        // "nothing deleted" too, so only asserting the throw distinguishes
        // fail-closed from the M1 regression (§6.1).
        val key = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + "com.gone")
        fakeDataStore.setInitialData(preferencesOf(key to "Drop"))
        fakeDataStore.makeReadFail()
        assertFailsWith<IOException> {
            customNamesManager.reconcileCustomNames(listOf("com.installed")) { false }
        }
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
        val manager = CustomNamesRepositoryImpl(brokenStore)

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
        val manager = CustomNamesRepositoryImpl(brokenStore)

        assertFailsWith<CancellationException> {
            manager.hasCustomNameForPackage("com.test.app")
        }
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
    fun `setCustomNamesInBatch - saves multiple names in a single DataStore edit`() = runTest {
        val result = customNamesManager.setCustomNamesInBatch(
            mapOf("com.app1" to "Name 1", "com.app2" to "Name 2")
        )

        Assert.assertTrue(result)
        // IMPORTANT: exactly ONE DataStore transaction for the whole batch (so
        // customNamesFlow also re-emits exactly once).
        Assert.assertEquals(1, fakeDataStore.updateDataCallCount)
        Assert.assertEquals(
            mapOf("com.app1" to "Name 1", "com.app2" to "Name 2"),
            customNamesManager.getAllCustomNames()
        )
    }

    @Test
    fun `setCustomNamesInBatch - with empty map - does nothing`() = runTest {
        val result = customNamesManager.setCustomNamesInBatch(emptyMap())

        Assert.assertTrue(result)
        Assert.assertEquals(0, fakeDataStore.updateDataCallCount)
    }

    @Test
    fun `reconcileCustomNames - removes orphans`() = runTest {
        val validKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + "com.installed")
        val orphanKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + "com.gone")
        fakeDataStore.setInitialData(preferencesOf(validKey to "Keep", orphanKey to "Drop"))

        customNamesManager.reconcileCustomNames(listOf("com.installed")) { false }

        // Orphan gone, valid kept.
        Assert.assertEquals(mapOf("com.installed" to "Keep"), customNamesManager.getAllCustomNames())
    }

    @Test
    fun `purgeRepository - removes keys`() = runTest {
        fakeDataStore.setInitialData(
            preferencesOf(stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + "com.app1") to "Name 1")
        )

        customNamesManager.purgeRepository()

        Assert.assertTrue(fakeDataStore.updateDataCallCount > 0)
        Assert.assertTrue(customNamesManager.getAllCustomNames().isEmpty())
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