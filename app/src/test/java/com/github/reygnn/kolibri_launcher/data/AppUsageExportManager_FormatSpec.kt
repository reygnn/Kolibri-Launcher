package com.github.reygnn.kolibri_launcher.data

import io.mockk.mockk

import android.content.Context
import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test

class AppUsageExportManager_FormatSpec {

    @get:Rule
    val timberRule = TimberRule()

    private val context: Context = mockk(relaxed = true)

    private lateinit var manager: AppUsageExportManager
    private lateinit var dataStore: FakeDataStore

    @Before
    fun setUp() {
        dataStore = FakeDataStore()
        manager = AppUsageExportManager(dataStore, context)
    }

    private fun recentTimestamp(hoursAgo: Long = 1): Long =
        System.currentTimeMillis() - (hoursAgo * 60 * 60 * 1000)

    private fun recentIsoTimestamp(hoursAgo: Long = 1): String =
        java.time.Instant.ofEpochMilli(recentTimestamp(hoursAgo)).toString()

    @Test
    fun `should parse camelCase JSON correctly`() = runTest {
        val ts1 = recentTimestamp(1)
        val ts2 = recentTimestamp(2)

        val jsonCamelCase = """
            {
                "version": "1.0.0",
                "exportTimestamp": "${recentIsoTimestamp()}",
                "usageData": {
                    "com.example.camel": [$ts1, $ts2]
                }
            }
        """.trimIndent()

        val result = manager.importFromJson(jsonCamelCase, mergeWithExisting = false)

        assertThat(result).isInstanceOf(UsageImportResult.Success::class.java)
        val success = result as UsageImportResult.Success
        assertThat(success.packagesImported).isEqualTo(1)
        assertThat(success.timestampsImported).isEqualTo(2)
    }

    @Test
    fun `should parse snake_case JSON correctly`() = runTest {
        val ts = recentTimestamp(1)

        val jsonSnakeCase = """
            {
                "version": "1.0.0",
                "export_timestamp": "${recentIsoTimestamp()}",
                "usage_data": {
                    "com.example.snake": [$ts]
                }
            }
        """.trimIndent()

        val result = manager.importFromJson(jsonSnakeCase, mergeWithExisting = false)

        assertThat(result).isInstanceOf(UsageImportResult.Success::class.java)
        val success = result as UsageImportResult.Success
        assertThat(success.packagesImported).isEqualTo(1)
    }

    @Test
    fun `should handle mixed formats`() = runTest {
        val tsLong = recentTimestamp(1)
        val tsIso = recentIsoTimestamp(2)

        val mixedJson = """
            {
                "version": "1.0.0",
                "export_timestamp": "${recentIsoTimestamp()}",
                "usageData": {
                    "com.example.mixed": [$tsLong, "$tsIso"]
                }
            }
        """.trimIndent()

        val result = manager.importFromJson(mixedJson, mergeWithExisting = false)

        assertThat(result).isInstanceOf(UsageImportResult.Success::class.java)
        val success = result as UsageImportResult.Success
        assertThat(success.packagesImported).isEqualTo(1)
        assertThat(success.timestampsImported).isEqualTo(2)
    }

    @Test
    fun `should prefer camelCase if both keys exist`() = runTest {
        val ts = recentTimestamp(1)

        val ambiguousJson = """
            {
                "version": "1.0.0",
                "usageData": {
                    "com.winner.camel": [$ts]
                },
                "usage_data": {
                    "com.loser.snake": [$ts]
                }
            }
        """.trimIndent()

        val result = manager.importFromJson(ambiguousJson, mergeWithExisting = false)

        assertThat(result).isInstanceOf(UsageImportResult.Success::class.java)
        val success = result as UsageImportResult.Success
        assertThat(success.packagesImported).isEqualTo(1)
    }

    @Test
    fun `should fail gracefully on invalid types (Security Check)`() = runTest {
        // GIVEN: Type Confusion (Array statt Object für usage_data)
        val maliciousJson = """
            {
                "version": "1.0.0",
                "usage_data": [ "i am", "not", "an object" ]
            }
        """.trimIndent()

        // WHEN
        val result = manager.importFromJson(maliciousJson, mergeWithExisting = false)

        // THEN
        assertThat(result).isInstanceOf(UsageImportResult.InvalidFormat::class.java)
    }
}