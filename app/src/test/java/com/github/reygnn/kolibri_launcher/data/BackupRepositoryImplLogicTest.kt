package com.github.reygnn.kolibri_launcher.data

import io.mockk.every
import io.mockk.mockk

import android.content.ContentResolver
import android.content.Context
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.fakes.*
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Logic-focused Unit-Tests (Blue Team).
 *
 * Ergänzt die Security-Tests. Prüft den "Happy Path" und die Business-Logik:
 * - Exportiert der Manager die Daten korrekt aus den Repositories?
 * - Importiert er Daten korrekt in die Repositories?
 * - Funktioniert die Filterung nicht-installierter Apps?
 */
class BackupRepositoryImplLogicTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var backupManager: BackupRepositoryImpl

    // Fakes
    private lateinit var fakeFavoritesRepo: FakeFavoritesRepository
    private lateinit var fakeFavoritesOrderRepo: FakeFavoritesOrderRepository
    private lateinit var fakeHiddenAppsRepo: FakeHiddenAppsRepository
    private lateinit var fakeCustomNamesRepo: FakeCustomNamesRepository
    private lateinit var fakeInstalledAppsRepo: FakeInstalledAppsRepository
    private lateinit var fakeSwipeActionsRepo: FakeSwipeActionsRepository
    private lateinit var fakeSettingsRepo: FakeSettingsRepository
    private lateinit var fakeWallpaperRepo: FakeWallpaperRepository
    private val wallpaperFileManager: WallpaperFileManager = mockk(relaxed = true)

    // Mocks
    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver

    @Before
    fun setUp() {
        // Fakes initialisieren
        fakeFavoritesRepo = FakeFavoritesRepository()
        fakeFavoritesOrderRepo = FakeFavoritesOrderRepository()
        fakeHiddenAppsRepo = FakeHiddenAppsRepository()
        fakeCustomNamesRepo = FakeCustomNamesRepository()
        fakeInstalledAppsRepo = FakeInstalledAppsRepository()
        fakeSwipeActionsRepo = FakeSwipeActionsRepository()
        fakeSettingsRepo = FakeSettingsRepository()
        fakeWallpaperRepo = FakeWallpaperRepository()

        // Mock Context
        context = mockk<Context>(relaxed = true)
        contentResolver = mockk<ContentResolver>(relaxed = true)
        every { context.contentResolver } returns contentResolver

        backupManager = BackupRepositoryImpl(
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
    // TEST 1: EXPORT (Daten sammeln)
    // ========================================================================

    @Test
    fun `exportToJson - collects data from repositories correctly`() = runTest {
        // ARRANGE: Wir füllen die Fakes mit Testdaten
        fakeSettingsRepo.setTextColor(-123456)
        fakeSettingsRepo.setSplitModeThreshold(42)

        // Simuliere eine existierende Custom App Bezeichnung
        // (Für Export ist installedApps egal, er nimmt einfach was im Repo steht)
        fakeCustomNamesRepo.setCustomNameForPackage("com.test.app", "My Cool App")

        // Simuliere Favoriten
        fakeFavoritesRepo.saveFavoriteComponents(listOf("com.test.app/.MainActivity"))

        // ACT
        val jsonString = backupManager.exportToJson()

        // ASSERT: Prüfen, ob das JSON die Werte enthält
        // 2025-11-29: Standard ist neu camelCase.
        assertThat(jsonString).contains("\"textColor\": -123456")
        assertThat(jsonString).contains("\"splitModeThreshold\": 42")
        assertThat(jsonString).contains("\"com.test.app\": \"My Cool App\"")
        assertThat(jsonString).contains("com.test.app/.MainActivity")
        assertThat(jsonString).contains("\"version\": \"1.0.0\"")
    }

    // ========================================================================
    // TEST 2: IMPORT (Daten schreiben)
    // ========================================================================

    @Test
    fun `importFromJson - updates settings repositories correctly`() = runTest {
        // ARRANGE
        val json = """
            {
                "version": "1.0.0",
                "settings": {
                    "text_color": -65536,
                    "split_mode_threshold": 99,
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": []
                }
            }
        """.trimIndent()

        // ACT
        val result = backupManager.importFromJson(
            json,
            ImportOptions(importThemeSettings = true, importPowerUserSettings = true)
        )

        // ASSERT
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // Prüfen, ob die Daten im Fake angekommen sind
        val newColor = fakeSettingsRepo.textColorFlow.first()
        assertThat(newColor).isEqualTo(-65536)

        val newThreshold = fakeSettingsRepo.splitModeThresholdFlow.first()
        assertThat(newThreshold).isEqualTo(99)
    }

    @Test
    fun `importFromJson - imports custom names ONLY if app is installed`() = runTest {
        // ARRANGE
        // Wir nutzen dein FakeInstalledAppsRepository!
        // Nur "com.whatsapp" ist installiert.
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.whatsapp", ".Main")
        )

        val json = """
            {
                "version": "1.0.0",
                "settings": {
                    "customAppNames": {
                        "com.whatsapp": "Was Geht App",
                        "com.missing.app": "Ghost App" 
                    },
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": []
                }
            }
        """.trimIndent()

        // ACT
        val result = backupManager.importFromJson(
            json,
            ImportOptions(importCustomNames = true)
        )

        // ASSERT
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val success = result as ImportResult.Success

        // "com.whatsapp" sollte importiert sein (da installiert)
        assertThat(fakeCustomNamesRepo.getDisplayNameForPackage("com.whatsapp", "Original"))
            .isEqualTo("Was Geht App")

        // "com.missing.app" sollte ignoriert worden sein (da nicht installiert)
        // Das FakeCustomNamesRepo gibt den Originalnamen zurück, wenn kein Custom Name existiert.
        assertThat(fakeCustomNamesRepo.getDisplayNameForPackage("com.missing.app", "Original"))
            .isEqualTo("Original")

        // Statistik Check
        assertThat(success.importedCount).isEqualTo(0) // custom names zählen nicht zum globalen "importedCount" im Result, das zählt oft nur Favoriten
    }

    // ========================================================================
    // TEST 3: FILTERING (Logik-Prüfung)
    // ========================================================================

    @Test
    fun `importFromJson - filters out favorite apps that are not installed`() = runTest {
        // ARRANGE
        // Dein Fake: Nur "com.exist" ist installiert.
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.exist", ".MainActivity")
        )

        val json = """
            {
                "version": "1.0.0",
                "settings": {
                    "favoriteComponents": [
                        "com.exist/com.exist.MainActivity",
                        "com.missing/com.missing.MainActivity"
                    ],
                    "favoritesOrder": [],
                    "hiddenComponents": []
                }
            }
        """.trimIndent()

        // ACT
        val result = backupManager.importFromJson(json, ImportOptions(importFavorites = true))

        // ASSERT
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val success = result as ImportResult.Success

        // Statistik prüfen
        assertThat(success.importedCount).isEqualTo(1) // Nur com.exist wurde importiert
        assertThat(success.missingApps).contains("com.missing/com.missing.MainActivity")

        // Repo prüfen: Darf NUR die installierte App enthalten
        val savedFavorites = fakeFavoritesRepo.favoriteComponentsFlow.first()
        assertThat(savedFavorites).contains("com.exist/com.exist.MainActivity")
        assertThat(savedFavorites).doesNotContain("com.missing/com.missing.MainActivity")
    }

    @Test
    fun `importFromJson - respects partial import options`() = runTest {
        // ARRANGE: JSON hat Theme und PowerUser Werte
        val json = """
            {
                "version": "1.0.0",
                "settings": {
                    "text_color": -1,
                    "split_mode_threshold": 50,
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": []
                }
            }
        """.trimIndent()

        // Initiale Werte im Repo auf 0 setzen
        fakeSettingsRepo.setTextColor(0)
        fakeSettingsRepo.setSplitModeThreshold(0)

        // ACT: Wir importieren NUR Theme (importPowerUserSettings = false)
        val result = backupManager.importFromJson(
            json,
            ImportOptions(importThemeSettings = true, importPowerUserSettings = false)
        )

        // ASSERT
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // Theme sollte aktualisiert sein (-1)
        assertThat(fakeSettingsRepo.textColorFlow.first()).isEqualTo(-1)

        // PowerUser Setting sollte UNVERÄNDERT bleiben (0), da Option false war
        assertThat(fakeSettingsRepo.splitModeThresholdFlow.first()).isEqualTo(0)
    }

    // --- Helper ---

    /**
     * Erstellt ein AppInfo Objekt basierend auf deiner reinen Datenklasse.
     */
    private fun createAppInfo(packageName: String, classNameSuffix: String): AppInfo {
        // Simuliert die Logik, dass .Activity zu package.Activity wird
        val className = if (classNameSuffix.startsWith(".")) {
            "$packageName$classNameSuffix" // e.g. com.whatsapp.Main
        } else {
            classNameSuffix
        }

        return AppInfo(
            originalName = "App $packageName",
            displayName = "App $packageName",
            packageName = packageName,
            className = className, // Vollqualifizierter Klassenname (wichtig für componentName Logik)
            isSystemApp = false,
            isFavorite = false
        )
    }
}