package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.test.assertFailsWith

class CustomNamesManagerTest {

    @get:Rule
    val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var mockDataStore: DataStore<Preferences>
    private lateinit var customNamesManager: CustomNamesManager
    @Mock
    private lateinit var mockAppsUpdateTrigger: MutableSharedFlow<Unit>
    @Mock
    private lateinit var mockContext: Context

    @Before
    fun setup() {
        customNamesManager = CustomNamesManager(mockDataStore, mockAppsUpdateTrigger, mockContext)
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `getDisplayNameForPackage returns custom name if it exists`() = runTest {
        val packageName = "com.test.app"
        val customName = "My Awesome App"
        val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
        val testPreferences = preferencesOf(nameKey to customName)

        whenever(mockDataStore.data).thenReturn(flowOf(testPreferences))

        val displayName = customNamesManager.getDisplayNameForPackage(packageName, "Original Name")

        Assert.assertEquals(customName, displayName)
    }

    @Test
    fun `getDisplayNameForPackage returns original name if no custom name exists`() = runTest {
        val packageName = "com.test.app"
        val originalName = "Original Name"

        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))

        val displayName = customNamesManager.getDisplayNameForPackage(packageName, originalName)

        Assert.assertEquals(originalName, displayName)
    }

    @Test
    fun `setCustomNameForPackage calls edit to save the new name`() = runTest {
        whenever(mockDataStore.edit(any())).doReturn(preferencesOf())

        val result = customNamesManager.setCustomNameForPackage("com.test.app", "New Name")

        Assert.assertTrue(result)
        verify(mockDataStore).edit(any())
    }

    @Test
    fun `setCustomNameForPackage with blank string calls remove logic`() = runTest {
        val packageName = "com.test.app"
        whenever(mockDataStore.edit(any())).doReturn(preferencesOf())

        val result = customNamesManager.setCustomNameForPackage(packageName, "  ")

        Assert.assertTrue(result)
        verify(mockDataStore).edit(any())
    }

    @Test
    fun `hasCustomNameForPackage returns true when name exists`() = runTest {
        val packageName = "com.test.app"
        val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
        val testPreferences = preferencesOf(nameKey to "Some Name")
        whenever(mockDataStore.data).thenReturn(flowOf(testPreferences))

        Assert.assertTrue(customNamesManager.hasCustomNameForPackage(packageName))
    }

    @Test
    fun `hasCustomNameForPackage returns false when name does not exist`() = runTest {
        val packageName = "com.test.app"
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))

        Assert.assertFalse(customNamesManager.hasCustomNameForPackage(packageName))
    }

    @Test
    fun `setCustomNameForPackage - whenDataStoreFails - returnsFalse`() = runTest {
        whenever(mockDataStore.edit(any())).doAnswer {
            throw IOException("Disk is full")
        }

        val result = customNamesManager.setCustomNameForPackage("com.test.app", "New Name")

        Assert.assertFalse(result)
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `setCustomNameForPackage - when CancellationException thrown - propagates it`() = runTest {
        whenever(mockDataStore.edit(any())).doAnswer {
            throw CancellationException("Test cancellation")
        }

        assertFailsWith<CancellationException> {
            customNamesManager.setCustomNameForPackage("com.test.app", "New Name")
        }
    }

    @Test
    fun `getDisplayNameForPackage - when DataStore throws IOException - returns original name`() =
        runTest {
            whenever(mockDataStore.data).thenReturn(flow {
                throw IOException("Disk error")
            })

            val result = customNamesManager.getDisplayNameForPackage("com.test.app", "Original")

            Assert.assertEquals("Original", result)
        }

    @Test
    fun `getDisplayNameForPackage - when DataStore throws RuntimeException - returns original name`() =
        runTest {
            whenever(mockDataStore.data).thenReturn(flow {
                throw RuntimeException("Corrupted data")
            })

            val result = customNamesManager.getDisplayNameForPackage("com.test.app", "Original")

            Assert.assertEquals("Original", result)
        }

    @Test
    fun `hasCustomNameForPackage - when DataStore corrupted - returns false`() = runTest {
        whenever(mockDataStore.data).thenReturn(flow {
            throw RuntimeException("Corrupted data")
        })

        val result = customNamesManager.hasCustomNameForPackage("com.test.app")

        Assert.assertFalse(result)
    }

    @Test
    fun `removeCustomNameForPackage - when DataStore fails - returns false`() = runTest {
        whenever(mockDataStore.edit(any())).doAnswer {
            throw IOException("Write error")
        }

        val result = customNamesManager.removeCustomNameForPackage("com.test.app")

        Assert.assertFalse(result)
    }

    @Test
    fun `removeCustomNameForPackage - when CancellationException - propagates it`() = runTest {
        whenever(mockDataStore.edit(any())).doAnswer {
            throw CancellationException("Cancelled")
        }

        assertFailsWith<CancellationException> {
            customNamesManager.removeCustomNameForPackage("com.test.app")
        }
    }

    @Test
    fun `hasCustomNameForPackage - when CancellationException - propagates it`() = runTest {
        whenever(mockDataStore.data).thenReturn(flow {
            throw CancellationException("Flow cancelled")
        })

        assertFailsWith<CancellationException> {
            customNamesManager.hasCustomNameForPackage("com.test.app")
        }
    }

    @Test
    fun `triggerCustomNameUpdate - calls emit on trigger flow`() = runTest {
        customNamesManager.triggerCustomNameUpdate()
        verify(mockAppsUpdateTrigger).emit(Unit)
    }

    // ========== MISSING TESTS (Batch & Cleanup) ==========

    @Test
    fun `getAllCustomNames - returns filtered map of names`() = runTest {
        // Arrange
        val prefs = preferencesOf(
            stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + "com.app1") to "Name 1",
            stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + "com.app2") to "Name 2",
            stringPreferencesKey("other_unrelated_key") to "Should be ignored"
        )
        whenever(mockDataStore.data).thenReturn(flowOf(prefs))

        // Act
        val result = customNamesManager.getAllCustomNames()

        // Assert
        Assert.assertEquals(2, result.size)
        Assert.assertEquals("Name 1", result["com.app1"])
        Assert.assertEquals("Name 2", result["com.app2"])
    }

    @Test
    fun `getAllCustomNames - handles exceptions gracefully`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flow { throw RuntimeException("Fail") })

        // Act
        val result = customNamesManager.getAllCustomNames()

        // Assert
        Assert.assertTrue(result.isEmpty())
    }

    @Test
    fun `setCustomNamesInBatch - saves multiple names and triggers ONCE`() = runTest {
        // Arrange
        whenever(mockDataStore.edit(any())).doReturn(preferencesOf())

        val namesToSet = mapOf(
            "com.app1" to "Name 1",
            "com.app2" to "Name 2"
        )

        // Act
        val result = customNamesManager.setCustomNamesInBatch(namesToSet)

        // Assert
        Assert.assertTrue(result)
        verify(mockDataStore).edit(any())
        // WICHTIG: Trigger darf nur exakt 1x aufgerufen werden, nicht pro Name!
        verify(mockAppsUpdateTrigger).emit(Unit)
    }

    @Test
    fun `setCustomNamesInBatch - with empty map - does nothing and NO trigger`() = runTest {
        // Act
        val result = customNamesManager.setCustomNamesInBatch(emptyMap())

        // Assert
        Assert.assertTrue(result)
        verify(mockDataStore, never()).edit(any())
        verify(mockAppsUpdateTrigger, never()).emit(any())
    }

    @Test
    fun `purgeRepository - removes keys and triggers update`() = runTest {
        // Arrange
        whenever(mockDataStore.edit(any())).doReturn(preferencesOf())

        // Act
        customNamesManager.purgeRepository()

        // Assert
        verify(mockDataStore).edit(any())
        verify(mockAppsUpdateTrigger).emit(Unit)
    }

    @Test
    fun `purgeRepository - handles exceptions gracefully`() = runTest {
        // Arrange
        whenever(mockDataStore.edit(any())).doAnswer { throw RuntimeException("Fail") }

        // Act - should not crash
        customNamesManager.purgeRepository()

        // Assert
        verify(mockDataStore).edit(any())
    }
}