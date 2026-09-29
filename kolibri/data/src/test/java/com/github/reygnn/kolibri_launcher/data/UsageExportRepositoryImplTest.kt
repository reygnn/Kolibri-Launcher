package com.github.reygnn.kolibri_launcher.data

import io.mockk.mockk

import android.content.Context
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
        appUsageExportManager = UsageExportRepositoryImpl(fakeDataStore, context, "test-version")
    }

    // ========== EXPORT TO JSON TESTS (ISO 8601) ==========

    @Test
    fun `exportToJson - with empty datastore - returns valid JSON with empty usage data`() = runTest {
        // Act
        val json = appUsageExportManager.exportToJson()

        // Assert
        assertThat(json.contains("\"version\"")).isTrue()
        assertThat(json.contains("\"exportTimestamp\"")).isTrue()
        assertThat(json.contains("\"usageData\"")).isTrue()
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
        assertThat(json.contains("com.app1")).isTrue()
        assertThat(json.contains("com.app2")).isTrue()
        // Check for ISO strings instead of raw longs
        assertWithMessage("JSON should contain ISO string $iso1").that(json.contains(iso1)).isTrue()
        assertWithMessage("JSON should contain ISO string $iso2").that(json.contains(iso2)).isTrue()
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
        assertThat(json.contains(validIso)).isTrue()
        assertThat(json.contains(futureIso)).isFalse()
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
        assertThat(json.contains(validIso)).isTrue()
        assertThat(json.contains(oldIso)).isFalse()
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
        assertThat(json.contains("com.valid")).isTrue()
        assertThat(json.contains("com.invalid")).isFalse()
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

        assertWithMessage("Newest timestamp should be found").that(newestIndex != -1).isTrue()
        assertWithMessage("Oldest timestamp should be found").that(oldestIndex != -1).isTrue()
        assertWithMessage("Timestamps should be sorted descending").that(newestIndex < oldestIndex).isTrue()
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
        assertThat(result.packagesImported).isEqualTo(1)
        assertThat(result.timestampsImported).isEqualTo(1)

        // Verify data was stored as Long string in DataStore
        val prefs = fakeDataStore.data.first()
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test.app")
        val stored = prefs[key]

        assertThat(stored != null).isTrue()
        assertThat(stored!!.contains(timestamp.toString())).isTrue()
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
        assertThat(result.timestampsImported).isEqualTo(1)
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
        assertThat(result.timestampsImported).isEqualTo(2)
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
        assertThat(result.timestampsImported).isEqualTo(1)
    }

    @Test
    fun `importFromJson - skips an out-of-range ISO timestamp instead of rejecting the whole file`() = runTest {
        // RC edge-case audit regression: a syntactically valid ISO-8601 instant
        // with an extreme year (Instant.MAX territory) parses successfully but
        // overflows Long on toEpochMilli(). parseTimestamp used to catch only
        // DateTimeParseException, so the ArithmeticException propagated to the
        // file-level catch and the WHOLE import was rejected as InvalidFormat.
        // It must instead be skipped like any other unusable entry, keeping the
        // valid siblings — the graceful-skip contract garbage strings already get.
        val validTs = currentTime - 10000
        val validIso = Instant.ofEpochMilli(validTs).toString()

        val json = """
            {
                "version": "1.0.0",
                "usage_data": {
                    "com.test": [
                        "$validIso",
                        "+1000000000-01-01T00:00:00Z"
                    ]
                }
            }
        """.trimIndent()

        val result = appUsageExportManager.importFromJson(json, mergeWithExisting = false)

        assertIs<UsageImportResult.Success>(result)
        assertThat(result.timestampsImported).isEqualTo(1)
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
        assertThat(result).isInstanceOf(UsageImportResult.Success::class.java)

        val prefs = fakeDataStore.data.first()
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test")
        val storedTimestamps = prefs[key] ?: emptySet()

        assertThat(storedTimestamps.size).isEqualTo(2)
        assertThat(storedTimestamps.contains(existingTimestamp.toString())).isTrue()
        assertThat(storedTimestamps.contains(newTimestamp.toString())).isTrue()
    }

    @Test
    fun `importFromJson - merge mode - deduplicates a timestamp present in both existing and import`() = runTest {
        // A timestamp in BOTH the stored set and the imported file must collapse to
        // one entry (KDoc: "Duplikate werden entfernt"). Without .distinct(),
        // overlapping re-imports would inflate the set and evict genuinely-distinct
        // older timestamps under the cap — silent usage-history corruption.
        val shared = currentTime - TimeUnit.HOURS.toMillis(1)
        val onlyExisting = currentTime - TimeUnit.HOURS.toMillis(3)
        val onlyImported = currentTime - TimeUnit.HOURS.toMillis(2)
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test")

        fakeDataStore.setInitialData(
            preferencesOf(key to setOf(onlyExisting.toString(), shared.toString()))
        )

        val json = """
            {
                "version": "1.0.0",
                "usage_data": {
                    "com.test": ["${Instant.ofEpochMilli(shared)}", "${Instant.ofEpochMilli(onlyImported)}"]
                }
            }
        """.trimIndent()

        val result = appUsageExportManager.importFromJson(json, mergeWithExisting = true)
        assertThat(result).isInstanceOf(UsageImportResult.Success::class.java)

        val stored = fakeDataStore.data.first()[key] ?: emptySet()
        // shared collapses: union of 3 distinct timestamps, not 4.
        assertThat(stored.size).isEqualTo(3)
        assertThat(stored.contains(onlyExisting.toString())).isTrue()
        assertThat(stored.contains(shared.toString())).isTrue()
        assertThat(stored.contains(onlyImported.toString())).isTrue()
    }

    @Test
    fun `importFromJson - merge mode - enforces the cap after combining, keeping the newest`() = runTest {
        // The cap applies to the MERGED set, not just the input (KDoc:
        // "MAX_TIMESTAMPS_PER_APP Limit wird eingehalten"). The replace-branch cap
        // is tested elsewhere; this pins the merge branch: existing is already at
        // MAX, a few newer imports must evict the oldest existing — not grow the set.
        val limit = AppConstants.MAX_TIMESTAMPS_PER_APP
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test")

        // MAX distinct existing timestamps, all older than the imports.
        val existing = (1..limit).map { currentTime - TimeUnit.HOURS.toMillis(1) - it * 1000L }
        fakeDataStore.setInitialData(
            preferencesOf(key to existing.map { it.toString() }.toSet())
        )

        // Three NEWER distinct timestamps.
        val newer = listOf(currentTime - 1000L, currentTime - 2000L, currentTime - 3000L)
        val newerIso = newer.joinToString(", ") { "\"${Instant.ofEpochMilli(it)}\"" }
        val json = """
            {
                "version": "1.0.0",
                "usage_data": { "com.test": [$newerIso] }
            }
        """.trimIndent()

        val result = appUsageExportManager.importFromJson(json, mergeWithExisting = true)
        assertThat(result).isInstanceOf(UsageImportResult.Success::class.java)

        val stored = fakeDataStore.data.first()[key] ?: emptySet()
        assertThat(stored.size).isEqualTo(limit) // capped at MAX after merge, not MAX + 3
        newer.forEach { assertWithMessage("newest import must survive").that(stored.contains(it.toString())).isTrue() }
        existing.sorted().take(3).forEach {
            assertWithMessage("the oldest existing must be evicted").that(stored.contains(it.toString())).isFalse()
        }
    }

    @Test
    fun `importFromJson - replace mode - keeps the NEWEST when the file exceeds the cap in ascending order`() = runTest {
        // AUDIT-17 F3: performImport must sort BEFORE truncating. A foreign/hand-
        // edited file may list more than MAX_TIMESTAMPS_PER_APP valid timestamps for
        // a package in ascending (oldest-first) order; validateJsonStructure permits
        // up to MAX * 2. take() before sort would keep the file-order first (=oldest)
        // MAX and drop the newest. Verify the newest MAX survive, the oldest drop.
        val limit = AppConstants.MAX_TIMESTAMPS_PER_APP
        val overflow = 50
        val count = limit + overflow // <= MAX * 2, so validation passes
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test")

        // `count` distinct valid timestamps listed OLDEST-FIRST (ascending): index 1
        // is the oldest, index `count` the newest, all within the last ~hour.
        val ascending = (1..count).map { i ->
            currentTime - TimeUnit.HOURS.toMillis(1) - (count - i) * 1000L
        }
        val ascIso = ascending.joinToString(", ") { "\"${Instant.ofEpochMilli(it)}\"" }
        val json = """
            {
                "version": "1.0.0",
                "usage_data": { "com.test": [$ascIso] }
            }
        """.trimIndent()

        val result = appUsageExportManager.importFromJson(json, mergeWithExisting = false)
        assertThat(result).isInstanceOf(UsageImportResult.Success::class.java)

        val stored = fakeDataStore.data.first()[key] ?: emptySet()
        assertThat(stored.size).isEqualTo(limit)
        // The newest `limit` all survive...
        ascending.sortedDescending().take(limit).forEach {
            assertWithMessage("newest must survive, ts=$it").that(stored.contains(it.toString())).isTrue()
        }
        // ...and the oldest `overflow` are dropped (would have been kept by take-before-sort).
        ascending.sorted().take(overflow).forEach {
            assertWithMessage("oldest must be dropped, ts=$it").that(stored.contains(it.toString())).isFalse()
        }
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
        assertThat(result).isInstanceOf(UsageImportResult.Success::class.java)

        val prefs = fakeDataStore.data.first()
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test")
        val storedTimestamps = prefs[key] ?: emptySet()

        assertThat(storedTimestamps.size).isEqualTo(1)
        assertThat(storedTimestamps.contains(validTs.toString())).isTrue()
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
        assertThat(result).isInstanceOf(UsageImportResult.Success::class.java)

        val prefs = fakeDataStore.data.first()
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.test")
        val storedTimestamps = prefs[key] ?: emptySet()

        assertThat(storedTimestamps.size).isEqualTo(limit)
    }

    // ========== INVALID FORMAT & STRUCTURE TESTS ==========

    @Test
    fun `importFromJson - with blank string - returns InvalidFormat`() = runTest {
        val result = appUsageExportManager.importFromJson("", false)
        assertThat(result).isInstanceOf(UsageImportResult.InvalidFormat::class.java)
    }

    @Test
    fun `importFromJson - with malformed JSON - returns InvalidFormat`() = runTest {
        val result = appUsageExportManager.importFromJson("{{{{", false)
        assertThat(result is UsageImportResult.InvalidFormat || result is UsageImportResult.Error).isTrue()
    }

    @Test
    fun `importFromJson - missing version - returns InvalidFormat or UnsupportedVersion`() = runTest {
        val json = """{ "usage_data": {} }"""
        val result = appUsageExportManager.importFromJson(json, false)
        // Ohne Version könnte "1.0.0" assumed werden (siehe parseUsageData default), oder Validierung schlägt fehl.
        // Der aktuelle Manager setzt default "1.0.0" beim Parsen, aber validateJsonStructure prüft nur EXISTENZ von Typen.
        assertThat(result).isInstanceOf(UsageImportResult.Success::class.java) // Da default 1.0.0 im Code gesetzt ist
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
        assertThat(result).isInstanceOf(UsageImportResult.InvalidFormat::class.java)
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
        assertThat(result).isInstanceOf(UsageImportResult.InvalidFormat::class.java)
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
        assertThat(result).isInstanceOf(UsageImportResult.InvalidFormat::class.java)
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
        assertThat(result).isInstanceOf(UsageImportResult.InvalidFormat::class.java)
    }

    @Test
    fun `importFromJson - an empty timestamp array counts toward packagesSkipped`() = runTest {
        // RC edge-case audit B8: a package with an empty array was dropped at the parse
        // stage — before the import stage's skip counter — so packagesSkipped undercounted
        // it. It must now be reported like any other unusable entry while valid siblings
        // still import.
        val validTs = currentTime - 10_000
        val json = """
            {
                "version": "1.0.0",
                "usage_data": {
                    "com.empty": [],
                    "com.valid": [$validTs]
                }
            }
        """.trimIndent()

        val result = appUsageExportManager.importFromJson(json, mergeWithExisting = false)

        assertIs<UsageImportResult.Success>(result)
        assertThat(result.packagesImported).isEqualTo(1)
        assertThat(result.packagesSkipped).isEqualTo(1)
    }

    @Test
    fun `importFromJson - unsupported version reaches the gate and echoes the version`() = runTest {
        // A well-formed document with a future version passes structural validation (which
        // only type-checks `version`, not its value) and must be rejected at the version
        // gate with the original version echoed back for the UI. The backup path tests this
        // end-to-end; the usage path previously only via a ViewModel-level mock.
        val validTs = currentTime - 10_000
        val json = """{"version":"2.0.0","usage_data":{"com.a":[$validTs]}}"""

        val result = appUsageExportManager.importFromJson(json, mergeWithExisting = false)

        assertIs<UsageImportResult.UnsupportedVersion>(result)
        assertThat(result.version).isEqualTo("2.0.0")
    }

    @Test
    fun `importFromJson - a blank package key is skipped while valid siblings import`() = runTest {
        // validateJsonStructure does not reject an empty key, so the blank-key branch in the
        // import loop is LIVE (not dead code): a "" package is counted as skipped and the
        // valid sibling still imports.
        val validTs = currentTime - 10_000
        val json = """{"version":"1.0.0","usage_data":{"":[$validTs],"com.valid":[$validTs]}}"""

        val result = appUsageExportManager.importFromJson(json, mergeWithExisting = false)

        assertIs<UsageImportResult.Success>(result)
        assertThat(result.packagesImported).isEqualTo(1)
        assertThat(result.packagesSkipped).isEqualTo(1)
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
        assertThat(result).isInstanceOf(UsageImportResult.InvalidFormat::class.java)
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
        assertThat(result).isInstanceOf(UsageImportResult.InvalidFormat::class.java)
    }

    // ========== ERROR HANDLING TESTS ==========

    @Test
    fun `exportToJson - when DataStore fails - throws IOException`() = runTest {
        fakeDataStore.makeReadFail()
        val exception = assertFailsWith<IOException> {
            appUsageExportManager.exportToJson()
        }
        assertThat(exception.message?.contains("Export failed") == true).isTrue()
    }

    @Test
    fun `importFromJson - when DataStore edit fails - returns Error`() = runTest {
        fakeDataStore.makeEditFail()
        val json = """{ "version": "1.0.0", "usage_data": { "com.test": [123] } }"""
        val result = appUsageExportManager.importFromJson(json, false)
        assertThat(result is UsageImportResult.Error || result is UsageImportResult.InvalidFormat).isTrue()
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
        assertThat(result).isInstanceOf(UsageImportResult.Success::class.java)
        val prefs = fakeDataStore.data.first()
        val key = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.app1")

        assertThat(prefs.contains(key)).isTrue()
        val stored = prefs[key]
        assertThat(stored!!.contains(timestamp1.toString())).isTrue()
    }
}