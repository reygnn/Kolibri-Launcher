package com.github.reygnn.kolibri_launcher.data

import io.mockk.mockk

import android.content.Context
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

class AppUsageRepositoryImplTest {
    @get:Rule
    val timberRule = TimberRule()

    private val context: Context = mockk(relaxed = true)

    private lateinit var fakeDataStore: FakeDataStore
    private lateinit var appUsageManager: AppUsageRepositoryImpl

    @Before
    fun setup() {
        fakeDataStore = FakeDataStore()
        appUsageManager = AppUsageRepositoryImpl(fakeDataStore, context)
    }

    // ========== BASIC FUNCTIONALITY TESTS ==========

    @Test
    fun `recordPackageLaunch - successfully records launch`() = runTest {
        // Act
        appUsageManager.recordPackageLaunch("com.test.app")

        // Assert
        assertThat(fakeDataStore.updateDataCallCount).isEqualTo(1)
        assertThat(appUsageManager.hasUsageDataForPackage("com.test.app")).isTrue()
    }

    @Test
    fun `recordPackageLaunch - with null packageName - does nothing`() = runTest {
        // Act
        appUsageManager.recordPackageLaunch(null)

        // Assert
        assertThat(fakeDataStore.updateDataCallCount).isEqualTo(0)
    }

    @Test
    fun `recordPackageLaunch - with blank packageName - does nothing`() = runTest {
        // Act
        appUsageManager.recordPackageLaunch("   ")

        // Assert
        assertThat(fakeDataStore.updateDataCallCount).isEqualTo(0)
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
        val sortedApps = appUsageManager.sortAppsByTimeWeightedUsage(apps, appUsageManager.usageSnapshotFlow.first())

        // Assert
        assertThat(sortedApps[0].displayName).isEqualTo("App A") // very recent
        assertThat(sortedApps[1].displayName).isEqualTo("App B") // recent
        assertThat(sortedApps[2].displayName).isEqualTo("App C") // old
        assertThat(sortedApps[3].displayName).isEqualTo("App D") // ungenutzt
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
        val sortedApps = appUsageManager.sortAppsByTimeWeightedUsage(apps, appUsageManager.usageSnapshotFlow.first())

        // Assert
        assertThat(sortedApps[0].displayName).isEqualTo("App Used") // genutzte App zuerst
        assertThat(sortedApps[1].displayName).isEqualTo("App A")    // dann alphabetisch
        assertThat(sortedApps[2].displayName).isEqualTo("App Z")
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
        val result = appUsageManager.sortAppsByTimeWeightedUsage(apps, appUsageManager.usageSnapshotFlow.first())

        // Assert - häufige Nutzung sollte gewinnen
        assertThat(result[0].displayName).isEqualTo("Frequent")
        assertThat(result[1].displayName).isEqualTo("Once")
    }

    @Test
    fun `sortAppsByTimeWeightedUsage - with empty app list - returns empty list`() = runTest {
        // Act
        val result = appUsageManager.sortAppsByTimeWeightedUsage(emptyList(), emptyMap())

        // Assert
        assertThat(result.isEmpty()).isTrue()
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
        val result = appUsageManager.sortAppsByTimeWeightedUsage(apps, appUsageManager.usageSnapshotFlow.first())

        // Assert - alphabetisch sortiert, da Timestamps ungültig
        assertThat(result[0].displayName).isEqualTo("Other")
        assertThat(result[1].displayName).isEqualTo("Test")
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
        val result = appUsageManager.sortAppsByTimeWeightedUsage(apps, appUsageManager.usageSnapshotFlow.first())

        // Assert - Valid sollte vor Future sein (Future wird ignoriert)
        assertThat(result[0].displayName).isEqualTo("Valid")
        assertThat(result[1].displayName).isEqualTo("Future")
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
        val result = appUsageManager.sortAppsByTimeWeightedUsage(apps, appUsageManager.usageSnapshotFlow.first())

        // Assert - Recent sollte vor Ancient sein
        assertThat(result[0].displayName).isEqualTo("Recent")
        assertThat(result[1].displayName).isEqualTo("Ancient")
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
            assertThat(fakeDataStore.updateDataCallCount).isEqualTo(1)
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
            val result = appUsageManager.sortAppsByTimeWeightedUsage(apps, appUsageManager.usageSnapshotFlow.first())

            // Assert - alphabetisch sortiert als Fallback
            assertThat(result[0].displayName).isEqualTo("A")
            assertThat(result[1].displayName).isEqualTo("B")
            assertThat(result[2].displayName).isEqualTo("C")
        }

    @Test
    fun `sortAppsByTimeWeightedUsage - scores from the passed snapshot, not the store`() =
        runTest {
            // The store says com.a is recently used; the passed snapshot instead
            // gives com.b the recent usage. The sort must honor the SNAPSHOT — this
            // pins that it no longer re-reads the store (the whole point of the
            // usageSnapshotFlow refactor).
            val now = System.currentTimeMillis()
            fakeDataStore.setInitialData(
                preferencesOf(
                    stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.a") to
                        setOf((now - TimeUnit.SECONDS.toMillis(5)).toString()),
                ),
            )
            val apps = listOf(
                AppInfo("A", "A", "com.a", "a"),
                AppInfo("B", "B", "com.b", "b"),
            )
            val snapshot = mapOf("com.b" to listOf(now - TimeUnit.SECONDS.toMillis(5)))

            val result = appUsageManager.sortAppsByTimeWeightedUsage(apps, snapshot)

            // Snapshot wins: com.b (its only recent entry) ranks first, not com.a.
            assertThat(result[0].displayName).isEqualTo("B")
            assertThat(result[1].displayName).isEqualTo("A")
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
        assertThat(result).isTrue()
    }

    @Test
    fun `hasUsageDataForPackage - with no data - returns false`() = runTest {
        // Act
        val result = appUsageManager.hasUsageDataForPackage("com.test.app")

        // Assert
        assertThat(result).isFalse()
    }

    @Test
    fun `hasUsageDataForPackage - with null packageName - returns false`() = runTest {
        // Act
        val result = appUsageManager.hasUsageDataForPackage(null)

        // Assert
        assertThat(result).isFalse()
    }

    @Test
    fun `hasUsageDataForPackage - with blank packageName - returns false`() = runTest {
        // Act
        val result = appUsageManager.hasUsageDataForPackage("   ")

        // Assert
        assertThat(result).isFalse()
    }

    @Test
    fun `hasUsageDataForPackage - when DataStore fails - returns false`() = runTest {
        // Arrange
        fakeDataStore.makeReadFail()

        // Act
        val result = appUsageManager.hasUsageDataForPackage("com.test.app")

        // Assert
        assertThat(result).isFalse()
    }

    // ========== REMOVE USAGE DATA TESTS ==========

    @Test
    fun `removeUsageDataForPackage - when successful - removes data`() = runTest {
        // Arrange - erst Daten hinzufügen
        appUsageManager.recordPackageLaunch("com.test.app")
        assertThat(appUsageManager.hasUsageDataForPackage("com.test.app")).isTrue()

        // Act
        appUsageManager.removeUsageDataForPackage("com.test.app")

        // Assert
        assertThat(appUsageManager.hasUsageDataForPackage("com.test.app")).isFalse()
    }

    @Test
    fun `removeUsageDataForPackage - with null packageName - does nothing`() = runTest {
        // Act
        appUsageManager.removeUsageDataForPackage(null)

        // Assert
        assertThat(fakeDataStore.updateDataCallCount).isEqualTo(0)
    }

    @Test
    fun `removeUsageDataForPackage - with blank packageName - does nothing`() = runTest {
        // Act
        appUsageManager.removeUsageDataForPackage("   ")

        // Assert
        assertThat(fakeDataStore.updateDataCallCount).isEqualTo(0)
    }

    @Test
    fun `removeUsageDataForPackage - when DataStore fails - does not crash`() = runTest {
        // Arrange
        fakeDataStore.makeEditFail()

        // Act - should not crash
        appUsageManager.removeUsageDataForPackage("com.test.app")

        // Assert - updateData wurde aufgerufen
        assertThat(fakeDataStore.updateDataCallCount).isEqualTo(1)
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
        assertThat(appUsageManager.hasUsageDataForPackage("com.a")).isTrue()
        assertThat(appUsageManager.hasUsageDataForPackage("com.b")).isTrue()
        assertThat(appUsageManager.hasUsageDataForPackage("com.c")).isTrue()

        // 3. Sortierung sollte nach Recency sein (C, A, B)
        val sorted = appUsageManager.sortAppsByTimeWeightedUsage(apps, appUsageManager.usageSnapshotFlow.first())
        assertThat(sorted[0].displayName).isEqualTo("App C") // zuletzt gestartet
        assertThat(sorted[1].displayName).isEqualTo("App A")
        assertThat(sorted[2].displayName).isEqualTo("App B")

        // 4. Eine App entfernen
        appUsageManager.removeUsageDataForPackage("com.a")
        assertThat(appUsageManager.hasUsageDataForPackage("com.a")).isFalse()

        // 5. Sortierung sollte sich ändern
        val sortedAfterRemoval = appUsageManager.sortAppsByTimeWeightedUsage(apps, appUsageManager.usageSnapshotFlow.first())
        assertThat(sortedAfterRemoval[0].displayName).isEqualTo("App C")
        assertThat(sortedAfterRemoval[1].displayName).isEqualTo("App B")
        assertThat(sortedAfterRemoval[2].displayName).isEqualTo("App A") // jetzt ungenutzt, alphabetisch
    }

    @Test
    fun `multiple recordings for same app - all timestamps are kept`() = runTest {
        // Arrange & Act - mehrere Starts aufzeichnen
        repeat(5) {
            appUsageManager.recordPackageLaunch("com.test.app")
            Thread.sleep(50) // Verschiedene Timestamps
        }

        // Assert - App sollte Nutzungsdaten haben
        assertThat(appUsageManager.hasUsageDataForPackage("com.test.app")).isTrue()

        // Die genaue Anzahl der Timestamps können wir hier nicht direkt prüfen,
        // aber wir können verifizieren dass die App höher gerankt wird
        val apps = listOf(
            AppInfo("Test", "Test", "com.test.app", "test"),
            AppInfo("Other", "Other", "com.other", "other")
        )

        appUsageManager.recordPackageLaunch("com.other") // nur 1x

        val sorted = appUsageManager.sortAppsByTimeWeightedUsage(apps, appUsageManager.usageSnapshotFlow.first())
        assertThat(sorted[0].displayName).isEqualTo("Test") // more launches = higher score
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

        assertThat(storedTimestamps.size).isEqualTo(limit)

        // CHECK: Ist der Timestamp von 'jetzt' (bzw. sehr neu) dabei?
        // Da recordPackageLaunch intern System.currentTimeMillis() nutzt,
        // ist der exakte String schwer zu raten, aber wir können prüfen,
        // ob ein Wert existiert, der neuer ist als die alten.
        val newestStored = storedTimestamps.maxOfOrNull { it.toLong() } ?: 0L
        assertWithMessage("Newest timestamp should be kept").that(newestStored > (currentTime - 10000)).isTrue()
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

        assertThat(appUsageManager.hasUsageDataForPackage(app1)).isTrue()
        assertThat(appUsageManager.hasUsageDataForPackage(app2)).isTrue()

        // Act
        appUsageManager.purgeRepository()

        // Assert
        assertThat(appUsageManager.hasUsageDataForPackage(app1)).isFalse()
        assertThat(appUsageManager.hasUsageDataForPackage(app2)).isFalse()

        // Prüfen, ob der andere Key noch da ist (optional, falls AppUsageRepositoryImpl selektiv löscht)
        // Laut deiner Implementierung filtert er nach KEY_USAGE_PREFIX, also sollte "some_other_setting" bleiben.
        val prefs = fakeDataStore.data.first()
        assertThat(prefs.contains(stringSetPreferencesKey("some_other_setting"))).isTrue()
    }

    @Test
    fun `purgeRepository - handles exceptions gracefully`() = runTest {
        // Arrange
        fakeDataStore.makeEditFail()

        // Act - sollte nicht crashen
        appUsageManager.purgeRepository()

        // Assert
        assertThat(fakeDataStore.updateDataCallCount).isEqualTo(1)
    }
}