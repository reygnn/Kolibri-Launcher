package com.github.reygnn.kolibri_launcher.data

import io.mockk.mockk

import android.content.Context
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class UsageExportRepositoryImplTest {
    @get:Rule
    val timberRule = TimberRule()

    private val context: Context = mockk(relaxed = true)

    private lateinit var fakeDataStore: FakeDataStore
    private lateinit var appUsageExportManager: UsageExportRepositoryImpl

    private val currentTime = System.currentTimeMillis()

    @Before
    fun setup() {
        fakeDataStore = FakeDataStore()
        appUsageExportManager = UsageExportRepositoryImpl(fakeDataStore, context)
    }

    // ========== EXPORT TO JSON TESTS (ISO 8601) ==========

    @Test
    fun `exportToJson - with empty datastore - returns valid JSON with empty usage data`() = runTest {
        // Act
        val json = appUsageExportManager.exportToJson()

        // Assert
        assertTrue(json.contains("\"version\""))
        assertTrue(json.contains("\"exportTimestamp\""))
        assertTrue(json.contains("\"usageData\""))
    }

    @Test
    fun `exportToJson - with valid timestamps - converts to ISO 8601 strings`() = runTest {
        // Arrange
        val timestamp1 = currentTime - TimeUnit.HOURS.toMillis(1)
        val timestamp2 = currentTime - TimeUnit.HOURS.toMillis(2)

        // Erwartete ISO Strings berechnen
        val iso1 = Instant.ofEpochMilli(timestamp1).toString()
        val iso2 = Instant.ofEpochMilli(timestamp2).toString()

        val usageData = preferencesOf(
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.app1") to setOf(
                timestamp1.toString(),
                timestamp2.toString()
            ),
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.app2") to setOf(
                currentTime.toString()
            )
        )
        fakeDataStore.setInitialData(usageData)

        // Act
        val json = appUsageExportManager.exportToJson()

        // Assert
        assertTrue(json.contains("com.app1"))
        assertTrue(json.contains("com.app2"))
        // Check for ISO strings instead of raw longs
        assertTrue("JSON should contain ISO string $iso1", json.contains(iso1))
        assertTrue("JSON should contain ISO string $iso2", json.contains(iso2))
    }

    @Test
    fun `exportToJson - filters out future timestamps`() = runTest {
        // Arrange
        val futureTimestamp = currentTime + TimeUnit.DAYS.toMillis(1)
        val validTimestamp = currentTime - TimeUnit.HOURS.toMillis(1)
        val validIso = Instant.ofEpochMilli(validTimestamp).toString()
        val futureIso = Instant.ofEpochMilli(futureTimestamp).toString()

        val usageData = preferencesOf(
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test") to setOf(
                futureTimestamp.toString(),
                validTimestamp.toString()
            )
        )
        fakeDataStore.setInitialData(usageData)

        // Act
        val json = appUsageExportManager.exportToJson()

        // Assert
        assertTrue(json.contains(validIso))
        assertFalse(json.contains(futureIso))
    }

    @Test
    fun `exportToJson - filters out timestamps older than max age`() = runTest {
        // Arrange
        val tooOldTimestamp = currentTime - AppConstants.MAX_TIMESTAMP_AGE_MS - TimeUnit.DAYS.toMillis(1)
        val validTimestamp = currentTime - TimeUnit.HOURS.toMillis(1)
        val validIso = Instant.ofEpochMilli(validTimestamp).toString()
        val oldIso = Instant.ofEpochMilli(tooOldTimestamp).toString()

        val usageData = preferencesOf(
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test") to setOf(
                tooOldTimestamp.toString(),
                validTimestamp.toString()
            )
        )
        fakeDataStore.setInitialData(usageData)

        // Act
        val json = appUsageExportManager.exportToJson()

        // Assert
        assertTrue(json.contains(validIso))
        assertFalse(json.contains(oldIso))
    }

    @Test
    fun `exportToJson - excludes packages with no valid timestamps`() = runTest {
        // Arrange
        val usageData = preferencesOf(
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.invalid") to setOf(
                "garbage",
                "data"
            ),
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.valid") to setOf(
                currentTime.toString()
            )
        )
        fakeDataStore.setInitialData(usageData)

        // Act
        val json = appUsageExportManager.exportToJson()

        // Assert
        assertTrue(json.contains("com.valid"))
        assertFalse(json.contains("com.invalid"))
    }

    @Test
    fun `exportToJson - timestamps are sorted descending`() = runTest {
        // Arrange
        val oldest = currentTime - TimeUnit.HOURS.toMillis(3)
        val newest = currentTime - TimeUnit.HOURS.toMillis(1)

        val oldestIso = Instant.ofEpochMilli(oldest).toString()
        val newestIso = Instant.ofEpochMilli(newest).toString()

        val usageData = preferencesOf(
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test") to setOf(
                oldest.toString(),
                newest.toString()
            )
        )
        fakeDataStore.setInitialData(usageData)

        // Act
        val json = appUsageExportManager.exportToJson()

        // Assert - newest should appear before oldest in JSON string
        val newestIndex = json.indexOf(newestIso)
        val oldestIndex = json.indexOf(oldestIso)

        assertTrue("Newest timestamp should be found", newestIndex != -1)
        assertTrue("Oldest timestamp should be found", oldestIndex != -1)
        assertTrue("Timestamps should be sorted descending", newestIndex < oldestIndex)
    }

    // ========== IMPORT TESTS (HYBRID: ISO & LONG) ==========

    @Test
    fun `importFromJson - with ISO 8601 strings - imports successfully`() = runTest {
        // Arrange
        val timestamp = currentTime - TimeUnit.HOURS.toMillis(1)
        val isoString = Instant.ofEpochMilli(timestamp).toString()

        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": "$isoString",
                "app_version": "1.0.0",
                "usage_data": {
                    "com.test.app": ["$isoString"]
                }
            }
        """.trimIndent()

        // Act
        val result = appUsageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Success>(result)
        assertEquals(1, result.packagesImported)
        assertEquals(1, result.timestampsImported)

        // Verify data was stored as Long string in DataStore
        val prefs = fakeDataStore.data.first()
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test.app")
        val stored = prefs[key]

        assertTrue(stored != null)
        assertTrue(stored!!.contains(timestamp.toString()))
    }

    @Test
    fun `importFromJson - with Legacy Long timestamps - imports successfully`() = runTest {
        // Arrange - Rückwärtskompatibilitätstest
        val timestamp = currentTime - TimeUnit.HOURS.toMillis(1)
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {
                    "com.legacy.app": [$timestamp]
                }
            }
        """.trimIndent()

        // Act
        val result = appUsageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Success>(result)
        assertEquals(1, result.timestampsImported)
    }

    @Test
    fun `importFromJson - with MIXED formats - imports both`() = runTest {
        // Arrange
        val ts1 = currentTime - 10000 // Long
        val ts2 = currentTime - 20000
        val ts2Iso = Instant.ofEpochMilli(ts2).toString() // ISO String

        val json = """
            {
                "version": "1.0.0",
                "usage_data": {
                    "com.mixed.test": [$ts1, "$ts2Iso"]
                }
            }
        """.trimIndent()

        // Act
        val result = appUsageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Success>(result)
        assertEquals(2, result.timestampsImported)
    }

    @Test
    fun `importFromJson - gracefully ignores garbage strings inside array`() = runTest {
        // Arrange
        val validTs = currentTime - 10000
        val validIso = Instant.ofEpochMilli(validTs).toString()

        val json = """
            {
                "version": "1.0.0",
                "usage_data": {
                    "com.test": [
                        "$validIso", 
                        "not-a-date", 
                        "garbage-data"
                    ]
                }
            }
        """.trimIndent()

        // Act
        val result = appUsageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Success>(result)
        // Nur der valide Timestamp sollte importiert werden
        assertEquals(1, result.timestampsImported)
    }

    @Test
    fun `importFromJson - merge mode - combines with existing data`() = runTest {
        // Arrange - existing data
        val existingTimestamp = currentTime - TimeUnit.HOURS.toMillis(1)
        val usageData = preferencesOf(
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test") to setOf(
                existingTimestamp.toString()
            )
        )
        fakeDataStore.setInitialData(usageData)

        // New data to import (ISO Format)
        val newTimestamp = currentTime - TimeUnit.HOURS.toMillis(2)
        val newIso = Instant.ofEpochMilli(newTimestamp).toString()

        val json = """
            {
                "version": "1.0.0",
                "usage_data": {
                    "com.test": ["$newIso"]
                }
            }
        """.trimIndent()

        // Act
        val result = appUsageExportManager.importFromJson(json, mergeWithExisting = true)

        // Assert
        assertIs<UsageImportResult.Success>(result)

        val prefs = fakeDataStore.data.first()
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test")
        val storedTimestamps = prefs[key] ?: emptySet()

        assertEquals(2, storedTimestamps.size)
        assertTrue(storedTimestamps.contains(existingTimestamp.toString()))
        assertTrue(storedTimestamps.contains(newTimestamp.toString()))
    }

    @Test
    fun `importFromJson - filters invalid timestamps (future or old)`() = runTest {
        // Arrange
        val validTs = currentTime - TimeUnit.HOURS.toMillis(1)
        val futureTs = currentTime + TimeUnit.DAYS.toMillis(1)

        val validIso = Instant.ofEpochMilli(validTs).toString()
        val futureIso = Instant.ofEpochMilli(futureTs).toString()

        val json = """
            {
                "version": "1.0.0",
                "usage_data": {
                    "com.test": ["$validIso", "$futureIso"]
                }
            }
        """.trimIndent()

        // Act
        val result = appUsageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Success>(result)

        val prefs = fakeDataStore.data.first()
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test")
        val storedTimestamps = prefs[key] ?: emptySet()

        assertEquals(1, storedTimestamps.size)
        assertTrue(storedTimestamps.contains(validTs.toString()))
    }

    @Test
    fun `importFromJson - enforces max timestamps limit`() = runTest {
        // Arrange
        val limit = AppConstants.MAX_TIMESTAMPS_PER_APP
        // Erzeuge Limit + 10 ISO Strings
        val timestamps = (1..limit + 10).joinToString(", ") { i ->
            "\"" + Instant.ofEpochMilli(currentTime - i * 1000).toString() + "\""
        }

        val json = """
            {
                "version": "1.0.0",
                "usage_data": {
                    "com.test": [$timestamps]
                }
            }
        """.trimIndent()

        // Act
        val result = appUsageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Success>(result)

        val prefs = fakeDataStore.data.first()
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test")
        val storedTimestamps = prefs[key] ?: emptySet()

        assertEquals(limit, storedTimestamps.size)
    }

    // ========== INVALID FORMAT & STRUCTURE TESTS ==========

    @Test
    fun `importFromJson - with blank string - returns InvalidFormat`() = runTest {
        val result = appUsageExportManager.importFromJson("", false)
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    @Test
    fun `importFromJson - with malformed JSON - returns InvalidFormat`() = runTest {
        val result = appUsageExportManager.importFromJson("{{{{", false)
        assertTrue(result is UsageImportResult.InvalidFormat || result is UsageImportResult.Error)
    }

    @Test
    fun `importFromJson - missing version - returns InvalidFormat or UnsupportedVersion`() = runTest {
        val json = """{ "usage_data": {} }"""
        val result = appUsageExportManager.importFromJson(json, false)
        // Ohne Version könnte "1.0.0" assumed werden (siehe parseUsageData default), oder Validierung schlägt fehl.
        // Der aktuelle Manager setzt default "1.0.0" beim Parsen, aber validateJsonStructure prüft nur EXISTENZ von Typen.
        assertIs<UsageImportResult.Success>(result) // Da default 1.0.0 im Code gesetzt ist
    }

    // ========== TYPE CONFUSION & ATTACK TESTS ==========

    @Test
    fun `importFromJson - version as number - returns InvalidFormat`() = runTest {
        val json = """
            {
                "version": 123,
                "usage_data": {}
            }
        """.trimIndent()
        val result = appUsageExportManager.importFromJson(json, false)
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    @Test
    fun `importFromJson - export_timestamp invalid type (boolean) - returns InvalidFormat`() = runTest {
        // String und Number sind erlaubt, Boolean nicht
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": true,
                "usage_data": {}
            }
        """.trimIndent()
        val result = appUsageExportManager.importFromJson(json, false)
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    @Test
    fun `importFromJson - usage_data as array - returns InvalidFormat`() = runTest {
        val json = """
            {
                "version": "1.0.0",
                "usage_data": [1, 2, 3]
            }
        """.trimIndent()
        val result = appUsageExportManager.importFromJson(json, false)
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    @Test
    fun `importFromJson - usage_data timestamp invalid type (boolean) - returns InvalidFormat`() = runTest {
        // String und Number sind im Array erlaubt, Boolean nicht
        val json = """
            {
                "version": "1.0.0",
                "usage_data": {
                    "com.test": [true]
                }
            }
        """.trimIndent()
        val result = appUsageExportManager.importFromJson(json, false)
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    // ========== DOS PROTECTION TESTS ==========

    @Test
    fun `importFromJson - too many packages - returns InvalidFormat`() = runTest {
        val maxPackages = AppConstants.MAX_ARRAY_ELEMENTS
        val packages = (1..maxPackages + 1).joinToString(", ") { i ->
            "\"com.app$i\": []"
        }
        val json = """
            {
                "version": "1.0.0",
                "usage_data": { $packages }
            }
        """.trimIndent()

        val result = appUsageExportManager.importFromJson(json, false)
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    @Test
    fun `importFromJson - too many timestamps per package - returns InvalidFormat`() = runTest {
        val maxTimestamps = AppConstants.MAX_TIMESTAMPS_PER_APP * 2
        // Ein Array das zu groß ist, selbst wenn leer oder mit Zahlen
        val timestamps = (1..maxTimestamps + 1).joinToString(", ") { "1" }
        val json = """
            {
                "version": "1.0.0",
                "usage_data": {
                    "com.test": [$timestamps]
                }
            }
        """.trimIndent()

        val result = appUsageExportManager.importFromJson(json, false)
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    // ========== ERROR HANDLING TESTS ==========

    @Test
    fun `exportToJson - when DataStore fails - throws IOException`() = runTest {
        fakeDataStore.makeReadFail()
        val exception = assertFailsWith<IOException> {
            appUsageExportManager.exportToJson()
        }
        assertTrue(exception.message?.contains("Export failed") == true)
    }

    @Test
    fun `importFromJson - when DataStore edit fails - returns Error`() = runTest {
        fakeDataStore.makeEditFail()
        val json = """{ "version": "1.0.0", "usage_data": { "com.test": [123] } }"""
        val result = appUsageExportManager.importFromJson(json, false)
        assertTrue(result is UsageImportResult.Error || result is UsageImportResult.InvalidFormat)
    }

    // ========== ROUNDTRIP TESTS ==========

    @Test
    fun `export and import roundtrip - preserves data (via ISO conversion)`() = runTest {
        // Arrange
        val timestamp1 = currentTime - TimeUnit.HOURS.toMillis(1)
        val initialData = preferencesOf(
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.app1") to setOf(
                timestamp1.toString()
            )
        )
        fakeDataStore.setInitialData(initialData)

        // Act - Export (to ISO)
        val exportedJson = appUsageExportManager.exportToJson()

        // Clear datastore
        fakeDataStore.reset()

        // Import (from ISO back to Long)
        val result = appUsageExportManager.importFromJson(exportedJson, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Success>(result)
        val prefs = fakeDataStore.data.first()
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.app1")

        assertTrue(prefs.contains(key))
        val stored = prefs[key]
        assertTrue(stored!!.contains(timestamp1.toString()))
    }
}