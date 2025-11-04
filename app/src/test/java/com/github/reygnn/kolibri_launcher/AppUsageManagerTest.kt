package com.github.reygnn.kolibri_launcher

import android.content.Context
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.kolibri_launcher.data.AppInfo
import com.github.reygnn.kolibri_launcher.data.AppUsageManager
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

class AppUsageManagerTest {

    @get:Rule
    val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var mockContext: Context

    private lateinit var fakeDataStore: FakeDataStore
    private lateinit var appUsageManager: AppUsageManager

    @Before
    fun setup() {
        fakeDataStore = FakeDataStore()
        appUsageManager = AppUsageManager(fakeDataStore, mockContext)
    }

    // ========== BASIC FUNCTIONALITY TESTS ==========

    @Test
    fun `recordPackageLaunch - successfully records launch`() = runTest {
        // Act
        appUsageManager.recordPackageLaunch("com.test.app")

        // Assert
        assertEquals(1, fakeDataStore.updateDataCallCount)
        assertTrue(appUsageManager.hasUsageDataForPackage("com.test.app"))
    }

    @Test
    fun `recordPackageLaunch - with null packageName - does nothing`() = runTest {
        // Act
        appUsageManager.recordPackageLaunch(null)

        // Assert
        assertEquals(0, fakeDataStore.updateDataCallCount)
    }

    @Test
    fun `recordPackageLaunch - with blank packageName - does nothing`() = runTest {
        // Act
        appUsageManager.recordPackageLaunch("   ")

        // Assert
        assertEquals(0, fakeDataStore.updateDataCallCount)
    }

    // ========== SORTING TESTS ==========

    @Test
    fun `sortAppsByTimeWeightedUsage - correctly sorts by recency`() = runTest {
        // Arrange
        val apps = listOf(
            AppInfo("App C", "App C", "com.c", "c"),
            AppInfo("App B", "App B", "com.b", "b"),
            AppInfo("App A", "App A", "com.a", "a"),
            AppInfo("App D", "App D", "com.d", "d")
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
        fakeDataStore.setInitialData(usagePreferences)

        // Act
        val sortedApps = appUsageManager.sortAppsByTimeWeightedUsage(apps)

        // Assert
        assertEquals("App A", sortedApps[0].displayName) // sehr kürzlich
        assertEquals("App B", sortedApps[1].displayName) // kürzlich
        assertEquals("App C", sortedApps[2].displayName) // alt
        assertEquals("App D", sortedApps[3].displayName) // ungenutzt
    }

    @Test
    fun `sortAppsByTimeWeightedUsage - uses alphabetical sort as tie-breaker`() = runTest {
        // Arrange
        val apps = listOf(
            AppInfo("App Z", "App Z", "com.z", "z"),
            AppInfo("App Used", "App Used", "com.used", "used"),
            AppInfo("App A", "App A", "com.a", "a")
        )

        val currentTime = System.currentTimeMillis()
        val recentTime = (currentTime - TimeUnit.SECONDS.toMillis(10)).toString()

        val usagePreferences = preferencesOf(
            stringSetPreferencesKey("com.used") to setOf(recentTime)
        )
        fakeDataStore.setInitialData(usagePreferences)

        // Act
        val sortedApps = appUsageManager.sortAppsByTimeWeightedUsage(apps)

        // Assert
        assertEquals("App Used", sortedApps[0].displayName) // genutzte App zuerst
        assertEquals("App A", sortedApps[1].displayName)    // dann alphabetisch
        assertEquals("App Z", sortedApps[2].displayName)
    }

    @Test
    fun `sortAppsByTimeWeightedUsage - multiple launches sum up correctly`() = runTest {
        // Arrange
        val currentTime = System.currentTimeMillis()
        val apps = listOf(
            AppInfo("Frequent", "Frequent", "com.frequent", "f"),
            AppInfo("Once", "Once", "com.once", "o")
        )

        // Frequent: 5 Starts in letzter Woche
        val frequentTimestamps = (0..4).map {
            (currentTime - TimeUnit.DAYS.toMillis(it.toLong())).toString()
        }.toSet()

        val usagePreferences = preferencesOf(
            stringSetPreferencesKey("com.frequent") to frequentTimestamps,
            stringSetPreferencesKey("com.once") to setOf(
                (currentTime - TimeUnit.DAYS.toMillis(7)).toString()
            )
        )
        fakeDataStore.setInitialData(usagePreferences)

        // Act
        val result = appUsageManager.sortAppsByTimeWeightedUsage(apps)

        // Assert - häufige Nutzung sollte gewinnen
        assertEquals("Frequent", result[0].displayName)
        assertEquals("Once", result[1].displayName)
    }

    @Test
    fun `sortAppsByTimeWeightedUsage - with empty app list - returns empty list`() = runTest {
        // Act
        val result = appUsageManager.sortAppsByTimeWeightedUsage(emptyList())

        // Assert
        assertTrue(result.isEmpty())
    }

    // ========== EDGE CASES & TIMESTAMP VALIDATION ==========

    @Test
    fun `sortAppsByTimeWeightedUsage - with corrupt timestamp data - still succeeds`() = runTest {
        // Arrange
        val corruptData = preferencesOf(
            stringSetPreferencesKey("com.test") to setOf("invalid", "not_a_number", "abc123")
        )
        fakeDataStore.setInitialData(corruptData)

        val apps = listOf(
            AppInfo("Test", "Test", "com.test", "test"),
            AppInfo("Other", "Other", "com.other", "other")
        )

        // Act
        val result = appUsageManager.sortAppsByTimeWeightedUsage(apps)

        // Assert - alphabetisch sortiert, da Timestamps ungültig
        assertEquals("Other", result[0].displayName)
        assertEquals("Test", result[1].displayName)
    }

    @Test
    fun `sortAppsByTimeWeightedUsage - ignores future timestamps`() = runTest {
        // Arrange
        val currentTime = System.currentTimeMillis()
        val apps = listOf(
            AppInfo("Future", "Future", "com.future", "f"),
            AppInfo("Valid", "Valid", "com.valid", "v")
        )

        val usagePreferences = preferencesOf(
            stringSetPreferencesKey("com.future") to setOf(
                (currentTime + TimeUnit.DAYS.toMillis(1)).toString()
            ),
            stringSetPreferencesKey("com.valid") to setOf(
                (currentTime - TimeUnit.HOURS.toMillis(1)).toString()
            )
        )
        fakeDataStore.setInitialData(usagePreferences)

        // Act
        val result = appUsageManager.sortAppsByTimeWeightedUsage(apps)

        // Assert - Valid sollte vor Future sein (Future wird ignoriert)
        assertEquals("Valid", result[0].displayName)
        assertEquals("Future", result[1].displayName)
    }

    @Test
    fun `sortAppsByTimeWeightedUsage - handles very old timestamps`() = runTest {
        // Arrange
        val currentTime = System.currentTimeMillis()
        val apps = listOf(
            AppInfo("Ancient", "Ancient", "com.ancient", "a"),
            AppInfo("Recent", "Recent", "com.recent", "r")
        )

        val usagePreferences = preferencesOf(
            stringSetPreferencesKey("com.ancient") to setOf(
                (currentTime - TimeUnit.DAYS.toMillis(364)).toString() // Fast 1 Jahr
            ),
            stringSetPreferencesKey("com.recent") to setOf(
                (currentTime - TimeUnit.HOURS.toMillis(1)).toString()
            )
        )
        fakeDataStore.setInitialData(usagePreferences)

        // Act - should not crash due to exp() overflow
        val result = appUsageManager.sortAppsByTimeWeightedUsage(apps)

        // Assert - Recent sollte vor Ancient sein
        assertEquals("Recent", result[0].displayName)
        assertEquals("Ancient", result[1].displayName)
    }

    // ========== ERROR HANDLING TESTS ==========

    @Test
    fun `recordPackageLaunch - when DataStore edit fails with IOException - does not crash`() = runTest {
        // Arrange
        fakeDataStore.makeEditFail()

        // Act - should not crash
        appUsageManager.recordPackageLaunch("com.test.app")

        // Assert - updateData wurde aufgerufen
        assertEquals(1, fakeDataStore.updateDataCallCount)
    }

    @Test
    fun `recordPackageLaunch - when CancellationException - propagates it`() = runTest {
        // Arrange
        fakeDataStore.makeCancellable()

        // Act & Assert
        assertFailsWith<CancellationException> {
            appUsageManager.recordPackageLaunch("com.test.app")
        }
    }

    @Test
    fun `sortAppsByTimeWeightedUsage - when DataStore fails - falls back to alphabetical`() = runTest {
        // Arrange
        fakeDataStore.makeReadFail()

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

    // ========== HAS USAGE DATA TESTS ==========

    @Test
    fun `hasUsageDataForPackage - with valid data - returns true`() = runTest {
        // Arrange
        val usageData = preferencesOf(
            stringSetPreferencesKey("com.test.app") to setOf("123456789")
        )
        fakeDataStore.setInitialData(usageData)

        // Act
        val result = appUsageManager.hasUsageDataForPackage("com.test.app")

        // Assert
        assertTrue(result)
    }

    @Test
    fun `hasUsageDataForPackage - with no data - returns false`() = runTest {
        // Act
        val result = appUsageManager.hasUsageDataForPackage("com.test.app")

        // Assert
        assertFalse(result)
    }

    @Test
    fun `hasUsageDataForPackage - with null packageName - returns false`() = runTest {
        // Act
        val result = appUsageManager.hasUsageDataForPackage(null)

        // Assert
        assertFalse(result)
    }

    @Test
    fun `hasUsageDataForPackage - with blank packageName - returns false`() = runTest {
        // Act
        val result = appUsageManager.hasUsageDataForPackage("   ")

        // Assert
        assertFalse(result)
    }

    @Test
    fun `hasUsageDataForPackage - when DataStore fails - returns false`() = runTest {
        // Arrange
        fakeDataStore.makeReadFail()

        // Act
        val result = appUsageManager.hasUsageDataForPackage("com.test.app")

        // Assert
        assertFalse(result)
    }

    // ========== REMOVE USAGE DATA TESTS ==========

    @Test
    fun `removeUsageDataForPackage - when successful - removes data`() = runTest {
        // Arrange - erst Daten hinzufügen
        appUsageManager.recordPackageLaunch("com.test.app")
        assertTrue(appUsageManager.hasUsageDataForPackage("com.test.app"))

        // Act
        appUsageManager.removeUsageDataForPackage("com.test.app")

        // Assert
        assertFalse(appUsageManager.hasUsageDataForPackage("com.test.app"))
    }

    @Test
    fun `removeUsageDataForPackage - with null packageName - does nothing`() = runTest {
        // Act
        appUsageManager.removeUsageDataForPackage(null)

        // Assert
        assertEquals(0, fakeDataStore.updateDataCallCount)
    }

    @Test
    fun `removeUsageDataForPackage - with blank packageName - does nothing`() = runTest {
        // Act
        appUsageManager.removeUsageDataForPackage("   ")

        // Assert
        assertEquals(0, fakeDataStore.updateDataCallCount)
    }

    @Test
    fun `removeUsageDataForPackage - when DataStore fails - does not crash`() = runTest {
        // Arrange
        fakeDataStore.makeEditFail()

        // Act - should not crash
        appUsageManager.removeUsageDataForPackage("com.test.app")

        // Assert - updateData wurde aufgerufen
        assertEquals(1, fakeDataStore.updateDataCallCount)
    }

    @Test
    fun `removeUsageDataForPackage - when CancellationException - propagates it`() = runTest {
        // Arrange
        fakeDataStore.makeCancellable()

        // Act & Assert
        assertFailsWith<CancellationException> {
            appUsageManager.removeUsageDataForPackage("com.test.app")
        }
    }

    // ========== INTEGRATION-LIKE TESTS ==========

    @Test
    fun `full workflow - record, sort, check, and remove usage data`() = runTest {
        // Arrange
        val apps = listOf(
            AppInfo("App A", "App A", "com.a", "a"),
            AppInfo("App B", "App B", "com.b", "b"),
            AppInfo("App C", "App C", "com.c", "c")
        )

        // Act & Assert - Schritt für Schritt

        // 1. Apps starten
        appUsageManager.recordPackageLaunch("com.b")
        Thread.sleep(100) // Kurze Pause für unterschiedliche Timestamps
        appUsageManager.recordPackageLaunch("com.a")
        Thread.sleep(100)
        appUsageManager.recordPackageLaunch("com.c")

        // 2. Prüfen dass Daten vorhanden sind
        assertTrue(appUsageManager.hasUsageDataForPackage("com.a"))
        assertTrue(appUsageManager.hasUsageDataForPackage("com.b"))
        assertTrue(appUsageManager.hasUsageDataForPackage("com.c"))

        // 3. Sortierung sollte nach Recency sein (C, A, B)
        val sorted = appUsageManager.sortAppsByTimeWeightedUsage(apps)
        assertEquals("App C", sorted[0].displayName) // zuletzt gestartet
        assertEquals("App A", sorted[1].displayName)
        assertEquals("App B", sorted[2].displayName)

        // 4. Eine App entfernen
        appUsageManager.removeUsageDataForPackage("com.a")
        assertFalse(appUsageManager.hasUsageDataForPackage("com.a"))

        // 5. Sortierung sollte sich ändern
        val sortedAfterRemoval = appUsageManager.sortAppsByTimeWeightedUsage(apps)
        assertEquals("App C", sortedAfterRemoval[0].displayName)
        assertEquals("App B", sortedAfterRemoval[1].displayName)
        assertEquals("App A", sortedAfterRemoval[2].displayName) // jetzt ungenutzt, alphabetisch
    }

    @Test
    fun `multiple recordings for same app - all timestamps are kept`() = runTest {
        // Arrange & Act - mehrere Starts aufzeichnen
        repeat(5) {
            appUsageManager.recordPackageLaunch("com.test.app")
            Thread.sleep(50) // Verschiedene Timestamps
        }

        // Assert - App sollte Nutzungsdaten haben
        assertTrue(appUsageManager.hasUsageDataForPackage("com.test.app"))

        // Die genaue Anzahl der Timestamps können wir hier nicht direkt prüfen,
        // aber wir können verifizieren dass die App höher gerankt wird
        val apps = listOf(
            AppInfo("Test", "Test", "com.test.app", "test"),
            AppInfo("Other", "Other", "com.other", "other")
        )

        appUsageManager.recordPackageLaunch("com.other") // nur 1x

        val sorted = appUsageManager.sortAppsByTimeWeightedUsage(apps)
        assertEquals("Test", sorted[0].displayName) // Mehr Starts = höherer Score
    }
}