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
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

class AppUsageManagerTest {

    @get:Rule
    val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var mockDataStore: DataStore<Preferences>
    @Mock
    private lateinit var mockContext: Context

    private lateinit var appUsageManager: AppUsageManager

    @Before
    fun setup() {
        appUsageManager = AppUsageManager(mockDataStore, mockContext)
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `recordPackageLaunch correctly calls edit on DataStore`() = runTest {
        val packageName = "com.test.app"
        whenever(mockDataStore.edit(any())).doReturn(preferencesOf())
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))

        appUsageManager.recordPackageLaunch(packageName)

        verify(mockDataStore).edit(any())
    }

    @Test
    fun `sortAppsByTimeWeightedUsage correctly sorts by recency`() = runTest {
        val apps = listOf(
            AppInfo(originalName = "App C", displayName = "App C", packageName = "com.c", className = "c"),
            AppInfo(originalName = "App B", displayName = "App B", packageName = "com.b", className = "b"),
            AppInfo(originalName = "App A", displayName = "App A", packageName = "com.a", className = "a"),
            AppInfo(originalName = "App D", displayName = "App D", packageName = "com.d", className = "d")
        )
        val expectedOrder = listOf(
            apps[2], // App A (sehr kürzlich)
            apps[1], // App B (kürzlich)
            apps[0], // App C (alt)
            apps[3]  // App D (ungenutzt)
        )

        val currentTime = System.currentTimeMillis()
        val veryRecentTime = (currentTime - TimeUnit.SECONDS.toMillis(10)).toString()
        val recentTime = (currentTime - TimeUnit.MINUTES.toMillis(5)).toString()
        val oldTime = (currentTime - TimeUnit.DAYS.toMillis(1)).toString()

        val usagePreferences = preferencesOf(
            stringSetPreferencesKey("com.a") to setOf(veryRecentTime),
            stringSetPreferencesKey("com.b") to setOf(recentTime),
            stringSetPreferencesKey("com.c") to setOf(oldTime)
        )
        whenever(mockDataStore.data).thenReturn(flowOf(usagePreferences))

        val sortedApps = appUsageManager.sortAppsByTimeWeightedUsage(apps)

        assertEquals(expectedOrder, sortedApps)
    }

    @Test
    fun `sortAppsByTimeWeightedUsage uses alphabetical sort as tie-breaker`() = runTest {
        val apps = listOf(
            AppInfo(originalName = "App Z", displayName = "App Z", packageName = "com.z", className = "z"),
            AppInfo(originalName = "App Used", displayName = "App Used", packageName = "com.used", className = "used"),
            AppInfo(originalName = "App A", displayName = "App A", packageName = "com.a", className = "a")
        )
        val expectedOrder = listOf(
            apps[1], // Die genutzte App muss immer zuerst kommen.
            apps[2], // App A kommt vor App Z in der alphabetischen Sortierung.
            apps[0]
        )

        val currentTime = System.currentTimeMillis()
        val recentTime = (currentTime - TimeUnit.SECONDS.toMillis(10)).toString()

        val usagePreferences = preferencesOf(
            stringSetPreferencesKey("com.used") to setOf(recentTime)
        )
        whenever(mockDataStore.data).thenReturn(flowOf(usagePreferences))

        val sortedApps = appUsageManager.sortAppsByTimeWeightedUsage(apps)

        assertEquals(expectedOrder, sortedApps)
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `recordPackageLaunch - when DataStore edit fails with IOException - does not crash`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))
        whenever(mockDataStore.edit(any())).doAnswer {
            throw IOException("Disk full")
        }

        // Act - should not crash
        appUsageManager.recordPackageLaunch("com.test.app")

        // Assert - verify edit was attempted
        verify(mockDataStore).edit(any())
    }

    @Test
    fun `recordPackageLaunch - when DataStore edit fails with RuntimeException - does not crash`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))
        whenever(mockDataStore.edit(any())).doAnswer {
            throw RuntimeException("Unexpected error")
        }

        // Act - should not crash
        appUsageManager.recordPackageLaunch("com.test.app")

        // Assert
        verify(mockDataStore).edit(any())
    }

    @Test
    fun `recordPackageLaunch - when CancellationException - propagates it`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))
        whenever(mockDataStore.edit(any())).doAnswer {
            throw CancellationException("Cancelled")
        }

        // Act & Assert
        assertFailsWith<CancellationException> {
            appUsageManager.recordPackageLaunch("com.test.app")
        }
    }

    @Test
    fun `sortAppsByTimeWeightedUsage - when DataStore fails - falls back to alphabetical`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flow {
            throw IOException("Read error")
        })

        val apps = listOf(
            AppInfo("C", "C", "com.c", "c"),
            AppInfo("A", "A", "com.a", "a"),
            AppInfo("B", "B", "com.b", "b")
        )

        // Act
        val result = appUsageManager.sortAppsByTimeWeightedUsage(apps)

        // Assert - alphabetisch sortiert als Fallback
        assertEquals("A", result[0].displayName)
        assertEquals("B", result[1].displayName)
        assertEquals("C", result[2].displayName)
    }

    @Test
    fun `sortAppsByTimeWeightedUsage - when DataStore throws RuntimeException - falls back to alphabetical`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flow {
            throw RuntimeException("Corrupted data")
        })

        val apps = listOf(
            AppInfo("Z", "Z", "com.z", "z"),
            AppInfo("M", "M", "com.m", "m"),
            AppInfo("A", "A", "com.a", "a")
        )

        // Act
        val result = appUsageManager.sortAppsByTimeWeightedUsage(apps)

        // Assert
        assertEquals("A", result[0].displayName)
        assertEquals("M", result[1].displayName)
        assertEquals("Z", result[2].displayName)
    }

    @Test
    fun `sortAppsByTimeWeightedUsage - with corrupt timestamp data - still succeeds`() = runTest {
        // Arrange - DataStore mit ungültigen Daten
        val corruptData = preferencesOf(
            stringSetPreferencesKey("com.test") to setOf("invalid_timestamp", "not_a_number", "abc123")
        )
        whenever(mockDataStore.data).thenReturn(flowOf(corruptData))

        val apps = listOf(
            AppInfo("Test", "Test", "com.test", "test"),
            AppInfo("Other", "Other", "com.other", "other")
        )

        // Act - should not crash
        val result = appUsageManager.sortAppsByTimeWeightedUsage(apps)

        // Assert - sollte alphabetisch sortiert sein, da Timestamps ungültig
        assertEquals(2, result.size)
        assertEquals("Other", result[0].displayName)
        assertEquals("Test", result[1].displayName)
    }

    @Test
    fun `sortAppsByTimeWeightedUsage - with empty app list - returns empty list`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))

        // Act
        val result = appUsageManager.sortAppsByTimeWeightedUsage(emptyList())

        // Assert
        assertTrue(result.isEmpty())
    }

    @Test
    fun `sortAppsByTimeWeightedUsage - with CancellationException - propagates it`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flow {
            throw CancellationException("Flow cancelled")
        })

        val apps = listOf(AppInfo("A", "A", "com.a", "a"))

        // Act & Assert
        assertFailsWith<CancellationException> {
            appUsageManager.sortAppsByTimeWeightedUsage(apps)
        }
    }

    @Test
    fun `hasUsageDataForPackage - when DataStore fails - returns false`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flow {
            throw IOException("Cannot read")
        })

        // Act
        val result = appUsageManager.hasUsageDataForPackage("com.test.app")

        // Assert
        assertFalse(result)
    }

    @Test
    fun `hasUsageDataForPackage - with valid data - returns true`() = runTest {
        // Arrange
        val usageData = preferencesOf(
            stringSetPreferencesKey("com.test.app") to setOf("123456789")
        )
        whenever(mockDataStore.data).thenReturn(flowOf(usageData))

        // Act
        val result = appUsageManager.hasUsageDataForPackage("com.test.app")

        // Assert
        assertTrue(result)
    }

    @Test
    fun `hasUsageDataForPackage - with no data - returns false`() = runTest {
        // Arrange
        whenever(mockDataStore.data).thenReturn(flowOf(preferencesOf()))

        // Act
        val result = appUsageManager.hasUsageDataForPackage("com.test.app")

        // Assert
        assertFalse(result)
    }

    @Test
    fun `removeUsageDataForPackage - when successful - completes without error`() = runTest {
        appUsageManager.removeUsageDataForPackage("com.test.app")
        verify(mockDataStore).edit(any())
    }

    @Test
    fun `removeUsageDataForPackage - when DataStore fails - does not crash`() = runTest {
        val fakeDataStore = FakeDataStore()
        fakeDataStore.makeEditFail()
        appUsageManager.removeUsageDataForPackage("com.test.app")
        // Kein crash = success
    }
}