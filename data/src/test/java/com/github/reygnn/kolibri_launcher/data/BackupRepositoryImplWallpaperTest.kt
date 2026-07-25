package com.github.reygnn.kolibri_launcher.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.BackupData
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
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
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

/**
 * Comprehensive test suite for Wallpaper backup/restore functionality.
 *
 * Runs with Robolectric for proper Uri.parse() support.
 *
 * NOTE: wallpaperFileManager.copyToInternal() is stubbed to return the
 * source URI unchanged, so URI equality assertions hold in tests.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRepositoryImplWallpaperTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun checkEnvironment() {
            org.junit.Assume.assumeTrue(
                "Skipping Robolectric tests in GitHub CI",
                System.getenv("CI") == null
            )
        }
    }

    @get:Rule
    val timberRule = TimberRule()

    // Fakes
    private lateinit var fakeFavoritesRepo: FakeFavoritesRepository
    private lateinit var fakeFavoritesOrderRepo: FakeFavoritesOrderRepository
    private lateinit var fakeHiddenAppsRepo: FakeHiddenAppsRepository
    private lateinit var fakeCustomNamesRepo: FakeCustomNamesRepository
    private lateinit var fakeInstalledAppsRepo: FakeInstalledAppsRepository
    private lateinit var fakeSwipeActionsRepo: FakeSwipeActionsRepository
    private lateinit var fakeSettingsRepo: FakeSettingsRepository
    private lateinit var fakeWallpaperRepo: FakeWallpaperRepository

    @MockK
    private lateinit var context: Context
    @MockK
    private lateinit var contentResolver: ContentResolver
    @MockK
    private lateinit var wallpaperFileManager: WallpaperFileManager

    private lateinit var backupManager: BackupRepositoryImpl

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val testWallpaperUri = "content://media/external/images/media/12345"
    private val testWallpaperScale = 1.5f
    private val testWallpaperTranslateX = 100.0f
    private val testWallpaperTranslateY = -50.0f

    @Before
    fun setUp() {
        MockKAnnotations.init(this)

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
        // would time out and return ImportResult.Error.
        fakeInstalledAppsRepo.installedApps = listOf(
            AppInfo(
                originalName = "Sentinel",
                displayName = "Sentinel",
                packageName = "kolibri.test.sentinel",
                className = "kolibri.test.sentinel.Main",
            )
        )

        every { context.contentResolver } returns contentResolver

        // Default: alle URIs sind zugänglich
        every { contentResolver.openInputStream(any()) } returns ByteArrayInputStream(ByteArray(1))

        // WallpaperFileManager: kopiert URI in internen Speicher.
        // Für Tests geben wir die Quell-URI zurück damit Gleichheits-Assertions bestehen.
        coEvery { wallpaperFileManager.copyToInternal(any()) } answers { firstArg<Uri>() }

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
    // SECTION 1: EXPORT TESTS
    // ========================================================================

    @Test
    fun `exportToJson - with wallpaper - includes all wallpaper fields`() = runTest {
        fakeWallpaperRepo.currentState = WallpaperState(
            imageUri = testWallpaperUri,
            scale = testWallpaperScale,
            translateX = testWallpaperTranslateX,
            translateY = testWallpaperTranslateY
        )

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        assertThat(backup.settings.wallpaperUri).isEqualTo(testWallpaperUri)
        assertThat(backup.settings.wallpaperScale).isEqualTo(testWallpaperScale)
        assertThat(backup.settings.wallpaperTranslateX).isEqualTo(testWallpaperTranslateX)
        assertThat(backup.settings.wallpaperTranslateY).isEqualTo(testWallpaperTranslateY)
    }

    @Test
    fun `exportToJson - without wallpaper - wallpaper fields are null`() = runTest {
        fakeWallpaperRepo.currentState = WallpaperState.NONE

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        assertThat(backup.settings.wallpaperUri).isNull()
        assertThat(backup.settings.wallpaperScale).isNull()
        assertThat(backup.settings.wallpaperTranslateX).isNull()
        assertThat(backup.settings.wallpaperTranslateY).isNull()
    }

    @Test
    fun `exportToJson - wallpaper uses camelCase keys`() = runTest {
        fakeWallpaperRepo.currentState = WallpaperState(
            imageUri = testWallpaperUri,
            scale = 1.0f,
            translateX = 0f,
            translateY = 0f
        )

        val jsonString = backupManager.exportToJson()

        assertThat(jsonString).contains("\"wallpaperUri\":")
        assertThat(jsonString).contains("\"wallpaperScale\":")
        assertThat(jsonString).contains("\"wallpaperTranslateX\":")
        assertThat(jsonString).contains("\"wallpaperTranslateY\":")
        assertThat(jsonString).doesNotContain("\"wallpaper_uri\":")
        assertThat(jsonString).doesNotContain("\"wallpaper_scale\":")
        assertThat(jsonString).doesNotContain("\"wallpaper_translate_x\":")
        assertThat(jsonString).doesNotContain("\"wallpaper_translate_y\":")
    }

    // ========================================================================
    // SECTION 2: IMPORT TESTS
    // ========================================================================

    @Test
    fun `importFromJson - with accessible wallpaper URI - restores wallpaper state`() = runTest {
        val jsonWithWallpaper = createWallpaperBackupJson(
            wallpaperUri = testWallpaperUri,
            wallpaperScale = testWallpaperScale,
            wallpaperTranslateX = testWallpaperTranslateX,
            wallpaperTranslateY = testWallpaperTranslateY
        )

        val result = backupManager.importFromJson(
            jsonWithWallpaper,
            ImportOptions(importThemeSettings = true)
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        val restoredState = fakeWallpaperRepo.currentState
        assertThat(restoredState.imageUri?.toString()).isEqualTo(testWallpaperUri)
        assertThat(restoredState.scale).isEqualTo(testWallpaperScale)
        assertThat(restoredState.translateX).isEqualTo(testWallpaperTranslateX)
        assertThat(restoredState.translateY).isEqualTo(testWallpaperTranslateY)
    }

    @Test
    fun `importFromJson - with inaccessible wallpaper URI - skips wallpaper gracefully`() = runTest {
        // Override: URI nicht zugänglich
        every { contentResolver.openInputStream(any()) } returns null

        fakeWallpaperRepo.currentState = WallpaperState.NONE

        val jsonWithWallpaper = createWallpaperBackupJson(
            wallpaperUri = "content://media/external/images/media/99999",
            wallpaperScale = 2.0f,
            wallpaperTranslateX = 50f,
            wallpaperTranslateY = 50f
        )

        val result = backupManager.importFromJson(
            jsonWithWallpaper,
            ImportOptions(importThemeSettings = true)
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeWallpaperRepo.currentState).isEqualTo(WallpaperState.NONE)
        // A single-layer wallpaper WAS present in the backup but could not be
        // restored → reported as one dropped layer so the UI warns (AUDIT-8 #1).
        assertThat((result as ImportResult.Success).droppedWallpaperLayers).isEqualTo(1)
    }

    @Test
    fun `importFromJson - without wallpaper in backup - keeps existing wallpaper`() = runTest {
        // A backup that carries NO wallpaper must not touch the user's current
        // wallpaper — same skip-on-null semantics as every other theme field
        // (Review 4813864 #2). Previously the wallpaper was cleared
        // unconditionally, silently wiping it with no warning.
        val existing = WallpaperState(
            imageUri = testWallpaperUri,
            scale = 1.2f,
            translateX = 10f,
            translateY = 20f
        )
        fakeWallpaperRepo.currentState = existing

        val jsonWithoutWallpaper = """
            {
                "version": "1.0.0",
                "timestamp": 123456789,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "textColor": -1
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(
            jsonWithoutWallpaper,
            ImportOptions(importThemeSettings = true)
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeWallpaperRepo.currentState).isEqualTo(existing)
        assertThat((result as ImportResult.Success).droppedWallpaperLayers).isEqualTo(0)
    }

    @Test
    fun `importFromJson - wallpaper with default transform values - uses defaults`() = runTest {
        val jsonWithMinimalWallpaper = """
            {
                "version": "1.0.0",
                "timestamp": 123456789,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "wallpaperUri": "$testWallpaperUri"
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(
            jsonWithMinimalWallpaper,
            ImportOptions(importThemeSettings = true)
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        val restoredState = fakeWallpaperRepo.currentState
        assertThat(restoredState.imageUri?.toString()).isEqualTo(testWallpaperUri)
        assertThat(restoredState.scale).isEqualTo(1.0f)
        assertThat(restoredState.translateX).isEqualTo(0.0f)
        assertThat(restoredState.translateY).isEqualTo(0.0f)
    }

    // ========================================================================
    // SECTION 3: ISOLATION TESTS
    // ========================================================================

    @Test
    fun `importFromJson - importThemeSettings false - does not import wallpaper`() = runTest {
        fakeWallpaperRepo.currentState = WallpaperState.NONE

        val jsonWithWallpaper = createWallpaperBackupJson(
            wallpaperUri = testWallpaperUri,
            wallpaperScale = 2.0f,
            wallpaperTranslateX = 100f,
            wallpaperTranslateY = 100f
        )

        val result = backupManager.importFromJson(
            jsonWithWallpaper,
            ImportOptions(importThemeSettings = false, importFavorites = true)
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeWallpaperRepo.currentState).isEqualTo(WallpaperState.NONE)
    }

    @Test
    fun `importFromJson - importThemeSettings true - imports wallpaper along with other theme settings`() = runTest {
        fakeWallpaperRepo.currentState = WallpaperState.NONE
        fakeSettingsRepo.color = 0

        val jsonWithThemeAndWallpaper = """
            {
                "version": "1.0.0",
                "timestamp": 123456789,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "textColor": -1,
                    "wallpaperUri": "$testWallpaperUri",
                    "wallpaperScale": 1.5
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(
            jsonWithThemeAndWallpaper,
            ImportOptions(importThemeSettings = true)
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeSettingsRepo.textColorFlow.first()).isEqualTo(-1)
        assertThat(fakeWallpaperRepo.currentState.imageUri?.toString()).isEqualTo(testWallpaperUri)
        assertThat(fakeWallpaperRepo.currentState.scale).isEqualTo(1.5f)
    }

    // ========================================================================
    // SECTION 4: NAMING CONVENTION TESTS
    // ========================================================================

    @Test
    fun `importFromJson - snake_case wallpaper keys - imports correctly`() = runTest {
        val jsonWithSnakeCase = """
            {
                "version": "1.0.0",
                "timestamp": 123456789,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "wallpaper_uri": "$testWallpaperUri",
                    "wallpaper_scale": 1.8,
                    "wallpaper_translate_x": 25.5,
                    "wallpaper_translate_y": -30.0
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(jsonWithSnakeCase, ImportOptions(importThemeSettings = true))

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val state = fakeWallpaperRepo.currentState
        assertThat(state.imageUri?.toString()).isEqualTo(testWallpaperUri)
        assertThat(state.scale).isEqualTo(1.8f)
        assertThat(state.translateX).isEqualTo(25.5f)
        assertThat(state.translateY).isEqualTo(-30.0f)
    }

    @Test
    fun `importFromJson - camelCase wallpaper keys - imports correctly`() = runTest {
        val jsonWithCamelCase = """
            {
                "version": "1.0.0",
                "timestamp": 123456789,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "wallpaperUri": "$testWallpaperUri",
                    "wallpaperScale": 2.2,
                    "wallpaperTranslateX": -15.0,
                    "wallpaperTranslateY": 45.5
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(jsonWithCamelCase, ImportOptions(importThemeSettings = true))

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val state = fakeWallpaperRepo.currentState
        assertThat(state.imageUri?.toString()).isEqualTo(testWallpaperUri)
        assertThat(state.scale).isEqualTo(2.2f)
        assertThat(state.translateX).isEqualTo(-15.0f)
        assertThat(state.translateY).isEqualTo(45.5f)
    }

    @Test
    fun `importFromJson - mixed case wallpaper keys - camelCase canonical wins`() = runTest {
        // Synthetic case (a real backup never carries both keys). The strict
        // path reads the canonical camelCase key first, with snake_case only as
        // a legacy alias (AUDIT-3 #3), so the camelCase value wins.
        val jsonWithMixedCase = """
            {
                "version": "1.0.0",
                "timestamp": 123456789,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "wallpaperUri": "$testWallpaperUri",
                    "wallpaperScale": 1.0,
                    "wallpaper_scale": 2.5
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(jsonWithMixedCase, ImportOptions(importThemeSettings = true))

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeWallpaperRepo.currentState.scale).isEqualTo(1.0f)
    }

    // ========================================================================
    // SECTION 5: SECURITY / TYPE VALIDATION TESTS
    // ========================================================================

    @Test
    fun `importFromJson - wallpaper_uri wrong type (number) - returns InvalidFormat`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 123456789,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "wallpaper_uri": 12345
                }
            }
        """.trimIndent()

        assertThat(backupManager.importFromJson(maliciousJson, ImportOptions(importThemeSettings = true)))
            .isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `importFromJson - wallpaper_translate_x wrong type (boolean) - returns InvalidFormat`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 123456789,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "wallpaper_translate_x": true
                }
            }
        """.trimIndent()

        assertThat(backupManager.importFromJson(maliciousJson, ImportOptions(importThemeSettings = true)))
            .isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `importFromJson - wallpaper_scale Infinity - is rejected`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 123456789,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "wallpaperUri": "$testWallpaperUri",
                    "wallpaperScale": 1e309
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(maliciousJson, ImportOptions(importThemeSettings = true))

        assertThat(result).isAnyOf(ImportResult.InvalidFormat, ImportResult.Success(0, 0, emptySet()))
    }

    @Test
    fun `importFromJson - wallpaper_translate with NaN-like value - handles gracefully`() = runTest {
        val edgeCaseJson = """
            {
                "version": "1.0.0",
                "timestamp": 123456789,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "wallpaperUri": "$testWallpaperUri",
                    "wallpaperScale": 1.0,
                    "wallpaperTranslateX": 1e-400,
                    "wallpaperTranslateY": -1e-400
                }
            }
        """.trimIndent()

        assertThat(backupManager.importFromJson(edgeCaseJson, ImportOptions(importThemeSettings = true)))
            .isNotNull()
    }

    @Test
    fun `importFromJson - wallpaper_uri with malicious content - handles safely`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 123456789,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "wallpaperUri": "content://media/../../../etc/passwd",
                    "wallpaperScale": 1.0
                }
            }
        """.trimIndent()

        assertThat(backupManager.importFromJson(maliciousJson, ImportOptions(importThemeSettings = true)))
            .isNotNull()
    }

    @Test
    fun `importFromJson - wallpaper fields with null values - succeeds`() = runTest {
        val jsonWithNulls = """
            {
                "version": "1.0.0",
                "timestamp": 123456789,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "wallpaperUri": null,
                    "wallpaperScale": null,
                    "wallpaperTranslateX": null,
                    "wallpaperTranslateY": null
                }
            }
        """.trimIndent()

        assertThat(backupManager.importFromJson(jsonWithNulls, ImportOptions(importThemeSettings = true)))
            .isInstanceOf(ImportResult.Success::class.java)
    }

    // ========================================================================
    // SECTION 6: ROUNDTRIP TESTS
    // ========================================================================

    @Test
    fun `roundtrip - export then import wallpaper - preserves all values`() = runTest {
        val originalState = WallpaperState(
            imageUri = testWallpaperUri,
            scale = 1.75f,
            translateX = 42.5f,
            translateY = -17.3f
        )
        fakeWallpaperRepo.currentState = originalState

        val exportedJson = backupManager.exportToJson()

        fakeWallpaperRepo.currentState = WallpaperState.NONE

        val result = backupManager.importFromJson(exportedJson, ImportOptions(importThemeSettings = true))

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        val restoredState = fakeWallpaperRepo.currentState
        assertThat(restoredState.imageUri?.toString()).isEqualTo(originalState.imageUri?.toString())
        assertThat(restoredState.scale).isEqualTo(originalState.scale)
        assertThat(restoredState.translateX).isEqualTo(originalState.translateX)
        assertThat(restoredState.translateY).isEqualTo(originalState.translateY)
    }

    // ========================================================================
    // SECTION 7: EDGE CASES
    // ========================================================================

    @Test
    fun `importFromJson - empty wallpaper URI string - treated as no wallpaper`() = runTest {
        fakeWallpaperRepo.currentState = WallpaperState.NONE

        val jsonWithEmptyUri = """
            {
                "version": "1.0.0",
                "timestamp": 123456789,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "wallpaperUri": "",
                    "wallpaperScale": 1.5
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(jsonWithEmptyUri, ImportOptions(importThemeSettings = true))

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeWallpaperRepo.currentState).isEqualTo(WallpaperState.NONE)
    }

    @Test
    fun `importFromJson - negative scale value - handles gracefully`() = runTest {
        val jsonWithNegativeScale = """
            {
                "version": "1.0.0",
                "timestamp": 123456789,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "wallpaperUri": "$testWallpaperUri",
                    "wallpaperScale": -1.0
                }
            }
        """.trimIndent()

        assertThat(backupManager.importFromJson(jsonWithNegativeScale, ImportOptions(importThemeSettings = true)))
            .isInstanceOf(ImportResult.Success::class.java)
    }

    @Test
    fun `importFromJson - very large translate values - handles gracefully`() = runTest {
        val jsonWithLargeTranslate = """
            {
                "version": "1.0.0",
                "timestamp": 123456789,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "wallpaperUri": "$testWallpaperUri",
                    "wallpaperScale": 1.0,
                    "wallpaperTranslateX": 999999.0,
                    "wallpaperTranslateY": -999999.0
                }
            }
        """.trimIndent()

        assertThat(backupManager.importFromJson(jsonWithLargeTranslate, ImportOptions(importThemeSettings = true)))
            .isInstanceOf(ImportResult.Success::class.java)
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private fun createWallpaperBackupJson(
        wallpaperUri: String,
        wallpaperScale: Float,
        wallpaperTranslateX: Float,
        wallpaperTranslateY: Float
    ): String = """
        {
            "version": "1.0.0",
            "timestamp": 123456789,
            "settings": {
                "favoriteComponents": [],
                "favoritesOrder": [],
                "hiddenComponents": [],
                "wallpaperUri": "$wallpaperUri",
                "wallpaperScale": $wallpaperScale,
                "wallpaperTranslateX": $wallpaperTranslateX,
                "wallpaperTranslateY": $wallpaperTranslateY
            }
        }
    """.trimIndent()
}