package com.github.reygnn.kolibri_launcher.data

import io.mockk.mockk

import android.content.Context
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

class AppUsageManagerTest {
    @get:Rule
    val timberRule = TimberRule()

    private val mockContext: Context = mockk(relaxed = true)

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
        Assert.assertEquals(1, fakeDataStore.updateDataCallCount)
        Assert.assertTrue(appUsageManager.hasUsageDataForPackage("com.test.app"))
    }

    @Test
    fun `recordPackageLaunch - with null packageName - does nothing`() = runTest {
        // Act
        appUsageManager.recordPackageLaunch(null)

        // Assert
        Assert.assertEquals(0, fakeDataStore.updateDataCallCount)
    }

    @Test
    fun `recordPackageLaunch - with blank packageName - does nothing`() = runTest {
        // Act
        appUsageManager.recordPackageLaunch("   ")

        // Assert
        Assert.assertEquals(0, fakeDataStore.updateDataCallCount)
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
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.a") to setOf(veryRecentTime),
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.b") to setOf(recentTime),
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.c") to setOf(oldTime)
        )
        fakeDataStore.setInitialData(usagePreferences)

        // Act
        val sortedApps = appUsageManager.sortAppsByTimeWeightedUsage(apps)

        // Assert
        Assert.assertEquals("App A", sortedApps[0].displayName) // sehr kürzlich
        Assert.assertEquals("App B", sortedApps[1].displayName) // kürzlich
        Assert.assertEquals("App C", sortedApps[2].displayName) // alt
        Assert.assertEquals("App D", sortedApps[3].displayName) // ungenutzt
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
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.used") to setOf(recentTime)
        )
        fakeDataStore.setInitialData(usagePreferences)

        // Act
        val sortedApps = appUsageManager.sortAppsByTimeWeightedUsage(apps)

        // Assert
        Assert.assertEquals("App Used", sortedApps[0].displayName) // genutzte App zuerst
        Assert.assertEquals("App A", sortedApps[1].displayName)    // dann alphabetisch
        Assert.assertEquals("App Z", sortedApps[2].displayName)
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
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.frequent") to frequentTimestamps,
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.once") to setOf(
                (currentTime - TimeUnit.DAYS.toMillis(7)).toString()
            )
        )
        fakeDataStore.setInitialData(usagePreferences)

        // Act
        val result = appUsageManager.sortAppsByTimeWeightedUsage(apps)

        // Assert - häufige Nutzung sollte gewinnen
        Assert.assertEquals("Frequent", result[0].displayName)
        Assert.assertEquals("Once", result[1].displayName)
    }

    @Test
    fun `sortAppsByTimeWeightedUsage - with empty app list - returns empty list`() = runTest {
        // Act
        val result = appUsageManager.sortAppsByTimeWeightedUsage(emptyList())

        // Assert
        Assert.assertTrue(result.isEmpty())
    }

    // ========== EDGE CASES & TIMESTAMP VALIDATION ==========

    @Test
    fun `sortAppsByTimeWeightedUsage - with corrupt timestamp data - still succeeds`() = runTest {
        // Arrange
        val corruptData = preferencesOf(
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test") to setOf(
                "invalid",
                "not_a_number",
                "abc123"
            )
        )
        fakeDataStore.setInitialData(corruptData)

        val apps = listOf(
            AppInfo("Test", "Test", "com.test", "test"),
            AppInfo("Other", "Other", "com.other", "other")
        )

        // Act
        val result = appUsageManager.sortAppsByTimeWeightedUsage(apps)

        // Assert - alphabetisch sortiert, da Timestamps ungültig
        Assert.assertEquals("Other", result[0].displayName)
        Assert.assertEquals("Test", result[1].displayName)
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
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.future") to setOf(
                (currentTime + TimeUnit.DAYS.toMillis(1)).toString()
            ),
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.valid") to setOf(
                (currentTime - TimeUnit.HOURS.toMillis(1)).toString()
            )
        )
        fakeDataStore.setInitialData(usagePreferences)

        // Act
        val result = appUsageManager.sortAppsByTimeWeightedUsage(apps)

        // Assert - Valid sollte vor Future sein (Future wird ignoriert)
        Assert.assertEquals("Valid", result[0].displayName)
        Assert.assertEquals("Future", result[1].displayName)
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
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.ancient") to setOf(
                (currentTime - TimeUnit.DAYS.toMillis(364)).toString() // Fast 1 Jahr
            ),
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.recent") to setOf(
                (currentTime - TimeUnit.HOURS.toMillis(1)).toString()
            )
        )
        fakeDataStore.setInitialData(usagePreferences)

        // Act - should not crash due to exp() overflow
        val result = appUsageManager.sortAppsByTimeWeightedUsage(apps)

        // Assert - Recent sollte vor Ancient sein
        Assert.assertEquals("Recent", result[0].displayName)
        Assert.assertEquals("Ancient", result[1].displayName)
    }

    // ========== ERROR HANDLING TESTS ==========

    @Test
    fun `recordPackageLaunch - when DataStore edit fails with IOException - does not crash`() =
        runTest {
            // Arrange
            fakeDataStore.makeEditFail()

            // Act - should not crash
            appUsageManager.recordPackageLaunch("com.test.app")

            // Assert - updateData wurde aufgerufen
            Assert.assertEquals(1, fakeDataStore.updateDataCallCount)
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
    fun `sortAppsByTimeWeightedUsage - when DataStore fails - falls back to alphabetical`() =
        runTest {
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
            Assert.assertEquals("A", result[0].displayName)
            Assert.assertEquals("B", result[1].displayName)
            Assert.assertEquals("C", result[2].displayName)
        }

    // ========== HAS USAGE DATA TESTS ==========

    @Test
    fun `hasUsageDataForPackage - with valid data - returns true`() = runTest {
        // Arrange
        val usageData = preferencesOf(
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test.app") to setOf("123456789")
        )
        fakeDataStore.setInitialData(usageData)

        // Act
        val result = appUsageManager.hasUsageDataForPackage("com.test.app")

        // Assert
        Assert.assertTrue(result)
    }

    @Test
    fun `hasUsageDataForPackage - with no data - returns false`() = runTest {
        // Act
        val result = appUsageManager.hasUsageDataForPackage("com.test.app")

        // Assert
        Assert.assertFalse(result)
    }

    @Test
    fun `hasUsageDataForPackage - with null packageName - returns false`() = runTest {
        // Act
        val result = appUsageManager.hasUsageDataForPackage(null)

        // Assert
        Assert.assertFalse(result)
    }

    @Test
    fun `hasUsageDataForPackage - with blank packageName - returns false`() = runTest {
        // Act
        val result = appUsageManager.hasUsageDataForPackage("   ")

        // Assert
        Assert.assertFalse(result)
    }

    @Test
    fun `hasUsageDataForPackage - when DataStore fails - returns false`() = runTest {
        // Arrange
        fakeDataStore.makeReadFail()

        // Act
        val result = appUsageManager.hasUsageDataForPackage("com.test.app")

        // Assert
        Assert.assertFalse(result)
    }

    // ========== REMOVE USAGE DATA TESTS ==========

    @Test
    fun `removeUsageDataForPackage - when successful - removes data`() = runTest {
        // Arrange - erst Daten hinzufügen
        appUsageManager.recordPackageLaunch("com.test.app")
        Assert.assertTrue(appUsageManager.hasUsageDataForPackage("com.test.app"))

        // Act
        appUsageManager.removeUsageDataForPackage("com.test.app")

        // Assert
        Assert.assertFalse(appUsageManager.hasUsageDataForPackage("com.test.app"))
    }

    @Test
    fun `removeUsageDataForPackage - with null packageName - does nothing`() = runTest {
        // Act
        appUsageManager.removeUsageDataForPackage(null)

        // Assert
        Assert.assertEquals(0, fakeDataStore.updateDataCallCount)
    }

    @Test
    fun `removeUsageDataForPackage - with blank packageName - does nothing`() = runTest {
        // Act
        appUsageManager.removeUsageDataForPackage("   ")

        // Assert
        Assert.assertEquals(0, fakeDataStore.updateDataCallCount)
    }

    @Test
    fun `removeUsageDataForPackage - when DataStore fails - does not crash`() = runTest {
        // Arrange
        fakeDataStore.makeEditFail()

        // Act - should not crash
        appUsageManager.removeUsageDataForPackage("com.test.app")

        // Assert - updateData wurde aufgerufen
        Assert.assertEquals(1, fakeDataStore.updateDataCallCount)
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
        Assert.assertTrue(appUsageManager.hasUsageDataForPackage("com.a"))
        Assert.assertTrue(appUsageManager.hasUsageDataForPackage("com.b"))
        Assert.assertTrue(appUsageManager.hasUsageDataForPackage("com.c"))

        // 3. Sortierung sollte nach Recency sein (C, A, B)
        val sorted = appUsageManager.sortAppsByTimeWeightedUsage(apps)
        Assert.assertEquals("App C", sorted[0].displayName) // zuletzt gestartet
        Assert.assertEquals("App A", sorted[1].displayName)
        Assert.assertEquals("App B", sorted[2].displayName)

        // 4. Eine App entfernen
        appUsageManager.removeUsageDataForPackage("com.a")
        Assert.assertFalse(appUsageManager.hasUsageDataForPackage("com.a"))

        // 5. Sortierung sollte sich ändern
        val sortedAfterRemoval = appUsageManager.sortAppsByTimeWeightedUsage(apps)
        Assert.assertEquals("App C", sortedAfterRemoval[0].displayName)
        Assert.assertEquals("App B", sortedAfterRemoval[1].displayName)
        Assert.assertEquals(
            "App A",
            sortedAfterRemoval[2].displayName
        ) // jetzt ungenutzt, alphabetisch
    }

    @Test
    fun `multiple recordings for same app - all timestamps are kept`() = runTest {
        // Arrange & Act - mehrere Starts aufzeichnen
        repeat(5) {
            appUsageManager.recordPackageLaunch("com.test.app")
            Thread.sleep(50) // Verschiedene Timestamps
        }

        // Assert - App sollte Nutzungsdaten haben
        Assert.assertTrue(appUsageManager.hasUsageDataForPackage("com.test.app"))

        // Die genaue Anzahl der Timestamps können wir hier nicht direkt prüfen,
        // aber wir können verifizieren dass die App höher gerankt wird
        val apps = listOf(
            AppInfo("Test", "Test", "com.test.app", "test"),
            AppInfo("Other", "Other", "com.other", "other")
        )

        appUsageManager.recordPackageLaunch("com.other") // nur 1x

        val sorted = appUsageManager.sortAppsByTimeWeightedUsage(apps)
        Assert.assertEquals("Test", sorted[0].displayName) // Mehr Starts = höherer Score
    }

    // ========== NEW TESTS: LIMITS & CLEANUP ==========

    @Test
    fun `recordPackageLaunch - enforces max timestamps limit and keeps NEWEST`() = runTest {
        // Arrange
        val packageName = "com.limit.test"
        val limit = AppConstants.MAX_TIMESTAMPS_PER_APP
        val currentTime = System.currentTimeMillis()

        // Wir füllen den Speicher mit alten Werten
        val oldTimestamps = (1..limit).map {
            (currentTime - TimeUnit.DAYS.toMillis(it.toLong())).toString()
        }.toSet()

        val usageKey = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + packageName)
        fakeDataStore.setInitialData(preferencesOf(usageKey to oldTimestamps))

        // Act - Neuer Launch JETZT
        appUsageManager.recordPackageLaunch(packageName)

        // Assert
        val prefs = fakeDataStore.data.first()
        val storedTimestamps = prefs[usageKey] ?: emptySet()

        Assert.assertEquals(limit, storedTimestamps.size)

        // CHECK: Ist der Timestamp von 'jetzt' (bzw. sehr neu) dabei?
        // Da recordPackageLaunch intern System.currentTimeMillis() nutzt,
        // ist der exakte String schwer zu raten, aber wir können prüfen,
        // ob ein Wert existiert, der neuer ist als die alten.
        val newestStored = storedTimestamps.maxOfOrNull { it.toLong() } ?: 0L
        Assert.assertTrue("Newest timestamp should be kept", newestStored > (currentTime - 10000))
    }

    @Test
    fun `purgeRepository - removes all usage data`() = runTest {
        // Arrange
        val app1 = "com.app1"
        val app2 = "com.app2"

        // Daten für zwei Apps anlegen
        val initialData = preferencesOf(
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + app1) to setOf("1000"),
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + app2) to setOf("2000"),
            // Ein anderer Key, der NICHT gelöscht werden soll (Simulation)
            stringSetPreferencesKey("some_other_setting") to setOf("value")
        )
        fakeDataStore.setInitialData(initialData)

        Assert.assertTrue(appUsageManager.hasUsageDataForPackage(app1))
        Assert.assertTrue(appUsageManager.hasUsageDataForPackage(app2))

        // Act
        appUsageManager.purgeRepository()

        // Assert
        Assert.assertFalse(appUsageManager.hasUsageDataForPackage(app1))
        Assert.assertFalse(appUsageManager.hasUsageDataForPackage(app2))

        // Prüfen, ob der andere Key noch da ist (optional, falls AppUsageManager selektiv löscht)
        // Laut deiner Implementierung filtert er nach KEY_USAGE_PREFIX, also sollte "some_other_setting" bleiben.
        val prefs = fakeDataStore.data.first()
        Assert.assertTrue(prefs.contains(stringSetPreferencesKey("some_other_setting")))
    }

    @Test
    fun `purgeRepository - handles exceptions gracefully`() = runTest {
        // Arrange
        fakeDataStore.makeEditFail()

        // Act - sollte nicht crashen
        appUsageManager.purgeRepository()

        // Assert
        Assert.assertEquals(1, fakeDataStore.updateDataCallCount)
    }
}