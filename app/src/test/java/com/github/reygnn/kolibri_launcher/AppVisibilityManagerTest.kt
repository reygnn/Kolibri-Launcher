package com.github.reygnn.kolibri_launcher

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
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

class AppVisibilityManagerTest {

    @get:Rule
    val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var mockDataStore: DataStore<Preferences>
    @Mock
    private lateinit var mockContext: Context

    private lateinit var appVisibilityManager: AppVisibilityManager

    private val hiddenComponentsKey = stringSetPreferencesKey("hidden_components_set")

    @Before
    fun setup() {
        appVisibilityManager = AppVisibilityManager(mockDataStore, mockContext)
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `isComponentHidden returns true for a hidden component`() = runTest {
        val hiddenComponents = setOf("com.hidden.app/ComponentA")
        val testPreferences = preferencesOf(hiddenComponentsKey to hiddenComponents)
        whenever(mockDataStore.data).thenReturn(flowOf(testPreferences))

        assertTrue(appVisibilityManager.isComponentHidden("com.hidden.app/ComponentA"))
    }

    @Test
    fun `isComponentHidden returns false for a visible component`() = runTest {
        val hiddenComponents = setOf("com.another.app/ComponentB")
        val testPreferences = preferencesOf(hiddenComponentsKey to hiddenComponents)
        whenever(mockDataStore.data).thenReturn(flowOf(testPreferences))

        assertFalse(appVisibilityManager.isComponentHidden("com.visible.app/ComponentC"))
    }

    @Test
    fun `hideComponent adds the component to the hidden set`() = runTest {
        val initialHiddenComponents = setOf("com.already.hidden/ComponentD")
        val initialPrefs = preferencesOf(hiddenComponentsKey to initialHiddenComponents)
        whenever(mockDataStore.data).thenReturn(flowOf(initialPrefs))
        whenever(mockDataStore.edit(any())).doReturn(initialPrefs)

        val result = appVisibilityManager.hideComponent("com.new.to.hide/ComponentE")

        assertTrue(result)
        verify(mockDataStore).edit(any())
    }

    @Test
    fun `showComponent removes the component from the hidden set`() = runTest {
        val initialHiddenComponents = setOf("com.app1/ComponentF", "com.to.show/ComponentG")
        val initialPrefs = preferencesOf(hiddenComponentsKey to initialHiddenComponents)
        whenever(mockDataStore.data).thenReturn(flowOf(initialPrefs))
        whenever(mockDataStore.edit(any())).doReturn(initialPrefs)

        val result = appVisibilityManager.showComponent("com.to.show/ComponentG")

        assertTrue(result)
        verify(mockDataStore).edit(any())
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `isComponentHidden - when DataStore fails with IOException - returns false`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flow {
            throw IOException("Cannot read data")
        })

        // Act
        val result = appVisibilityManager.isComponentHidden("com.test.app/Component")

        // Assert - Fallback: assume visible
        assertFalse(result)
    }

    @Test
    fun `isComponentHidden - when DataStore fails with RuntimeException - returns false`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flow {
            throw RuntimeException("Corrupted data")
        })

        // Act
        val result = appVisibilityManager.isComponentHidden("com.test.app/Component")

        // Assert
        assertFalse(result)
    }

    @Test
    fun `isComponentHidden - when CancellationException - propagates it`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flow {
            throw CancellationException("Flow cancelled")
        })

        // Act & Assert
        assertFailsWith<CancellationException> {
            appVisibilityManager.isComponentHidden("com.test.app/Component")
        }
    }

    @Test
    fun `hideComponent - when DataStore edit fails with IOException - returns false`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))
        whenever(mockDataStore.edit(any())).doAnswer {
            throw IOException("Disk full")
        }

        // Act
        val result = appVisibilityManager.hideComponent("com.test.app/Component")

        // Assert
        assertFalse(result)
        verify(mockDataStore).edit(any())
    }

    @Test
    fun `hideComponent - when DataStore edit fails with RuntimeException - returns false`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))
        whenever(mockDataStore.edit(any())).doAnswer {
            throw RuntimeException("Unexpected error")
        }

        // Act
        val result = appVisibilityManager.hideComponent("com.test.app/Component")

        // Assert
        assertFalse(result)
    }

    @Test
    fun `hideComponent - when CancellationException - propagates it`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))
        whenever(mockDataStore.edit(any())).doAnswer {
            throw CancellationException("Cancelled")
        }

        // Act & Assert
        assertFailsWith<CancellationException> {
            appVisibilityManager.hideComponent("com.test.app/Component")
        }
    }

    @Test
    fun `showComponent - when DataStore edit fails with IOException - returns false`() = runTest {
        // Arrange
        val initialHiddenComponents = setOf("com.test.app/Component")
        val initialPrefs = preferencesOf(hiddenComponentsKey to initialHiddenComponents)
        whenever(mockDataStore.data).thenReturn(flowOf(initialPrefs))
        whenever(mockDataStore.edit(any())).doAnswer {
            throw IOException("Write error")
        }

        // Act
        val result = appVisibilityManager.showComponent("com.test.app/Component")

        // Assert
        assertFalse(result)
    }

    @Test
    fun `showComponent - when CancellationException - propagates it`() = runTest {
        // Arrange - ✅ FIXED: Component muss im Hidden-Set sein!
        val initialHiddenComponents = setOf("com.test.app/Component")
        val initialPrefs = preferencesOf(hiddenComponentsKey to initialHiddenComponents)
        whenever(mockDataStore.data).thenReturn(flowOf(initialPrefs))
        whenever(mockDataStore.edit(any())).doAnswer {
            throw CancellationException("Cancelled")
        }

        // Act & Assert
        assertFailsWith<CancellationException> {
            appVisibilityManager.showComponent("com.test.app/Component")
        }
    }

    @Test
    fun `hideComponent - with null componentName - returns false`() = runTest {
        // Act
        val result = appVisibilityManager.hideComponent(null)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `hideComponent - with blank componentName - returns false`() = runTest {
        // Act
        val result = appVisibilityManager.hideComponent("   ")

        // Assert
        assertFalse(result)
    }

    @Test
    fun `hideComponent - with malformed componentName - still attempts to hide`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))
        whenever(mockDataStore.edit(any())).doReturn(preferencesOf())

        // Act - manager might accept any string, validation is caller's responsibility
        val result = appVisibilityManager.hideComponent("invalid_format_no_slash")

        // Assert - should succeed (even if malformed)
        assertTrue(result)
    }

    @Test
    fun `showComponent - with null componentName - returns false`() = runTest {
        // Act
        val result = appVisibilityManager.showComponent(null)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `showComponent - with blank componentName - returns false`() = runTest {
        // Act
        val result = appVisibilityManager.showComponent("")

        // Assert
        assertFalse(result)
    }

    @Test
    fun `isComponentHidden - with null componentName - returns false`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))

        // Act
        val result = appVisibilityManager.isComponentHidden(null)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `isComponentHidden - with blank componentName - returns false`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))

        // Act
        val result = appVisibilityManager.isComponentHidden("  ")

        // Assert
        assertFalse(result)
    }

    @Test
    fun `hideComponent - when component already hidden - returns true and no duplicate added`() = runTest {
        // Arrange
        val alreadyHidden = setOf("com.test.app/Component")
        val initialPrefs = preferencesOf(hiddenComponentsKey to alreadyHidden)
        whenever(mockDataStore.data).thenReturn(flowOf(initialPrefs))
        whenever(mockDataStore.edit(any())).doReturn(initialPrefs)

        // Act
        val result = appVisibilityManager.hideComponent("com.test.app/Component")

        // Assert
        assertTrue(result)
    }

    @Test
    fun `showComponent - when component not hidden - returns true`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))
        whenever(mockDataStore.edit(any())).doReturn(preferencesOf())

        // Act
        val result = appVisibilityManager.showComponent("com.test.app/Component")

        // Assert
        assertTrue(result)
    }
}