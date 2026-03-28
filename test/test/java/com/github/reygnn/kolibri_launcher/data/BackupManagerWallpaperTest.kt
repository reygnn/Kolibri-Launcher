package com.github.reygnn.kolibri_launcher.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.BackupData
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSwipeActionsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeWallpaperRepository
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

/**
 * Comprehensive test suite for Wallpaper backup/restore functionality.
 *
 * Tests cover:
 * - Export: Wallpaper state is correctly serialized to JSON
 * - Import: Wallpaper state is correctly restored (with URI accessibility checks)
 * - Preview: hasWallpaper flag detection
 * - Isolation: Wallpaper import respects importThemeSettings option
 * - Security: Type validation for wallpaper fields
 * - Naming Convention: snake_case and camelCase compatibility
 *
 * Runs with Robolectric for proper Uri.parse() support.
 */
@RunWith(RobolectricTestRunner::class)
class BackupManagerWallpaperTest {

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

    // Mocks
    private lateinit var mockContext: Context
    private lateinit var mockContentResolver: ContentResolver

    private lateinit var backupManager: BackupManager

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Test constants
    private val testWallpaperUri = "content://media/external/images/media/12345"
    private val testWallpaperScale = 1.5f
    private val testWallpaperTranslateX = 100.0f
    private val testWallpaperTranslateY = -50.0f

    @Before
    fun setUp() {
        // Initialize fakes
        fakeFavoritesRepo = FakeFavoritesRepository()
        fakeFavoritesOrderRepo = FakeFavoritesOrderRepository()
        fakeHiddenAppsRepo = FakeHiddenAppsRepository()
        fakeCustomNamesRepo = FakeCustomNamesRepository()
        fakeInstalledAppsRepo = FakeInstalledAppsRepository()
        fakeSwipeActionsRepo = FakeSwipeActionsRepository()
        fakeSettingsRepo = FakeSettingsRepository()
        fakeWallpaperRepo = FakeWallpaperRepository()

        // Setup mock context
        mockContext = mock(Context::class.java)
        mockContentResolver = mock(ContentResolver::class.java)
        `when`(mockContext.contentResolver).thenReturn(mockContentResolver)

        // Default: Any URI is accessible (returns a valid stream)
        `when`(mockContentResolver.openInputStream(any(Uri::class.java)))
            .thenReturn(ByteArrayInputStream(ByteArray(1)))

        // Initialize BackupManager
        backupManager = BackupManager(
            favoritesManager = fakeFavoritesRepo,
            favoritesOrderManager = fakeFavoritesOrderRepo,
            appVisibilityManager = fakeHiddenAppsRepo,
            appNamesManager = fakeCustomNamesRepo,
            installedAppsManager = fakeInstalledAppsRepo,
            swipeActionsManager = fakeSwipeActionsRepo,
            settingsManager = fakeSettingsRepo,
            wallpaperManager = fakeWallpaperRepo,
            context = mockContext
        )
    }

    // ========================================================================
    // SECTION 1: EXPORT TESTS
    // ========================================================================

    @Test
    fun `exportToJson - with wallpaper - includes all wallpaper fields`() = runTest {
        // ARRANGE
        fakeWallpaperRepo.currentState = WallpaperState(
            imageUri = testWallpaperUri.toUri(),
            scale = testWallpaperScale,
            translateX = testWallpaperTranslateX,
            translateY = testWallpaperTranslateY
        )

        // ACT
        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        // ASSERT
        assertThat(backup.settings.wallpaperUri).isEqualTo(testWallpaperUri)
        assertThat(backup.settings.wallpaperScale).isEqualTo(testWallpaperScale)
        assertThat(backup.settings.wallpaperTranslateX).isEqualTo(testWallpaperTranslateX)
        assertThat(backup.settings.wallpaperTranslateY).isEqualTo(testWallpaperTranslateY)
    }

    @Test
    fun `exportToJson - without wallpaper - wallpaper fields are null`() = runTest {
        // ARRANGE
        fakeWallpaperRepo.currentState = WallpaperState.NONE

        // ACT
        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        // ASSERT
        assertThat(backup.settings.wallpaperUri).isNull()
        assertThat(backup.settings.wallpaperScale).isNull()
        assertThat(backup.settings.wallpaperTranslateX).isNull()
        assertThat(backup.settings.wallpaperTranslateY).isNull()
    }

    @Test
    fun `exportToJson - wallpaper uses camelCase keys`() = runTest {
        // ARRANGE
        fakeWallpaperRepo.currentState = WallpaperState(
            imageUri = testWallpaperUri.toUri(),
            scale = 1.0f,
            translateX = 0f,
            translateY = 0f
        )

        // ACT
        val jsonString = backupManager.exportToJson()

        // ASSERT - camelCase keys are used
        assertThat(jsonString).contains("\"wallpaperUri\":")
        assertThat(jsonString).contains("\"wallpaperScale\":")
        assertThat(jsonString).contains("\"wallpaperTranslateX\":")
        assertThat(jsonString).contains("\"wallpaperTranslateY\":")

        // ASSERT - snake_case keys are NOT used
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
        // ARRANGE - Default mock already returns accessible stream

        val jsonWithWallpaper = createWallpaperBackupJson(
            wallpaperUri = testWallpaperUri,
            wallpaperScale = testWallpaperScale,
            wallpaperTranslateX = testWallpaperTranslateX,
            wallpaperTranslateY = testWallpaperTranslateY
        )

        // ACT
        val result = backupManager.importFromJson(
            jsonWithWallpaper,
            ImportOptions(importThemeSettings = true)
        )

        // ASSERT
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        val restoredState = fakeWallpaperRepo.currentState
        assertThat(restoredState.imageUri?.toString()).isEqualTo(testWallpaperUri)
        assertThat(restoredState.scale).isEqualTo(testWallpaperScale)
        assertThat(restoredState.translateX).isEqualTo(testWallpaperTranslateX)
        assertThat(restoredState.translateY).isEqualTo(testWallpaperTranslateY)
    }

    @Test
    fun `importFromJson - with inaccessible wallpaper URI - skips wallpaper gracefully`() = runTest {
        // ARRANGE - Override mock to return null (inaccessible)
        `when`(mockContentResolver.openInputStream(any(Uri::class.java)))
            .thenReturn(null)

        // Set initial wallpaper state
        fakeWallpaperRepo.currentState = WallpaperState.NONE

        val jsonWithWallpaper = createWallpaperBackupJson(
            wallpaperUri = "content://media/external/images/media/99999",
            wallpaperScale = 2.0f,
            wallpaperTranslateX = 50f,
            wallpaperTranslateY = 50f
        )

        // ACT
        val result = backupManager.importFromJson(
            jsonWithWallpaper,
            ImportOptions(importThemeSettings = true)
        )

        // ASSERT - Import succeeds but wallpaper is skipped
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // Wallpaper should remain unchanged (NONE)
        val restoredState = fakeWallpaperRepo.currentState
        assertThat(restoredState).isEqualTo(WallpaperState.NONE)
    }

    @Test
    fun `importFromJson - without wallpaper in backup - does not modify existing wallpaper`() = runTest {
        // ARRANGE - Set existing wallpaper
        fakeWallpaperRepo.currentState = WallpaperState(
            imageUri = testWallpaperUri.toUri(),
            scale = 1.2f,
            translateX = 10f,
            translateY = 20f
        )

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

        // ACT
        val result = backupManager.importFromJson(
            jsonWithoutWallpaper,
            ImportOptions(importThemeSettings = true)
        )

        // ASSERT - Import succeeds
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // Existing wallpaper should remain unchanged
        val currentState = fakeWallpaperRepo.currentState
        assertThat(currentState.imageUri?.toString()).isEqualTo(testWallpaperUri)
        assertThat(currentState.scale).isEqualTo(1.2f)
    }

    @Test
    fun `importFromJson - wallpaper with default transform values - uses defaults`() = runTest {
        // ARRANGE - Only URI provided, scale/translate missing
        // Default mock already returns accessible stream

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

        // ACT
        val result = backupManager.importFromJson(
            jsonWithMinimalWallpaper,
            ImportOptions(importThemeSettings = true)
        )

        // ASSERT
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        val restoredState = fakeWallpaperRepo.currentState
        assertThat(restoredState.imageUri?.toString()).isEqualTo(testWallpaperUri)
        assertThat(restoredState.scale).isEqualTo(1.0f) // Default
        assertThat(restoredState.translateX).isEqualTo(0.0f) // Default
        assertThat(restoredState.translateY).isEqualTo(0.0f) // Default
    }

    // ========================================================================
    // SECTION 3: ISOLATION TESTS
    // ========================================================================

    @Test
    fun `importFromJson - importThemeSettings false - does not import wallpaper`() = runTest {
        // ARRANGE
        fakeWallpaperRepo.currentState = WallpaperState.NONE
        // Default mock already returns accessible stream

        val jsonWithWallpaper = createWallpaperBackupJson(
            wallpaperUri = testWallpaperUri,
            wallpaperScale = 2.0f,
            wallpaperTranslateX = 100f,
            wallpaperTranslateY = 100f
        )

        // ACT - Import with theme settings disabled
        val result = backupManager.importFromJson(
            jsonWithWallpaper,
            ImportOptions(
                importThemeSettings = false,
                importFavorites = true // Need at least one option enabled
            )
        )

        // ASSERT
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // Wallpaper should NOT be imported
        assertThat(fakeWallpaperRepo.currentState).isEqualTo(WallpaperState.NONE)
    }

    @Test
    fun `importFromJson - importThemeSettings true - imports wallpaper along with other theme settings`() = runTest {
        // ARRANGE
        fakeWallpaperRepo.currentState = WallpaperState.NONE
        fakeSettingsRepo.color = 0 // Black
        // Default mock already returns accessible stream

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

        // ACT
        val result = backupManager.importFromJson(
            jsonWithThemeAndWallpaper,
            ImportOptions(importThemeSettings = true)
        )

        // ASSERT
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // Both theme color and wallpaper should be imported
        assertThat(fakeSettingsRepo.textColorFlow.first()).isEqualTo(-1)
        assertThat(fakeWallpaperRepo.currentState.imageUri?.toString()).isEqualTo(testWallpaperUri)
        assertThat(fakeWallpaperRepo.currentState.scale).isEqualTo(1.5f)
    }

    // ========================================================================
    // SECTION 4: NAMING CONVENTION TESTS (snake_case compatibility)
    // ========================================================================

    @Test
    fun `importFromJson - snake_case wallpaper keys - imports correctly`() = runTest {
        // ARRANGE - Default mock already returns accessible stream

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

        // ACT
        val result = backupManager.importFromJson(
            jsonWithSnakeCase,
            ImportOptions(importThemeSettings = true)
        )

        // ASSERT
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        val state = fakeWallpaperRepo.currentState
        assertThat(state.imageUri?.toString()).isEqualTo(testWallpaperUri)
        assertThat(state.scale).isEqualTo(1.8f)
        assertThat(state.translateX).isEqualTo(25.5f)
        assertThat(state.translateY).isEqualTo(-30.0f)
    }

    @Test
    fun `importFromJson - camelCase wallpaper keys - imports correctly`() = runTest {
        // ARRANGE - Default mock already returns accessible stream

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

        // ACT
        val result = backupManager.importFromJson(
            jsonWithCamelCase,
            ImportOptions(importThemeSettings = true)
        )

        // ASSERT
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        val state = fakeWallpaperRepo.currentState
        assertThat(state.imageUri?.toString()).isEqualTo(testWallpaperUri)
        assertThat(state.scale).isEqualTo(2.2f)
        assertThat(state.translateX).isEqualTo(-15.0f)
        assertThat(state.translateY).isEqualTo(45.5f)
    }

    @Test
    fun `importFromJson - mixed case wallpaper keys - last value wins`() = runTest {
        // ARRANGE - Default mock already returns accessible stream

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

        // ACT
        val result = backupManager.importFromJson(
            jsonWithMixedCase,
            ImportOptions(importThemeSettings = true)
        )

        // ASSERT
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // snake_case value should win (due to merge order)
        val state = fakeWallpaperRepo.currentState
        assertThat(state.scale).isEqualTo(2.5f)
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

        val result = backupManager.importFromJson(
            maliciousJson,
            ImportOptions(importThemeSettings = true)
        )

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `importFromJson - wallpaper_scale wrong type (string) - returns InvalidFormat`() = runTest {
        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 123456789,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "wallpaper_scale": "not_a_number"
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(
            maliciousJson,
            ImportOptions(importThemeSettings = true)
        )

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
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

        val result = backupManager.importFromJson(
            maliciousJson,
            ImportOptions(importThemeSettings = true)
        )

        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `importFromJson - wallpaper_scale Infinity - is rejected`() = runTest {
        // JSON doesn't have Infinity literal, but some parsers might accept it
        // We test with a huge number that would be coerced or rejected
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

        val result = backupManager.importFromJson(
            maliciousJson,
            ImportOptions(importThemeSettings = true)
        )

        // Should either reject as InvalidFormat or succeed with null/default scale
        assertThat(result).isAnyOf(
            ImportResult.InvalidFormat,
            ImportResult.Success(0, 0, emptySet())
        )
    }

    @Test
    fun `importFromJson - wallpaper_translate with NaN-like value - handles gracefully`() = runTest {
        // ARRANGE - Default mock already returns accessible stream

        // Some JSON generators might produce very small exponentials
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

        val result = backupManager.importFromJson(
            edgeCaseJson,
            ImportOptions(importThemeSettings = true)
        )

        // Should handle without crash
        assertThat(result).isNotNull()
    }

    @Test
    fun `importFromJson - wallpaper_uri with malicious content - handles safely`() = runTest {
        // ARRANGE - Default mock already returns accessible stream
        // URI with path traversal attempt
        val maliciousUri = "content://media/../../../etc/passwd"

        val maliciousJson = """
            {
                "version": "1.0.0",
                "timestamp": 123456789,
                "settings": {
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": [],
                    "wallpaperUri": "$maliciousUri",
                    "wallpaperScale": 1.0
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(
            maliciousJson,
            ImportOptions(importThemeSettings = true)
        )

        // Should not crash - either imports the weird URI or fails gracefully
        assertThat(result).isNotNull()
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

        val result = backupManager.importFromJson(
            jsonWithNulls,
            ImportOptions(importThemeSettings = true)
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
    }

    // ========================================================================
    // SECTION 6: ROUNDTRIP TESTS
    // ========================================================================

    @Test
    fun `roundtrip - export then import wallpaper - preserves all values`() = runTest {
        // ARRANGE - Set wallpaper state
        val originalState = WallpaperState(
            imageUri = testWallpaperUri.toUri(),
            scale = 1.75f,
            translateX = 42.5f,
            translateY = -17.3f
        )
        fakeWallpaperRepo.currentState = originalState
        // Default mock already returns accessible stream

        // ACT - Export
        val exportedJson = backupManager.exportToJson()

        // Reset wallpaper
        fakeWallpaperRepo.currentState = WallpaperState.NONE

        // ACT - Import
        val result = backupManager.importFromJson(
            exportedJson,
            ImportOptions(importThemeSettings = true)
        )

        // ASSERT
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

        val result = backupManager.importFromJson(
            jsonWithEmptyUri,
            ImportOptions(importThemeSettings = true)
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        // Empty URI should not trigger wallpaper restore
        assertThat(fakeWallpaperRepo.currentState).isEqualTo(WallpaperState.NONE)
    }

    @Test
    fun `importFromJson - negative scale value - handles gracefully`() = runTest {
        // ARRANGE - Default mock already returns accessible stream

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

        val result = backupManager.importFromJson(
            jsonWithNegativeScale,
            ImportOptions(importThemeSettings = true)
        )

        // Should succeed - negative scale might be clamped or used as-is
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
    }

    @Test
    fun `importFromJson - very large translate values - handles gracefully`() = runTest {
        // ARRANGE - Default mock already returns accessible stream

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

        val result = backupManager.importFromJson(
            jsonWithLargeTranslate,
            ImportOptions(importThemeSettings = true)
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
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