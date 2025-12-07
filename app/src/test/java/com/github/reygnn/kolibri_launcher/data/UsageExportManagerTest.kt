package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertIs

class UsageExportManagerTest {

    @get:Rule
    val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var mockContext: Context

    private lateinit var fakeDataStore: FakeDataStore
    private lateinit var usageExportManager: UsageExportManager

    private val currentTime = System.currentTimeMillis()

    @Before
    fun setup() {
        fakeDataStore = FakeDataStore()
        usageExportManager = UsageExportManager(fakeDataStore, mockContext)
    }

    // ========== EXPORT TO JSON TESTS ==========

    @Test
    fun `exportToJson - with empty datastore - returns valid JSON with empty usage data`() = runTest {
        // Act
        val json = usageExportManager.exportToJson()

        // Assert
        assertTrue(json.contains("\"version\""))
        assertTrue(json.contains("\"export_timestamp\""))
        assertTrue(json.contains("\"usage_data\""))
    }

    @Test
    fun `exportToJson - with valid timestamps - includes all data`() = runTest {
        // Arrange
        val timestamp1 = currentTime - TimeUnit.HOURS.toMillis(1)
        val timestamp2 = currentTime - TimeUnit.HOURS.toMillis(2)

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
        val json = usageExportManager.exportToJson()

        // Assert
        assertTrue(json.contains("com.app1"))
        assertTrue(json.contains("com.app2"))
        assertTrue(json.contains(timestamp1.toString()))
        assertTrue(json.contains(timestamp2.toString()))
    }

    @Test
    fun `exportToJson - filters out future timestamps`() = runTest {
        // Arrange
        val futureTimestamp = currentTime + TimeUnit.DAYS.toMillis(1)
        val validTimestamp = currentTime - TimeUnit.HOURS.toMillis(1)

        val usageData = preferencesOf(
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test") to setOf(
                futureTimestamp.toString(),
                validTimestamp.toString()
            )
        )
        fakeDataStore.setInitialData(usageData)

        // Act
        val json = usageExportManager.exportToJson()

        // Assert
        assertTrue(json.contains(validTimestamp.toString()))
        assertFalse(json.contains(futureTimestamp.toString()))
    }

    @Test
    fun `exportToJson - filters out timestamps older than max age`() = runTest {
        // Arrange
        val tooOldTimestamp = currentTime - AppConstants.MAX_TIMESTAMP_AGE_MS - TimeUnit.DAYS.toMillis(1)
        val validTimestamp = currentTime - TimeUnit.HOURS.toMillis(1)

        val usageData = preferencesOf(
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test") to setOf(
                tooOldTimestamp.toString(),
                validTimestamp.toString()
            )
        )
        fakeDataStore.setInitialData(usageData)

        // Act
        val json = usageExportManager.exportToJson()

        // Assert
        assertTrue(json.contains(validTimestamp.toString()))
        assertFalse(json.contains(tooOldTimestamp.toString()))
    }

    @Test
    fun `exportToJson - filters out corrupt timestamps`() = runTest {
        // Arrange
        val validTimestamp = currentTime - TimeUnit.HOURS.toMillis(1)

        val usageData = preferencesOf(
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test") to setOf(
                "invalid",
                "not_a_number",
                validTimestamp.toString()
            )
        )
        fakeDataStore.setInitialData(usageData)

        // Act
        val json = usageExportManager.exportToJson()

        // Assert
        assertTrue(json.contains(validTimestamp.toString()))
        assertFalse(json.contains("invalid"))
        assertFalse(json.contains("not_a_number"))
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
        val json = usageExportManager.exportToJson()

        // Assert
        assertTrue(json.contains("com.valid"))
        assertFalse(json.contains("com.invalid"))
    }

    @Test
    fun `exportToJson - ignores non-usage keys`() = runTest {
        // Arrange
        val usageData = preferencesOf(
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test") to setOf(
                currentTime.toString()
            ),
            stringSetPreferencesKey("some_other_setting") to setOf("value")
        )
        fakeDataStore.setInitialData(usageData)

        // Act
        val json = usageExportManager.exportToJson()

        // Assert
        assertTrue(json.contains("com.test"))
        assertFalse(json.contains("some_other_setting"))
    }

    @Test
    fun `exportToJson - timestamps are sorted descending`() = runTest {
        // Arrange
        val oldest = currentTime - TimeUnit.HOURS.toMillis(3)
        val middle = currentTime - TimeUnit.HOURS.toMillis(2)
        val newest = currentTime - TimeUnit.HOURS.toMillis(1)

        val usageData = preferencesOf(
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test") to setOf(
                oldest.toString(),
                newest.toString(),
                middle.toString()
            )
        )
        fakeDataStore.setInitialData(usageData)

        // Act
        val json = usageExportManager.exportToJson()

        // Assert - newest should appear before oldest in JSON
        val newestIndex = json.indexOf(newest.toString())
        val oldestIndex = json.indexOf(oldest.toString())
        assertTrue(newestIndex < oldestIndex)
    }

    // ========== IMPORT FROM JSON TESTS ==========

    @Test
    fun `importFromJson - with valid JSON - imports successfully`() = runTest {
        // Arrange
        val timestamp = currentTime - TimeUnit.HOURS.toMillis(1)
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {
                    "com.test.app": [$timestamp]
                }
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Success>(result)
        assertEquals(1, result.packagesImported)
        assertEquals(1, result.timestampsImported)

        // Verify data was stored
        val prefs = fakeDataStore.data.first()
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test.app")
        assertTrue(prefs.contains(key))
    }

    @Test
    fun `importFromJson - with multiple packages - imports all`() = runTest {
        // Arrange
        val timestamp1 = currentTime - TimeUnit.HOURS.toMillis(1)
        val timestamp2 = currentTime - TimeUnit.HOURS.toMillis(2)
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {
                    "com.app1": [$timestamp1, $timestamp2],
                    "com.app2": [$timestamp1]
                }
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Success>(result)
        assertEquals(2, result.packagesImported)
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

        // New data to import
        val newTimestamp = currentTime - TimeUnit.HOURS.toMillis(2)
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {
                    "com.test": [$newTimestamp]
                }
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = true)

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
    fun `importFromJson - replace mode - overwrites existing data`() = runTest {
        // Arrange - existing data
        val existingTimestamp = currentTime - TimeUnit.HOURS.toMillis(1)
        val usageData = preferencesOf(
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test") to setOf(
                existingTimestamp.toString()
            )
        )
        fakeDataStore.setInitialData(usageData)

        // New data to import
        val newTimestamp = currentTime - TimeUnit.HOURS.toMillis(2)
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {
                    "com.test": [$newTimestamp]
                }
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Success>(result)

        val prefs = fakeDataStore.data.first()
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test")
        val storedTimestamps = prefs[key] ?: emptySet()

        assertEquals(1, storedTimestamps.size)
        assertFalse(storedTimestamps.contains(existingTimestamp.toString()))
        assertTrue(storedTimestamps.contains(newTimestamp.toString()))
    }

    @Test
    fun `importFromJson - merge mode - removes duplicates`() = runTest {
        // Arrange - existing data with same timestamp
        val sharedTimestamp = currentTime - TimeUnit.HOURS.toMillis(1)
        val usageData = preferencesOf(
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test") to setOf(
                sharedTimestamp.toString()
            )
        )
        fakeDataStore.setInitialData(usageData)

        // Import same timestamp
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {
                    "com.test": [$sharedTimestamp]
                }
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = true)

        // Assert
        assertIs<UsageImportResult.Success>(result)

        val prefs = fakeDataStore.data.first()
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test")
        val storedTimestamps = prefs[key] ?: emptySet()

        assertEquals(1, storedTimestamps.size) // No duplicates
    }

    @Test
    fun `importFromJson - filters invalid timestamps during import`() = runTest {
        // Arrange
        val validTimestamp = currentTime - TimeUnit.HOURS.toMillis(1)
        val futureTimestamp = currentTime + TimeUnit.DAYS.toMillis(1)
        val tooOldTimestamp = currentTime - AppConstants.MAX_TIMESTAMP_AGE_MS - TimeUnit.DAYS.toMillis(1)

        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {
                    "com.test": [$validTimestamp, $futureTimestamp, $tooOldTimestamp]
                }
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Success>(result)

        val prefs = fakeDataStore.data.first()
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test")
        val storedTimestamps = prefs[key] ?: emptySet()

        assertEquals(1, storedTimestamps.size)
        assertTrue(storedTimestamps.contains(validTimestamp.toString()))
    }

    @Test
    fun `importFromJson - skips packages with blank names`() = runTest {
        // Arrange
        val timestamp = currentTime - TimeUnit.HOURS.toMillis(1)
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {
                    "": [$timestamp],
                    "com.valid": [$timestamp]
                }
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Success>(result)
        assertEquals(1, result.packagesImported)
        assertEquals(1, result.packagesSkipped)
    }

    @Test
    fun `importFromJson - skips packages with no valid timestamps`() = runTest {
        // Arrange
        val futureTimestamp = currentTime + TimeUnit.DAYS.toMillis(1)
        val validTimestamp = currentTime - TimeUnit.HOURS.toMillis(1)

        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {
                    "com.invalid": [$futureTimestamp],
                    "com.valid": [$validTimestamp]
                }
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Success>(result)
        assertEquals(1, result.packagesImported)
        assertEquals(1, result.packagesSkipped)
    }

    @Test
    fun `importFromJson - enforces max timestamps limit`() = runTest {
        // Arrange
        val limit = AppConstants.MAX_TIMESTAMPS_PER_APP
        val timestamps = (1..limit + 10).map { currentTime - TimeUnit.HOURS.toMillis(it.toLong()) }
        val timestampArray = timestamps.joinToString(", ")

        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {
                    "com.test": [$timestampArray]
                }
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Success>(result)

        val prefs = fakeDataStore.data.first()
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test")
        val storedTimestamps = prefs[key] ?: emptySet()

        assertEquals(limit, storedTimestamps.size)
    }

    // ========== INVALID FORMAT TESTS ==========

    @Test
    fun `importFromJson - with blank string - returns InvalidFormat`() = runTest {
        // Act
        val result = usageExportManager.importFromJson("", mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    @Test
    fun `importFromJson - with whitespace only - returns InvalidFormat`() = runTest {
        // Act
        val result = usageExportManager.importFromJson("   ", mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    @Test
    fun `importFromJson - with non-JSON string - returns InvalidFormat`() = runTest {
        // Act
        val result = usageExportManager.importFromJson("not valid json", mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    @Test
    fun `importFromJson - with malformed JSON - returns InvalidFormat`() = runTest {
        // Act
        val result = usageExportManager.importFromJson("{ invalid: json }", mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    @Test
    fun `importFromJson - with array instead of object - returns InvalidFormat`() = runTest {
        // Act
        val result = usageExportManager.importFromJson("[1, 2, 3]", mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    // ========== TYPE CONFUSION ATTACK TESTS ==========

    @Test
    fun `importFromJson - version as number - returns InvalidFormat`() = runTest {
        // Arrange
        val json = """
            {
                "version": 123,
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {}
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    @Test
    fun `importFromJson - export_timestamp as string - returns InvalidFormat`() = runTest {
        // Arrange
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": "not_a_number",
                "app_version": "1.0.0",
                "usage_data": {}
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    @Test
    fun `importFromJson - usage_data as array - returns InvalidFormat`() = runTest {
        // Arrange
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": [1, 2, 3]
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    @Test
    fun `importFromJson - timestamps as strings - returns InvalidFormat`() = runTest {
        // Arrange
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {
                    "com.test": ["not", "numbers"]
                }
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    @Test
    fun `importFromJson - package timestamps as object - returns InvalidFormat`() = runTest {
        // Arrange
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {
                    "com.test": {"key": "value"}
                }
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    // ========== DOS PROTECTION TESTS ==========

    @Test
    fun `importFromJson - too many packages - returns InvalidFormat`() = runTest {
        // Arrange
        val maxPackages = AppConstants.MAX_ARRAY_ELEMENTS
        val packages = (1..maxPackages + 1).joinToString(", ") { i ->
            "\"com.app$i\": [$currentTime]"
        }
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": { $packages }
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    @Test
    fun `importFromJson - too many timestamps per package - returns InvalidFormat`() = runTest {
        // Arrange
        val maxTimestamps = AppConstants.MAX_TIMESTAMPS_PER_APP * 2
        val timestamps = (1..maxTimestamps + 1).joinToString(", ") { i ->
            (currentTime - i * 1000).toString()
        }
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {
                    "com.test": [$timestamps]
                }
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    // ========== VERSION TESTS ==========

    @Test
    fun `importFromJson - unsupported version - returns UnsupportedVersion`() = runTest {
        // Arrange
        val json = """
            {
                "version": "99.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {}
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.UnsupportedVersion>(result)
        assertEquals("99.0.0", result.version)
    }

    @Test
    fun `importFromJson - missing version - returns InvalidFormat`() = runTest {
        // Arrange
        val json = """
            {
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {}
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert - kotlinx.serialization sollte hier fehlschlagen
        assertTrue(result is UsageImportResult.InvalidFormat || result is UsageImportResult.UnsupportedVersion)
    }

    // ========== ERROR HANDLING TESTS ==========

    @Test
    fun `exportToJson - when DataStore fails - throws IOException`() = runTest {
        // Arrange
        fakeDataStore.makeReadFail()

        // Act & Assert
        try {
            usageExportManager.exportToJson()
            assertTrue("Expected IOException to be thrown", false)
        } catch (e: Exception) {
            assertTrue(e is java.io.IOException)
        }
    }

    @Test
    fun `importFromJson - when DataStore edit fails - returns Error`() = runTest {
        // Arrange
        fakeDataStore.makeEditFail()

        val timestamp = currentTime - TimeUnit.HOURS.toMillis(1)
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {
                    "com.test": [$timestamp]
                }
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Error>(result)
    }

    @Test
    fun `importFromJson - when CancellationException - propagates it`() = runTest {
        // Arrange
        fakeDataStore.makeCancellable()

        val timestamp = currentTime - TimeUnit.HOURS.toMillis(1)
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {
                    "com.test": [$timestamp]
                }
            }
        """.trimIndent()

        // Act & Assert
        assertFailsWith<CancellationException> {
            usageExportManager.importFromJson(json, mergeWithExisting = false)
        }
    }

    // ========== ROUNDTRIP TESTS ==========

    @Test
    fun `export and import roundtrip - preserves data`() = runTest {
        // Arrange - seed initial data
        val timestamp1 = currentTime - TimeUnit.HOURS.toMillis(1)
        val timestamp2 = currentTime - TimeUnit.HOURS.toMillis(2)

        val initialData = preferencesOf(
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.app1") to setOf(
                timestamp1.toString(),
                timestamp2.toString()
            ),
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.app2") to setOf(
                timestamp1.toString()
            )
        )
        fakeDataStore.setInitialData(initialData)

        // Act - export
        val exportedJson = usageExportManager.exportToJson()

        // Clear datastore
        fakeDataStore.reset()

        // Import
        val result = usageExportManager.importFromJson(exportedJson, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Success>(result)
        assertEquals(2, result.packagesImported)

        val prefs = fakeDataStore.data.first()

        val key1 = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.app1")
        val key2 = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.app2")

        assertTrue(prefs.contains(key1))
        assertTrue(prefs.contains(key2))

        val timestamps1 = prefs[key1] ?: emptySet()
        assertEquals(2, timestamps1.size)
        assertTrue(timestamps1.contains(timestamp1.toString()))
        assertTrue(timestamps1.contains(timestamp2.toString()))
    }

    @Test
    fun `export from one manager and import to another - data is identical`() = runTest {
        // Arrange - first manager with data
        val timestamp = currentTime - TimeUnit.HOURS.toMillis(1)
        val initialData = preferencesOf(
            stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test") to setOf(
                timestamp.toString()
            )
        )
        fakeDataStore.setInitialData(initialData)

        // Export
        val exportedJson = usageExportManager.exportToJson()

        // Create second manager with fresh datastore
        val secondDataStore = FakeDataStore()
        val secondManager = UsageExportManager(secondDataStore, mockContext)

        // Act - import into second manager
        val result = secondManager.importFromJson(exportedJson, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Success>(result)

        val prefs = secondDataStore.data.first()
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test")
        val storedTimestamps = prefs[key] ?: emptySet()

        assertEquals(1, storedTimestamps.size)
        assertTrue(storedTimestamps.contains(timestamp.toString()))
    }

    // ========== EDGE CASES ==========

    @Test
    fun `importFromJson - with empty usage_data - succeeds with zero imports`() = runTest {
        // Arrange
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {}
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Success>(result)
        assertEquals(0, result.packagesImported)
        assertEquals(0, result.timestampsImported)
    }

    @Test
    fun `importFromJson - with null values in JSON - handles gracefully`() = runTest {
        // Arrange
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": null,
                "usage_data": {}
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert - should either succeed or return InvalidFormat, but not crash
        assertTrue(result is UsageImportResult.Success || result is UsageImportResult.InvalidFormat)
    }

    @Test
    fun `importFromJson - negative timestamp - gets filtered`() = runTest {
        // Arrange
        val validTimestamp = currentTime - TimeUnit.HOURS.toMillis(1)
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {
                    "com.test": [-1, -999, $validTimestamp]
                }
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Success>(result)

        val prefs = fakeDataStore.data.first()
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test")
        val storedTimestamps = prefs[key] ?: emptySet()

        assertEquals(1, storedTimestamps.size)
        assertTrue(storedTimestamps.contains(validTimestamp.toString()))
    }

    @Test
    fun `importFromJson - zero timestamp - gets filtered`() = runTest {
        // Arrange
        val validTimestamp = currentTime - TimeUnit.HOURS.toMillis(1)
        val json = """
            {
                "version": "1.0.0",
                "export_timestamp": $currentTime,
                "app_version": "1.0.0",
                "usage_data": {
                    "com.test": [0, $validTimestamp]
                }
            }
        """.trimIndent()

        // Act
        val result = usageExportManager.importFromJson(json, mergeWithExisting = false)

        // Assert
        assertIs<UsageImportResult.Success>(result)

        val prefs = fakeDataStore.data.first()
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test")
        val storedTimestamps = prefs[key] ?: emptySet()

        assertEquals(1, storedTimestamps.size)
    }
}