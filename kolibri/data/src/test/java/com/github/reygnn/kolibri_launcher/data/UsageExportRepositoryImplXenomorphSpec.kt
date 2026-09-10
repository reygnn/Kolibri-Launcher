package com.github.reygnn.kolibri_launcher.data

import io.mockk.mockk

import android.content.Context
import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertIs

/**
 * XENOMORPH & PREDATOR EDITION
 * * Security Stress Tests für UsageExportManager.
 * Simuliert bösartige Angriffe auf die JSON-Struktur und den Parser.
 */
class UsageExportRepositoryImplXenomorphSpec {

    @get:Rule
    val timberRule = TimberRule()

    private val context: Context = mockk(relaxed = true)
    private lateinit var fakeDataStore: FakeDataStore
    private lateinit var manager: UsageExportRepositoryImpl

    @Before
    fun setup() {
        fakeDataStore = FakeDataStore()
        manager = UsageExportRepositoryImpl(fakeDataStore, context, "test-version")
    }

    @Test
    fun `attack - The Nesting Doll (Stack Overflow Attempt)`() = runTest {
        // PREDATOR TACTIC: 500 Ebenen tief verschachteltes JSON
        // Ziel: StackOverflowError im Parser oder Validator provozieren.

        val depth = 500
        val open = "{\"a\":".repeat(depth)
        val close = "}".repeat(depth)
        val toxicJson = """
            {
                "version": "1.0.0",
                "usage_data": $open "trap" $close
            }
        """.trimIndent()

        val result = manager.importFromJson(toxicJson, false)

        // Der Validator sollte hier entweder false zurückgeben oder Exception fangen
        // Wichtig ist: KEIN CRASH der JVM.
        assertTrue(
            "Should reject deeply nested JSON without crashing",
            result is UsageImportResult.InvalidFormat || result is UsageImportResult.Error
        )
    }

    @Test
    fun `attack - The Black Hole (Memory Exhaustion via Huge Strings)`() = runTest {
        // XENOMORPH TACTIC: Ein valides Feld mit 10MB Müll füllen.
        // Ziel: OutOfMemoryError oder extrem lange Blockierzeit.

        val massiveString = "A".repeat(5_000_000) // 5MB String
        val toxicJson = """
            {
                "version": "1.0.0",
                "usage_data": {
                    "$massiveString": [123456789]
                }
            }
        """.trimIndent()

        // Der Manager sollte das entweder verarbeiten (wenn RAM reicht) oder ablehnen,
        // aber der Thread darf nicht sterben.
        val result = manager.importFromJson(toxicJson, false)

        // Da wir im Test viel RAM haben, könnte es klappen, aber der Key ist Quatsch -> Success mit 0 Importen
        // oder InvalidFormat wegen Länge.
        assertIs<UsageImportResult.Success>(result)
    }

    @Test
    fun `attack - The Polymorph (Type Confusion - Array inside Array)`() = runTest {
        // TACTIC: Statt eines Timestamps ein weiteres Array schicken.
        // [ [123], 123 ]

        val toxicJson = """
            {
                "version": "1.0.0",
                "usage_data": {
                    "com.test": [ [123456], 123456 ]
                }
            }
        """.trimIndent()

        val result = manager.importFromJson(toxicJson, false)
        assertIs<UsageImportResult.InvalidFormat>(result)
    }

    @Test
    fun `attack - The Ghost (Null Byte Injection)`() = runTest {
        // TACTIC: Null-Bytes im Package-Namen, um Dateisysteme oder C-Libraries zu verwirren.

        val toxicJson = """
            {
                "version": "1.0.0",
                "usage_data": {
                    "com.test.app\u0000.evil": [123456789]
                }
            }
        """.trimIndent()

        val result = manager.importFromJson(toxicJson, false)

        // Sollte als Success durchgehen (wird gespeichert), darf aber beim Speichern
        // im DataStore keinen Key-Fehler werfen.
        assertIs<UsageImportResult.Success>(result)
    }

    @Test
    fun `attack - The mimic (Duplicate Keys)`() = runTest {
        // Strict Parsing ist besser für Security.
        // Wenn org.json Duplicate Keys ablehnt, ist das ein valides Verhalten für InvalidFormat.
        val toxicJson = """
            {
                "version": "1.0.0",
                "usage_data": {
                    "com.test": [1000],
                    "com.test": [2000]
                }
            }
        """.trimIndent()

        val result = manager.importFromJson(toxicJson, false)

        // FIX: Wir erwarten jetzt InvalidFormat, da JSONObject(str) bei Duplikaten
        // oft eine Exception wirft, die validateJsonStructure() als false interpretiert.
        assertIs<UsageImportResult.InvalidFormat>(result)
    }
}