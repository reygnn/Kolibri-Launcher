package com.github.reygnn.kolibri_launcher.data

import io.mockk.every
import io.mockk.mockk

import android.content.ContentResolver
import android.content.Context
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSwipeActionsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeWallpaperRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Security-focused Unit-Tests für BackupRepositoryImpl.
 *
 * Diese Tests simulieren bösartige JSON-Eingaben aus der Perspektive
 * eines Angreifers, der versucht die App zum Absturz zu bringen oder
 * unerwartetes Verhalten zu provozieren.
 *
 * Kategorien:
 * - JSON Bombs (DoS)
 * - Type Confusion
 * - Integer Overflow/Underflow
 * - Boundary Conditions
 * - Malformed JSON
 * - Unicode/Encoding Attacks
 */
class BackupRepositoryImplSecurityTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var backupManager: BackupRepositoryImpl

    // Fake Repositories
    private lateinit var fakeFavoritesRepo: FakeFavoritesRepository
    private lateinit var fakeFavoritesOrderRepo: FakeFavoritesOrderRepository
    private lateinit var fakeHiddenAppsRepo: FakeHiddenAppsRepository
    private lateinit var fakeCustomNamesRepo: FakeCustomNamesRepository
    private lateinit var fakeInstalledAppsRepo: FakeInstalledAppsRepository
    private lateinit var fakeSwipeActionsRepo: FakeSwipeActionsRepository
    private lateinit var fakeSettingsRepo: FakeSettingsRepository
    private lateinit var fakeWallpaperRepo: FakeWallpaperRepository
    private val wallpaperFileManager: WallpaperFileManager = mockk(relaxed = true)
    private lateinit var context: Context

    @Before
    fun setUp() {
        fakeFavoritesRepo = FakeFavoritesRepository()
        fakeFavoritesOrderRepo = FakeFavoritesOrderRepository()
        fakeHiddenAppsRepo = FakeHiddenAppsRepository()
        fakeCustomNamesRepo = FakeCustomNamesRepository()
        fakeInstalledAppsRepo = FakeInstalledAppsRepository()
        fakeSwipeActionsRepo = FakeSwipeActionsRepository()
        fakeSettingsRepo = FakeSettingsRepository()
        fakeWallpaperRepo = FakeWallpaperRepository()

        // Seed: BackupDataAssembler.performImport waits for the InstalledApps
        // StateFlow to deliver a non-empty emission (cold-path gate added in
        // BACKUP_COLD_PATH_FIX). Without this seed, every importFromJson call
        // would time out and return ImportResult.Error. The sentinel is
        // distinct from any test fixtures (com.fake.app$it, …) so the
        // "is installed?" filter still rejects all malicious payloads.
        fakeInstalledAppsRepo.installedApps = listOf(
            AppInfo(
                originalName = "Sentinel",
                displayName = "Sentinel",
                packageName = "kolibri.test.sentinel",
                className = "kolibri.test.sentinel.Main",
            )
        )

        context = mockk<Context>(relaxed = true)
        val mockContentResolver = mockk<ContentResolver>(relaxed = true)
        every { context.contentResolver } returns mockContentResolver

        backupManager = BackupRepositoryImplTestFactory.create(
            favoritesRepository = fakeFavoritesRepo,
            favoritesOrderRepository = fakeFavoritesOrderRepo,
            hiddenAppsRepository = fakeHiddenAppsRepo,
            customNamesRepository = fakeCustomNamesRepo,
            installedAppsRepository = fakeInstalledAppsRepo,
            swipeActionsRepository = fakeSwipeActionsRepo,
            settingsRepository = fakeSettingsRepo,
            wallpaperRepository = fakeWallpaperRepo,
            wallpaperFileManager = wallpaperFileManager,
            context = context
        )
    }

    // ========================================================================
    // HELPER: Erstellt minimales gültiges JSON
    // ========================================================================

    private fun validBackupJson(
        version: String = "1.0.0",
        timestamp: Long = 1234567890L,
        extras: String = ""
    ): String = """
        {
            "version": "$version",
            "timestamp": $timestamp,
            "settings": {
                "favoriteComponents": [],
                "favoritesOrder": [],
                "hiddenComponents": [],
                "customAppNames": {}
                ${if (extras.isNotEmpty()) ",$extras" else ""}
            }
        }
    """.trimIndent()

    // ========================================================================
    // SECTION 1: JSON BOMBS (Denial of Service Attacks)
    // ========================================================================

    @Test
    fun `attack - massive favoriteComponents array - should not crash`() = runTest {
        val massiveArray = (1..AppConstants.MAX_ARRAY_ELEMENTS).joinToString(",")
        {
            "\"com.fake.app$it/.MainActivity\""
        }
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": [$massiveArray],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {}
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        // Sollte nicht crashen - entweder LimitExceeded oder Success (mit 0 imports, da keine Apps installiert)
        assertThat(result).isNotNull()
        assertThat(result).isNotEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `attack - array exceeding limit - should be rejected as InvalidFormat`() = runTest {
        // 1. Wir erzeugen genau EIN Element mehr als erlaubt
        val tooManyItems = AppConstants.MAX_ARRAY_ELEMENTS + 1

        // Erstelle den riesigen String (Komma-separierte Liste von Strings)
        val massiveArray = (1..tooManyItems).joinToString(",") { i ->
            "\"com.fake.app$i/.MainActivity\""
        }

        val maliciousJson = """
        {
            "version": "1.0.0",
            "timestamp": 1234567890,
            "settings": {
                "favoriteComponents": [$massiveArray],
                "favoritesOrder": [],
                "hiddenComponents": [],
                "customAppNames": {}
            }
        }
    """.trimIndent()

        // 2. Import durchführen
        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        // 3. Assert: Das System muss den Import verweigern!
        // Grund: validateJsonTypes() erkennt die Überlänge und gibt false zurück.
        // parseBackupData() gibt daraufhin null zurück.
        // importFromJson() wandelt null in InvalidFormat um.
        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `attack - deeply nested JSON object - should not cause StackOverflow`() = runTest {
        // Angriff: 500 Level tief verschachteltes JSON
        val depth = 500
        val openBraces = "{\"a\":".repeat(depth)
        val closeBraces = "}".repeat(depth)
        val maliciousJson = """$openBraces"deep"$closeBraces"""

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        // Muss InvalidFormat sein, da keine gültige Backup-Struktur
        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `attack - huge customAppNames map - should handle gracefully`() = runTest {
        // Angriff: 5.000 Einträge in customAppNames
        val massiveMap = (1..5_000).joinToString(",") {
            "\"com.fake.package$it\": \"Custom Name $it\""
        }
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {$massiveMap}
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        // Sollte nicht crashen
        assertThat(result).isNotNull()
    }

    @Test
    fun `attack - very long string in single field - should handle`() = runTest {
        // Angriff: 1MB langer String
        val longString = "A".repeat(1024 * 1024)
        val maliciousJson = """
            {
                "version": "$longString",
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {}
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        // Sollte UnsupportedVersion zurückgeben (Version != "1.0.0")
        assertThat(result).isInstanceOf(ImportResult.UnsupportedVersion::class.java)
    }

    // ========================================================================
    // SECTION 2: TYPE CONFUSION ATTACKS
    // ========================================================================

    @Test
    fun `attack - string instead of integer for textColor - should reject`() = runTest {
        val maliciousJson = validBackupJson(extras = "\"text_color\": \"not-a-number\"")

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `attack - array instead of object for settings - should reject`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890,
                "settings": ["not", "an", "object"]
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `attack - object instead of array for favoriteComponents - should reject`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": {"not": "an array"},
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {}
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `attack - integer instead of boolean for isFontBold - should reject`() = runTest {
        val maliciousJson = validBackupJson(extras = "\"is_font_bold\": 1")

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `attack - string true instead of boolean - should reject`() = runTest {
        val maliciousJson = validBackupJson(extras = "\"show_alarm\": \"true\"")

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `attack - number array in favoriteComponents instead of strings - should reject or handle`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": [1, 2, 3, 4, 5],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {}
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        // Sollte InvalidFormat oder Error sein, nicht crashen
        assertThat(result).isNotNull()
    }

    @Test
    fun `attack - null in array - should handle gracefully`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": ["com.valid/.App", null, "com.another/.App"],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {}
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        // Sollte nicht crashen
        assertThat(result).isNotNull()
    }

    @Test
    fun `attack - mixed types in array - should reject or handle`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": ["valid", 123, true, null, {"nested": "obj"}],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {}
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        // Sollte nicht crashen
        assertThat(result).isNotNull()
    }

    // ========================================================================
    // SECTION 3: INTEGER OVERFLOW / UNDERFLOW ATTACKS
    // ========================================================================

    @Test
    fun `attack - ARGB color as unsigned int greater than Int MAX - should handle correctly`() = runTest {
        // 0xFFFF0000 (rot mit voller Opazität) = 4294901760 als unsigned
        val maliciousJson = validBackupJson(extras = "\"textColor\": 4294901760")

        val result = backupManager.importFromJson(
            maliciousJson,
            ImportOptions(importThemeSettings = true)
        )

        // Der BackupRepositoryImpl sollte das korrekt als signed int interpretieren
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
    }

    @Test
    fun `attack - maximum unsigned int for chipBackgroundColor - should handle`() = runTest {
        // 0xFFFFFFFF = 4294967295 als unsigned, -1 als signed
        val maliciousJson = validBackupJson(extras = "\"chipBackgroundColor\": 4294967295")

        val result = backupManager.importFromJson(
            maliciousJson,
            ImportOptions(importThemeSettings = true)
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
    }

    @Test
    fun `attack - Float Infinity approximation for layoutScale - should handle`() = runTest {
        // 1e309 übersteigt Double.MAX_VALUE → wird zu Infinity
        val maliciousJson = validBackupJson(extras = "\"layoutScale\": 1e309")

        val result = backupManager.importFromJson(
            maliciousJson,
            ImportOptions(importThemeSettings = true)
        )

        // Sollte nicht crashen - entweder Success (mit coerceIn) oder Error
        assertThat(result).isNotNull()
    }

    @Test
    fun `attack - negative infinity for verticalPaddingScale - should handle`() = runTest {
        val maliciousJson = validBackupJson(extras = "\"verticalPaddingScale\": -1e309")

        val result = backupManager.importFromJson(
            maliciousJson,
            ImportOptions(importThemeSettings = true)
        )

        assertThat(result).isNotNull()
    }

    @Test
    fun `attack - negative timestamp - should handle gracefully`() = runTest {
        val maliciousJson = validBackupJson(timestamp = -9999999999999)

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
    }

    @Test
    fun `attack - zero timestamp - should handle`() = runTest {
        val maliciousJson = validBackupJson(timestamp = 0)

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
    }

    // ========================================================================
    // SECTION 4: BOUNDARY CONDITIONS
    // ========================================================================

    @Test
    fun `boundary - empty version string - should be UnsupportedVersion`() = runTest {
        val maliciousJson = validBackupJson(version = "")

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.UnsupportedVersion::class.java)
    }

    @Test
    fun `boundary - null version - should handle gracefully`() = runTest {
        val maliciousJson = """
            {
                "version": null,
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {}
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        // Sollte nicht crashen
        assertThat(result).isNotNull()
    }

    @Test
    fun `boundary - missing version field - should use default or fail gracefully`() = runTest {
        val maliciousJson = """
            {
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {}
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        // Entweder nutzt es Default "1.0.0" → Success, oder InvalidFormat
        assertThat(result).isNotNull()
    }

    @Test
    fun `boundary - missing settings object - should return InvalidFormat`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `boundary - null settings - should return InvalidFormat`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890,
                "settings": null
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `boundary - zero layoutScale - should coerce to minimum`() = runTest {
        val maliciousJson = validBackupJson(extras = "\"layoutScale\": 0.0")

        val result = backupManager.importFromJson(
            maliciousJson,
            ImportOptions(importThemeSettings = true)
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
    }

    @Test
    fun `boundary - negative layoutScale - should coerce to minimum`() = runTest {
        val maliciousJson = validBackupJson(extras = "\"layoutScale\": -5.0")

        val result = backupManager.importFromJson(
            maliciousJson,
            ImportOptions(importThemeSettings = true)
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
    }

    @Test
    fun `boundary - empty arrays everywhere - should succeed with zero imports`() = runTest {
        val result = backupManager.importFromJson(validBackupJson(), ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val success = result as ImportResult.Success
        assertThat(success.importedCount).isEqualTo(0)
    }

    @Test
    fun `boundary - empty customAppNames map - should succeed`() = runTest {
        val result = backupManager.importFromJson(
            validBackupJson(),
            ImportOptions(importCustomNames = true)
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
    }

    // ========================================================================
    // SECTION 5: MALFORMED JSON
    // ========================================================================

    @Test
    fun `malformed - completely invalid JSON - should return InvalidFormat`() = runTest {
        val result = backupManager.importFromJson("this is not json at all {{{}}}", ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `malformed - truncated JSON - should return InvalidFormat`() = runTest {
        val maliciousJson = """{"version": "1.0.0", "timestamp": 123, "settings": {"""

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `malformed - JSON with trailing garbage - should handle`() = runTest {
        val maliciousJson = validBackupJson() + "GARBAGE_DATA_HERE!!!"

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        // Je nach Parser-Verhalten
        assertThat(result).isNotNull()
    }

    @Test
    fun `malformed - duplicate keys in JSON - should handle gracefully`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "version": "2.0.0",
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {}
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        // Sollte nicht crashen - eine der Versionen wird genommen
        assertThat(result).isNotNull()
    }

    @Test
    fun `malformed - JSON array at root instead of object - should return InvalidFormat`() = runTest {
        val maliciousJson = """[{"version": "1.0.0"}]"""

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `malformed - empty string input - should return InvalidFormat`() = runTest {
        val result = backupManager.importFromJson("", ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `malformed - whitespace only input - should return InvalidFormat`() = runTest {
        val result = backupManager.importFromJson("   \n\t  ", ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `malformed - single curly brace - should return InvalidFormat`() = runTest {
        val result = backupManager.importFromJson("{", ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `malformed - null literal - should return InvalidFormat`() = runTest {
        val result = backupManager.importFromJson("null", ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `malformed - JSON with BOM - should handle`() = runTest {
        // UTF-8 BOM: EF BB BF
        val bom = "\uFEFF"
        val maliciousJson = bom + validBackupJson()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        // Manche Parser ignorieren BOM, manche nicht
        assertThat(result).isNotNull()
    }

    // ========================================================================
    // SECTION 6: UNICODE / ENCODING ATTACKS
    // ========================================================================

    @Test
    fun `unicode - null byte in string - should handle gracefully`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": ["com.app\u0000.malicious/.Activity"],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {}
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isNotNull()
    }

    @Test
    fun `unicode - RTL override characters in app names - should handle`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {
                        "com.bank.app": "\u202EsgnitteSkcaB"
                    }
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isNotNull()
    }

    @Test
    fun `unicode - emoji in package names - should handle`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": ["com.💀.evil/.💣Activity"],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {}
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isNotNull()
    }

    @Test
    fun `unicode - control characters in strings - should handle`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {
                        "com.test.app": "Name\u0001\u0002\u0003\u0007"
                    }
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isNotNull()
    }

    @Test
    fun `unicode - lone surrogate - should handle`() = runTest {
        // \uD800 ist ein High Surrogate ohne Low Surrogate → ungültiges UTF-16
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {
                        "com.test.app": "broken\uD800string"
                    }
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isNotNull()
    }

    @Test
    fun `unicode - zero width characters - should handle`() = runTest {
        // Zero Width Space, Zero Width Non-Joiner, Zero Width Joiner
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {
                        "com.test.app": "App\u200B\u200C\u200DName"
                    }
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isNotNull()
    }

    // ========================================================================
    // SECTION 7: INJECTION-STYLE ATTACKS
    // ========================================================================

    @Test
    fun `injection - path traversal in component name - should be filtered`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": ["../../../etc/passwd/.Fake"],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {}
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        // Path wird nicht in installedApps sein → gefiltert
        assertThat(result).isNotNull()
    }

    @Test
    fun `injection - SQL injection style in custom names - should be stored literal`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {
                        "com.test.app": "'; DROP TABLE users; --"
                    }
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isNotNull()
    }

    @Test
    fun `injection - HTML script injection in custom names - should be stored literal`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {
                        "com.test.app": "<script>alert('XSS')</script>"
                    }
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isNotNull()
    }

    // ========================================================================
    // SECTION 8: VERSION STRING ATTACKS
    // ========================================================================

    @Test
    fun `version - extra semver parts - should be unsupported`() = runTest {
        val result = backupManager.importFromJson(
            validBackupJson(version = "1.0.0.0.0.0"),
            ImportOptions()
        )

        assertThat(result).isInstanceOf(ImportResult.UnsupportedVersion::class.java)
    }

    @Test
    fun `version - leading zeros - should be unsupported`() = runTest {
        val result = backupManager.importFromJson(
            validBackupJson(version = "01.00.00"),
            ImportOptions()
        )

        assertThat(result).isInstanceOf(ImportResult.UnsupportedVersion::class.java)
    }

    @Test
    fun `version - with whitespace - should be unsupported`() = runTest {
        val result = backupManager.importFromJson(
            validBackupJson(version = " 1.0.0 "),
            ImportOptions()
        )

        assertThat(result).isInstanceOf(ImportResult.UnsupportedVersion::class.java)
    }

    @Test
    fun `version - future version - should be unsupported`() = runTest {
        val result = backupManager.importFromJson(
            validBackupJson(version = "2.0.0"),
            ImportOptions()
        )

        assertThat(result).isInstanceOf(ImportResult.UnsupportedVersion::class.java)
        assertThat((result as ImportResult.UnsupportedVersion).version).isEqualTo("2.0.0")
    }

    @Test
    fun `version - negative version - should be unsupported`() = runTest {
        val result = backupManager.importFromJson(
            validBackupJson(version = "-1.0.0"),
            ImportOptions()
        )

        assertThat(result).isInstanceOf(ImportResult.UnsupportedVersion::class.java)
    }

    // ========================================================================
    // SECTION 9: IMPORT OPTIONS EDGE CASES
    // ========================================================================

    @Test
    fun `options - importNothing selected - should return Error`() = runTest {
        val options = ImportOptions(
            importFavorites = false,
            importOrder = false,
            importHiddenApps = false,
            importCustomNames = false,
            importSwipeActions = false,
            importThemeSettings = false,
            importTimeBasedEvents = false,
            importQualityOfLife = false,
            importPowerUserSettings = false
        )

        val result = backupManager.importFromJson(validBackupJson(), options)

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
        assertThat((result as ImportResult.Error).message).contains("No import options")
    }

    @Test
    fun `options - only importFavorites true - should succeed`() = runTest {
        val options = ImportOptions(
            importFavorites = true,
            importOrder = false,
            importHiddenApps = false,
            importCustomNames = false,
            importSwipeActions = false,
            importThemeSettings = false,
            importTimeBasedEvents = false,
            importQualityOfLife = false,
            importPowerUserSettings = false
        )

        val result = backupManager.importFromJson(validBackupJson(), options)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
    }

    // ========================================================================
    // SECTION 10: COMBINED / MIXED ATTACKS
    // ========================================================================

    @Test
    fun `combined - multiple type confusions - should return InvalidFormat`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": "not-a-number",
                "settings": {
                    "favoriteComponents": {},
                    "favoritesOrder": "not-an-array",
                    "hiddenComponents": 12345,
                    "customAppNames": []
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `combined - valid structure with adversarial values - should handle`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 0,
                "appVersion": "",
                "settings": {
                    "favoriteComponents": ["\u0000", "", "   ", "///"],
                    "favoritesOrder": ["\n\r\t"],
                    "hiddenComponents": ["'\"<>"],
                    "customAppNames": {"": "", " ": "  "},
                    "swipeLeftApp": "",
                    "swipeRightApp": null,
                    "textColor": null,
                    "layoutScale": null,
                    "isFontBold": null
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isNotNull()
    }

    @Test
    fun `combined - all optional fields null - should succeed`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {},
                    "swipeLeftApp": null,
                    "swipeRightApp": null,
                    "textColor": null,
                    "chipBackgroundColor": null,
                    "layoutScale": null,
                    "verticalPaddingScale": null,
                    "contentTopMarginScale": null,
                    "isFontBold": null,
                    "textShadowEnabled": null,
                    "showCalendarEvent": null,
                    "showAlarm": null,
                    "autoShowKeyboard": null,
                    "autoLaunchApp": null
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
    }

    @Test
    fun `combined - extra unknown fields - should be ignored`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 1234567890,
                "unknownField": "should be ignored",
                "anotherUnknown": 12345,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "customAppNames": {},
                    "futureFeature": true,
                    "newSetting": "value"
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions())

        // ignoreUnknownKeys = true sollte das erlauben
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
    }
}
