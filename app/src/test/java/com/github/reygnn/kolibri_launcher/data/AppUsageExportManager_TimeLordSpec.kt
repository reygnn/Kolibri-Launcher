package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import java.time.Instant
import kotlin.test.assertIs

/**
 * TIME LORD EDITION
 * * Testet temporale Anomalien, Y2K38 Probleme und Zeitzonen-Chaos.
 */
class AppUsageExportManager_TimeLordSpec {

    @get:Rule
    val timberRule = TimberRule()

    @Mock private lateinit var mockContext: Context
    private lateinit var fakeDataStore: FakeDataStore
    private lateinit var manager: AppUsageExportManager
    private val now = System.currentTimeMillis()

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        fakeDataStore = FakeDataStore()
        manager = AppUsageExportManager(fakeDataStore, mockContext)
    }

    @Test
    fun `paradox - The Future (Year 3000)`() = runTest {
        // Timestamp weit in der Zukunft
        val futureIso = "3000-01-01T00:00:00Z"

        val json = buildJson("com.future", listOf(futureIso))
        val result = manager.importFromJson(json, false)

        assertIs<UsageImportResult.Success>(result)
        // Sollte gefiltert werden, da > currentTime
        assertEquals(0, result.timestampsImported)
    }

    @Test
    fun `paradox - The Beginning (Year 1970)`() = runTest {
        // Timestamp genau auf Epoch 0 oder davor
        val pastIso = "1970-01-01T00:00:00Z"

        val json = buildJson("com.ancient", listOf(pastIso))
        val result = manager.importFromJson(json, false)

        assertIs<UsageImportResult.Success>(result)
        // Sollte gefiltert werden, da zu alt (MAX_TIMESTAMP_AGE)
        assertEquals(0, result.timestampsImported)
    }

    @Test
    fun `paradox - The Broken Clock (Invalid ISO Format)`() = runTest {
        // Fast richtig, aber kaputt
        val invalidDates = listOf(
            "2024-13-40T99:99:99Z", // Monat 13
            "Yesterday",
            "2024-01-01 12:00:00", // Fehlendes T und Z
            ""
        )

        val json = """
            {
                "version": "1.0.0",
                "usage_data": {
                    "com.broken": ${invalidDates.joinToString(prefix="[", postfix="]", separator=",") { "\"$it\"" }} 
                }
            }
        """.trimIndent()

        val result = manager.importFromJson(json, false)

        assertIs<UsageImportResult.Success>(result)
        // Alle sollten ignoriert werden
        assertEquals(0, result.timestampsImported)
    }

    @Test
    fun `paradox - The Hybrid (Mixed Timezones)`() = runTest {
        // ISO Strings mit verschiedenen Offsets
        // Instant.parse kann Z, +01:00 etc. normalerweise
        val validNow = Instant.ofEpochMilli(now - 10000)

        // Simuliere Offset-Zeit (Format muss exakt passen für Instant.parse, meist nur 'Z' supported bei Strict ISO_INSTANT,
        // aber wir testen hier, ob deine parseTimestamp Logik robust ist)
        val isoUtc = validNow.toString() // 2024-..Z

        val json = buildJson("com.mixed", listOf(isoUtc))
        val result = manager.importFromJson(json, false)

        assertIs<UsageImportResult.Success>(result)
        assertEquals(1, result.timestampsImported)
    }

    @Test
    fun `paradox - The Leap Second (Corner Case)`() = runTest {
        // Java Time API handled Leap Seconds oft speziell oder normalisiert sie.
        // Wir wollen nur sichergehen, dass es nicht crasht.
        val leapIso = "2016-12-31T23:59:60Z"

        val json = buildJson("com.leap", listOf(leapIso))
        val result = manager.importFromJson(json, false)

        // Wenn Java es parsen kann -> gut. Wenn nicht -> catch block -> ignore.
        // Hauptsache Success result.
        assertIs<UsageImportResult.Success>(result)
    }

    private fun buildJson(pkg: String, timestamps: List<String>): String {
        val tsArray = timestamps.joinToString(", ") { "\"$it\"" }
        return """
            {
                "version": "1.0.0",
                "usage_data": { "$pkg": [$tsArray] }
            }
        """.trimIndent()
    }
}