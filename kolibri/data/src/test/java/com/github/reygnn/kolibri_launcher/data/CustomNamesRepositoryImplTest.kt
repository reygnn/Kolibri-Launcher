package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
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

    // ========== EXISTING TESTS ==========

    @Test
    fun `getDisplayNameForPackage returns custom name if it exists`() = runTest {
        val packageName = "com.test.app"
        val customName = "My Awesome App"
        val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
        fakeDataStore.setInitialData(preferencesOf(nameKey to customName))

        val displayName = customNamesManager.getDisplayNameForPackage(packageName, "Original Name")

        assertThat(displayName).isEqualTo(customName)
    }

    @Test
    fun `getDisplayNameForPackage returns original name if no custom name exists`() = runTest {
        val displayName = customNamesManager.getDisplayNameForPackage("com.test.app", "Original Name")

        assertThat(displayName).isEqualTo("Original Name")
    }

    @Test
    fun `setCustomNameForPackage calls edit to save the new name`() = runTest {
        val result = customNamesManager.setCustomNameForPackage("com.test.app", "New Name")

        assertThat(result).isTrue()
        assertThat(fakeDataStore.updateDataCallCount > 0).isTrue()
    }

    @Test
    fun `setCustomNameForPackage with blank string calls remove logic`() = runTest {
        val result = customNamesManager.setCustomNameForPackage("com.test.app", "  ")

        assertThat(result).isTrue()
        assertThat(fakeDataStore.updateDataCallCount > 0).isTrue()
    }

    @Test
    fun `hasCustomNameForPackage returns true when name exists`() = runTest {
        val packageName = "com.test.app"
        val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
        fakeDataStore.setInitialData(preferencesOf(nameKey to "Some Name"))

        assertThat(customNamesManager.hasCustomNameForPackage(packageName)).isTrue()
    }

    @Test
    fun `hasCustomNameForPackage returns false when name does not exist`() = runTest {
        assertThat(customNamesManager.hasCustomNameForPackage("com.test.app")).isFalse()
    }

    @Test
    fun `setCustomNameForPackage - whenDataStoreFails - returnsFalse`() = runTest {
        fakeDataStore.makeEditFail()

        val result = customNamesManager.setCustomNameForPackage("com.test.app", "New Name")

        assertThat(result).isFalse()
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

        assertThat(result).isEqualTo("Original")
    }

    @Test
    fun `getDisplayNameForPackage - when DataStore throws RuntimeException - returns original name`() = runTest {
        // Lokales Mock nur für data-Property (kein edit → kein Extension-Function-Problem)
        val brokenStore = mockk<DataStore<Preferences>>()
        every { brokenStore.data } returns flow { throw RuntimeException("Corrupted data") }
        val manager = CustomNamesRepositoryImpl(brokenStore)

        val result = manager.getDisplayNameForPackage("com.test.app", "Original")

        assertThat(result).isEqualTo("Original")
    }

    @Test
    fun `hasCustomNameForPackage - when DataStore corrupted - returns false`() = runTest {
        fakeDataStore.makeReadFail()

        assertThat(customNamesManager.hasCustomNameForPackage("com.test.app")).isFalse()
    }

    @Test
    fun `removeCustomNameForPackage - when DataStore fails - returns false`() = runTest {
        fakeDataStore.makeEditFail()

        assertThat(customNamesManager.removeCustomNameForPackage("com.test.app")).isFalse()
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

        assertThat(result.size).isEqualTo(2)
        assertThat(result["com.app1"]).isEqualTo("Name 1")
        assertThat(result["com.app2"]).isEqualTo("Name 2")
    }

    @Test
    fun `getAllCustomNames - handles exceptions gracefully`() = runTest {
        fakeDataStore.makeReadFail()

        val result = customNamesManager.getAllCustomNames()

        assertThat(result.isEmpty()).isTrue()
    }

    @Test
    fun `setCustomNamesInBatch - saves multiple names in a single DataStore edit`() = runTest {
        val result = customNamesManager.setCustomNamesInBatch(
            mapOf("com.app1" to "Name 1", "com.app2" to "Name 2")
        )

        assertThat(result).isTrue()
        // IMPORTANT: exactly ONE DataStore transaction for the whole batch (so
        // customNamesFlow also re-emits exactly once).
        assertThat(fakeDataStore.updateDataCallCount).isEqualTo(1)
        assertThat(customNamesManager.getAllCustomNames()).isEqualTo(mapOf("com.app1" to "Name 1", "com.app2" to "Name 2"))
    }

    @Test
    fun `setCustomNamesInBatch - with empty map - does nothing`() = runTest {
        val result = customNamesManager.setCustomNamesInBatch(emptyMap())

        assertThat(result).isTrue()
        assertThat(fakeDataStore.updateDataCallCount).isEqualTo(0)
    }

    @Test
    fun `purgeRepository - removes keys`() = runTest {
        fakeDataStore.setInitialData(
            preferencesOf(stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + "com.app1") to "Name 1")
        )

        customNamesManager.purgeRepository()

        assertThat(fakeDataStore.updateDataCallCount > 0).isTrue()
        assertThat(customNamesManager.getAllCustomNames().isEmpty()).isTrue()
    }

    @Test
    fun `purgeRepository - handles exceptions gracefully`() = runTest {
        fakeDataStore.makeEditFail()

        // Should not crash
        customNamesManager.purgeRepository()

        // FakeDataStore zählt den Versuch auch bei Fehler
        assertThat(fakeDataStore.updateDataCallCount).isEqualTo(1)
    }
}