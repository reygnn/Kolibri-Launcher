package com.github.reygnn.kolibri_launcher.data

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

class HiddenAppsManagerTest {

    @get:Rule
    val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var mockDataStore: DataStore<Preferences>
    @Mock
    private lateinit var mockContext: Context

    private lateinit var hiddenAppsManager: HiddenAppsManager

    private val hiddenComponentsKey = stringSetPreferencesKey("hidden_components_set")

    @Before
    fun setup() {
        hiddenAppsManager = HiddenAppsManager(mockDataStore, mockContext)
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `isComponentHidden returns true for a hidden component`() = runTest {
        val hiddenComponents = setOf("com.hidden.app/ComponentA")
        val testPreferences = preferencesOf(hiddenComponentsKey to hiddenComponents)
        whenever(mockDataStore.data).thenReturn(flowOf(testPreferences))

        Assert.assertTrue(hiddenAppsManager.isComponentHidden("com.hidden.app/ComponentA"))
    }

    @Test
    fun `isComponentHidden returns false for a visible component`() = runTest {
        val hiddenComponents = setOf("com.another.app/ComponentB")
        val testPreferences = preferencesOf(hiddenComponentsKey to hiddenComponents)
        whenever(mockDataStore.data).thenReturn(flowOf(testPreferences))

        Assert.assertFalse(hiddenAppsManager.isComponentHidden("com.visible.app/ComponentC"))
    }

    @Test
    fun `hideComponent adds the component to the hidden set`() = runTest {
        val initialHiddenComponents = setOf("com.already.hidden/ComponentD")
        val initialPrefs = preferencesOf(hiddenComponentsKey to initialHiddenComponents)
        whenever(mockDataStore.data).thenReturn(flowOf(initialPrefs))
        whenever(mockDataStore.edit(any())).doReturn(initialPrefs)

        val result = hiddenAppsManager.hideComponent("com.new.to.hide/ComponentE")

        Assert.assertTrue(result)
        verify(mockDataStore).edit(any())
    }

    @Test
    fun `showComponent removes the component from the hidden set`() = runTest {
        val initialHiddenComponents = setOf("com.app1/ComponentF", "com.to.show/ComponentG")
        val initialPrefs = preferencesOf(hiddenComponentsKey to initialHiddenComponents)
        whenever(mockDataStore.data).thenReturn(flowOf(initialPrefs))
        whenever(mockDataStore.edit(any())).doReturn(initialPrefs)

        val result = hiddenAppsManager.showComponent("com.to.show/ComponentG")

        Assert.assertTrue(result)
        verify(mockDataStore).edit(any())
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `isComponentHidden - when DataStore fails with IOException - returns false`() = runTest {
        whenever(mockDataStore.data).thenReturn(flow {
            throw IOException("Cannot read data")
        })

        val result = hiddenAppsManager.isComponentHidden("com.test.app/Component")

        Assert.assertFalse(result)
    }

    @Test
    fun `isComponentHidden - when DataStore fails with RuntimeException - returns false`() =
        runTest {
            whenever(mockDataStore.data).thenReturn(flow {
                throw RuntimeException("Corrupted data")
            })

            val result = hiddenAppsManager.isComponentHidden("com.test.app/Component")

            Assert.assertFalse(result)
        }

    @Test
    fun `isComponentHidden - when CancellationException - propagates it`() = runTest {
        whenever(mockDataStore.data).thenReturn(flow {
            throw CancellationException("Flow cancelled")
        })

        assertFailsWith<CancellationException> {
            hiddenAppsManager.isComponentHidden("com.test.app/Component")
        }
    }

    @Test
    fun `hideComponent - when DataStore edit fails with IOException - returns false`() = runTest {
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))
        whenever(mockDataStore.edit(any())).doAnswer {
            throw IOException("Disk full")
        }

        val result = hiddenAppsManager.hideComponent("com.test.app/Component")

        Assert.assertFalse(result)
        verify(mockDataStore).edit(any())
    }

    @Test
    fun `hideComponent - when DataStore edit fails with RuntimeException - returns false`() =
        runTest {
            whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))
            whenever(mockDataStore.edit(any())).doAnswer {
                throw RuntimeException("Unexpected error")
            }

            val result = hiddenAppsManager.hideComponent("com.test.app/Component")

            Assert.assertFalse(result)
        }

    @Test
    fun `hideComponent - when CancellationException - propagates it`() = runTest {
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))
        whenever(mockDataStore.edit(any())).doAnswer {
            throw CancellationException("Cancelled")
        }

        assertFailsWith<CancellationException> {
            hiddenAppsManager.hideComponent("com.test.app/Component")
        }
    }

    @Test
    fun `showComponent - when DataStore edit fails with IOException - returns false`() = runTest {
        val initialHiddenComponents = setOf("com.test.app/Component")
        val initialPrefs = preferencesOf(hiddenComponentsKey to initialHiddenComponents)
        whenever(mockDataStore.data).thenReturn(flowOf(initialPrefs))
        whenever(mockDataStore.edit(any())).doAnswer {
            throw IOException("Write error")
        }

        val result = hiddenAppsManager.showComponent("com.test.app/Component")

        Assert.assertFalse(result)
    }

    @Test
    fun `showComponent - when CancellationException - propagates it`() = runTest {
        val initialHiddenComponents = setOf("com.test.app/Component")
        val initialPrefs = preferencesOf(hiddenComponentsKey to initialHiddenComponents)
        whenever(mockDataStore.data).thenReturn(flowOf(initialPrefs))
        whenever(mockDataStore.edit(any())).doAnswer {
            throw CancellationException("Cancelled")
        }

        assertFailsWith<CancellationException> {
            hiddenAppsManager.showComponent("com.test.app/Component")
        }
    }

    @Test
    fun `hideComponent - with null componentName - returns false`() = runTest {
        val result = hiddenAppsManager.hideComponent(null)
        Assert.assertFalse(result)
    }

    @Test
    fun `hideComponent - with blank componentName - returns false`() = runTest {
        val result = hiddenAppsManager.hideComponent("   ")
        Assert.assertFalse(result)
    }

    @Test
    fun `hideComponent - with malformed componentName - still attempts to hide`() = runTest {
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))
        whenever(mockDataStore.edit(any())).doReturn(preferencesOf())

        val result = hiddenAppsManager.hideComponent("invalid_format_no_slash")

        Assert.assertTrue(result)
    }

    @Test
    fun `showComponent - with null componentName - returns false`() = runTest {
        val result = hiddenAppsManager.showComponent(null)
        Assert.assertFalse(result)
    }

    @Test
    fun `showComponent - with blank componentName - returns false`() = runTest {
        val result = hiddenAppsManager.showComponent("")
        Assert.assertFalse(result)
    }

    @Test
    fun `isComponentHidden - with null componentName - returns false`() = runTest {
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))
        val result = hiddenAppsManager.isComponentHidden(null)
        Assert.assertFalse(result)
    }

    @Test
    fun `isComponentHidden - with blank componentName - returns false`() = runTest {
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))
        val result = hiddenAppsManager.isComponentHidden("  ")
        Assert.assertFalse(result)
    }

    // ========== OPTIMIZATION CHECKS ==========

    @Test
    fun `hideComponent - when component already hidden - returns true and DOES NOT edit DataStore`() =
        runTest {
            // Arrange
            val alreadyHidden = setOf("com.test.app/Component")
            val initialPrefs = preferencesOf(hiddenComponentsKey to alreadyHidden)
            whenever(mockDataStore.data).thenReturn(flowOf(initialPrefs))

            // Act
            val result = hiddenAppsManager.hideComponent("com.test.app/Component")

            // Assert
            Assert.assertTrue(result)
            // WICHTIG: Sicherstellen, dass KEIN Schreibzugriff stattfand (Performance)
            verify(mockDataStore, never()).edit(any())
        }

    @Test
    fun `showComponent - when component not hidden - returns true and DOES NOT edit DataStore`() =
        runTest {
            // Arrange
            whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))

            // Act
            val result = hiddenAppsManager.showComponent("com.test.app/Component")

            // Assert
            Assert.assertTrue(result)
            // WICHTIG: Sicherstellen, dass KEIN Schreibzugriff stattfand (Performance)
            verify(mockDataStore, never()).edit(any())
        }

    // ========== MISSING TESTS (Purge & Batch Update) ==========

    @Test
    fun `updateComponentVisibilities - updates DataStore correctly`() = runTest {
        // Arrange
        val initialHidden = setOf("com.keep.hidden/A", "com.to.show/B")
        val initialPrefs = preferencesOf(hiddenComponentsKey to initialHidden)

        whenever(mockDataStore.data).thenReturn(flowOf(initialPrefs))
        whenever(mockDataStore.edit(any())).doReturn(initialPrefs)

        val toHide = setOf("com.new.hide/C")
        val toShow = setOf("com.to.show/B")

        // Act
        hiddenAppsManager.updateComponentVisibilities(toHide, toShow)

        // Assert
        verify(mockDataStore).edit(any())
        // Hinweis: Da wir `edit` mocken, können wir nicht prüfen, ob das Set *innen* drin stimmt,
        // außer wir nutzen einen FakeDataStore (wie im AppUsageManagerTest).
        // Aber wir verifizieren zumindest, dass der Aufruf stattfindet.
    }

    @Test
    fun `purgeRepository - clears hidden components`() = runTest {
        // Arrange
        whenever(mockDataStore.edit(any())).doReturn(preferencesOf())

        // Act
        hiddenAppsManager.purgeRepository()

        // Assert
        verify(mockDataStore).edit(any())
    }

    @Test
    fun `purgeRepository - handles exceptions gracefully`() = runTest {
        // Arrange
        whenever(mockDataStore.edit(any())).doAnswer { throw RuntimeException("Fail") }

        // Act - should not crash
        hiddenAppsManager.purgeRepository()

        // Assert
        verify(mockDataStore).edit(any())
    }
}