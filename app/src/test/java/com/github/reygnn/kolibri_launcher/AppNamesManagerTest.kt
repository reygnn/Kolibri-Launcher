package com.github.reygnn.kolibri_launcher

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.test.assertFailsWith

class AppNamesManagerTest {

    @get:Rule
    val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var mockDataStore: DataStore<Preferences>
    private lateinit var appNamesManager: AppNamesManager
    @Mock
    private lateinit var mockAppsUpdateTrigger: MutableSharedFlow<Unit>
    @Mock
    private lateinit var mockContext: Context

    @Before
    fun setup() {
        appNamesManager = AppNamesManager(mockDataStore, mockAppsUpdateTrigger, mockContext)
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `getDisplayNameForPackage returns custom name if it exists`() = runTest {
        val packageName = "com.test.app"
        val customName = "My Awesome App"
        val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
        val testPreferences = preferencesOf(nameKey to customName)

        whenever(mockDataStore.data).thenReturn(flowOf(testPreferences))

        val displayName = appNamesManager.getDisplayNameForPackage(packageName, "Original Name")

        assertEquals(customName, displayName)
    }

    @Test
    fun `getDisplayNameForPackage returns original name if no custom name exists`() = runTest {
        val packageName = "com.test.app"
        val originalName = "Original Name"

        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))

        val displayName = appNamesManager.getDisplayNameForPackage(packageName, originalName)

        assertEquals(originalName, displayName)
    }

    @Test
    fun `setCustomNameForPackage calls edit to save the new name`() = runTest {
        whenever(mockDataStore.edit(any())).doReturn(preferencesOf())

        val result = appNamesManager.setCustomNameForPackage("com.test.app", "New Name")

        assertTrue(result)
        verify(mockDataStore).edit(any())
    }

    @Test
    fun `setCustomNameForPackage with blank string calls remove logic`() = runTest {
        val packageName = "com.test.app"
        whenever(mockDataStore.edit(any())).doReturn(preferencesOf())

        val result = appNamesManager.setCustomNameForPackage(packageName, "  ")

        assertTrue(result)
        verify(mockDataStore).edit(any())
    }

    @Test
    fun `hasCustomNameForPackage returns true when name exists`() = runTest {
        val packageName = "com.test.app"
        val nameKey = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + packageName)
        val testPreferences = preferencesOf(nameKey to "Some Name")
        whenever(mockDataStore.data).thenReturn(flowOf(testPreferences))

        assertTrue(appNamesManager.hasCustomNameForPackage(packageName))
    }

    @Test
    fun `hasCustomNameForPackage returns false when name does not exist`() = runTest {
        val packageName = "com.test.app"
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))

        assertFalse(appNamesManager.hasCustomNameForPackage(packageName))
    }

    @Test
    fun `setCustomNameForPackage - whenDataStoreFails - returnsFalse`() = runTest {
        whenever(mockDataStore.edit(any())).doAnswer {
            throw IOException("Disk is full")
        }

        val result = appNamesManager.setCustomNameForPackage("com.test.app", "New Name")

        assertFalse(result)
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `setCustomNameForPackage - when CancellationException thrown - propagates it`() = runTest {
        // Arrange
        whenever(mockDataStore.edit(any())).doAnswer {
            throw CancellationException("Test cancellation")
        }

        // Act & Assert
        assertFailsWith<CancellationException> {
            appNamesManager.setCustomNameForPackage("com.test.app", "New Name")
        }
    }

    @Test
    fun `getDisplayNameForPackage - when DataStore throws IOException - returns original name`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flow {
            throw IOException("Disk error")
        })

        // Act
        val result = appNamesManager.getDisplayNameForPackage("com.test.app", "Original")

        // Assert
        assertEquals("Original", result)
    }

    @Test
    fun `getDisplayNameForPackage - when DataStore throws RuntimeException - returns original name`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flow {
            throw RuntimeException("Corrupted data")
        })

        // Act
        val result = appNamesManager.getDisplayNameForPackage("com.test.app", "Original")

        // Assert
        assertEquals("Original", result)
    }

    @Test
    fun `hasCustomNameForPackage - when DataStore corrupted - returns false`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flow {
            throw RuntimeException("Corrupted data")
        })

        // Act
        val result = appNamesManager.hasCustomNameForPackage("com.test.app")

        // Assert
        assertFalse(result)
    }

    @Test
    fun `removeCustomNameForPackage - when DataStore fails - returns false`() = runTest {
        // Arrange
        whenever(mockDataStore.edit(any())).doAnswer {
            throw IOException("Write error")
        }

        // Act
        val result = appNamesManager.removeCustomNameForPackage("com.test.app")

        // Assert
        assertFalse(result)
    }

    @Test
    fun `removeCustomNameForPackage - when CancellationException - propagates it`() = runTest {
        // Arrange
        whenever(mockDataStore.edit(any())).doAnswer {
            throw CancellationException("Cancelled")
        }

        // Act & Assert
        assertFailsWith<CancellationException> {
            appNamesManager.removeCustomNameForPackage("com.test.app")
        }
    }

    @Test
    fun `hasCustomNameForPackage - when CancellationException - propagates it`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flow {
            throw CancellationException("Flow cancelled")
        })

        // Act & Assert
        assertFailsWith<CancellationException> {
            appNamesManager.hasCustomNameForPackage("com.test.app")
        }
    }

    @Test
    fun `triggerCustomNameUpdate - calls emit on trigger flow`() = runTest {
        // Act
        appNamesManager.triggerCustomNameUpdate()

        // Assert
        verify(mockAppsUpdateTrigger).emit(Unit)
    }
}