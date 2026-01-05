package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.fakes.*
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Verifies JSON naming convention behavior:
 * - Export always uses camelCase (property names)
 * - Import accepts both camelCase AND snake_case (via @JsonNames for backward compatibility)
 */
class BackupManagerNamingConventionTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var backupManager: BackupManager
    private lateinit var fakeSettingsRepo: FakeSettingsRepository
    private lateinit var fakeFavoritesRepo: FakeFavoritesRepository
    private lateinit var fakeFavoritesOrderRepo: FakeFavoritesOrderRepository
    private lateinit var fakeHiddenAppsRepo: FakeHiddenAppsRepository
    private lateinit var fakeCustomNamesRepo: FakeCustomNamesRepository
    private lateinit var fakeInstalledAppsRepo: FakeInstalledAppsRepository
    private lateinit var fakeSwipeActionsRepo: FakeSwipeActionsRepository
    private lateinit var fakeWallpaperRepo: FakeWallpaperRepository

    @Before
    fun setUp() {
        fakeSettingsRepo = FakeSettingsRepository()
        fakeFavoritesRepo = FakeFavoritesRepository()
        fakeFavoritesOrderRepo = FakeFavoritesOrderRepository()
        fakeHiddenAppsRepo = FakeHiddenAppsRepository()
        fakeCustomNamesRepo = FakeCustomNamesRepository()
        fakeInstalledAppsRepo = FakeInstalledAppsRepository()
        fakeSwipeActionsRepo = FakeSwipeActionsRepository()
        fakeWallpaperRepo = FakeWallpaperRepository()

        val mockContext = mock(Context::class.java)
        `when`(mockContext.contentResolver).thenReturn(mock())

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
    // EXPORT: Always camelCase
    // ========================================================================

    @Test
    fun `export - uses camelCase for all setting keys`() = runTest {
        fakeSettingsRepo.setTextColor(-123456)
        fakeSettingsRepo.setChipBackgroundColor(-654321)
        fakeSettingsRepo.setTextShadowEnabled(true)
        fakeSettingsRepo.setLayoutScale(1.5f)
        fakeSettingsRepo.setSplitModeThreshold(42)
        fakeSettingsRepo.setDoubleTapToLockEnabled(true)
        fakeSettingsRepo.setSwipeDownToNotificationsEnabled(false)

        val json = backupManager.exportToJson()

        // Verify camelCase is used
        assertThat(json).contains("\"textColor\":")
        assertThat(json).contains("\"chipBackgroundColor\":")
        assertThat(json).contains("\"textShadowEnabled\":")
        assertThat(json).contains("\"layoutScale\":")
        assertThat(json).contains("\"splitModeThreshold\":")
        assertThat(json).contains("\"doubleTapToLockEnabled\":")
        assertThat(json).contains("\"swipeDownToNotificationsEnabled\":")

        // Verify snake_case is NOT used
        assertThat(json).doesNotContain("\"text_color\":")
        assertThat(json).doesNotContain("\"chip_bg_color\":")
        assertThat(json).doesNotContain("\"text_shadow_enabled\":")
        assertThat(json).doesNotContain("\"layout_scale\":")
        assertThat(json).doesNotContain("\"split_mode_threshold\":")
        assertThat(json).doesNotContain("\"double_tap_to_lock_enabled\":")
        assertThat(json).doesNotContain("\"swipe_down_to_notifications_enabled\":")
    }

    @Test
    fun `export - uses camelCase for collection keys`() = runTest {
        fakeFavoritesRepo.saveFavoriteComponents(listOf("com.test/.Main"))
        fakeHiddenAppsRepo.hiddenApps = setOf("com.hidden/.Main")
        fakeCustomNamesRepo.setCustomNameForPackage("com.test", "Test App")

        val json = backupManager.exportToJson()

        assertThat(json).contains("\"favoriteComponents\":")
        assertThat(json).contains("\"hiddenComponents\":")
        assertThat(json).contains("\"customAppNames\":")

        assertThat(json).doesNotContain("\"favorite_components\":")
        assertThat(json).doesNotContain("\"hidden_components\":")
        assertThat(json).doesNotContain("\"custom_app_names\":")
    }

    // ========================================================================
    // IMPORT: Accepts camelCase (new format)
    // ========================================================================

    @Test
    fun `import - accepts camelCase settings`() = runTest {
        val json = """
            {
                "version": "1.0.0",
                "settings": {
                    "textColor": -111111,
                    "chipBackgroundColor": -222222,
                    "splitModeThreshold": 55,
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": []
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(
            json,
            ImportOptions(importThemeSettings = true, importPowerUserSettings = true)
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeSettingsRepo.textColorFlow.first()).isEqualTo(-111111)
        assertThat(fakeSettingsRepo.chipBackgroundColorFlow.first()).isEqualTo(-222222)
        assertThat(fakeSettingsRepo.splitModeThresholdFlow.first()).isEqualTo(55)
    }

    // ========================================================================
    // IMPORT: Accepts snake_case (legacy format / user preference)
    // ========================================================================

    @Test
    fun `import - accepts snake_case settings for backward compatibility`() = runTest {
        val json = """
            {
                "version": "1.0.0",
                "settings": {
                    "text_color": -333333,
                    "chip_bg_color": -444444,
                    "split_mode_threshold": 77,
                    "favoriteComponents": [],
                    "favoritesOrder": [],
                    "hiddenComponents": []
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(
            json,
            ImportOptions(importThemeSettings = true, importPowerUserSettings = true)
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeSettingsRepo.textColorFlow.first()).isEqualTo(-333333)
        assertThat(fakeSettingsRepo.chipBackgroundColorFlow.first()).isEqualTo(-444444)
        assertThat(fakeSettingsRepo.splitModeThresholdFlow.first()).isEqualTo(77)
    }

    @Test
    fun `import - accepts snake_case collection keys`() = runTest {
        fakeInstalledAppsRepo.installedApps = listOf(
            createTestAppInfo("com.test")
        )

        val json = """
            {
                "version": "1.0.0",
                "settings": {
                    "favorite_components": ["com.test/com.test.Main"],
                    "favorites_order": ["com.test/com.test.Main"],
                    "hidden_components": [],
                    "custom_app_names": {"com.test": "Snake App"}
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(
            json,
            ImportOptions(
                importFavorites = true,
                importCustomNames = true
            )
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeFavoritesRepo.favoriteComponentsFlow.first())
            .contains("com.test/com.test.Main")
        assertThat(fakeCustomNamesRepo.getDisplayNameForPackage("com.test", "Default"))
            .isEqualTo("Snake App")
    }

    // ========================================================================
    // IMPORT: Mixed formats (edge case)
    // ========================================================================

    @Test
    fun `import - accepts mixed camelCase and snake_case in same file`() = runTest {
        fakeInstalledAppsRepo.installedApps = listOf(
            createTestAppInfo("com.test")
        )

        // User manually edited file with inconsistent naming
        val json = """
            {
                "version": "1.0.0",
                "settings": {
                    "textColor": -555555,
                    "split_mode_threshold": 88,
                    "favoriteComponents": ["com.test/com.test.Main"],
                    "hidden_components": []
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(
            json,
            ImportOptions(
                importThemeSettings = true,
                importPowerUserSettings = true,
                importFavorites = true,
                importHiddenApps = true
            )
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeSettingsRepo.textColorFlow.first()).isEqualTo(-555555)
        assertThat(fakeSettingsRepo.splitModeThresholdFlow.first()).isEqualTo(88)
        assertThat(fakeFavoritesRepo.favoriteComponentsFlow.first())
            .contains("com.test/com.test.Main")
    }

    // ========================================================================
    // ROUNDTRIP: Export → Import
    // ========================================================================

    @Test
    fun `roundtrip - exported JSON can be re-imported correctly`() = runTest {
        val componentName = "com.roundtrip/com.roundtrip.Main"

        // Setup initial data
        fakeSettingsRepo.setTextColor(-999999)
        fakeSettingsRepo.setSplitModeThreshold(123)
        fakeFavoritesRepo.saveFavoriteComponents(listOf(componentName))
        fakeInstalledAppsRepo.installedApps = listOf(
            createTestAppInfo("com.roundtrip")
        )

        // Export
        val exportedJson = backupManager.exportToJson()

        // Reset repos
        fakeSettingsRepo.setTextColor(0)
        fakeSettingsRepo.setSplitModeThreshold(0)
        fakeFavoritesRepo.saveFavoriteComponents(emptyList())

        // Re-import
        val result = backupManager.importFromJson(
            exportedJson,
            ImportOptions(
                importThemeSettings = true,
                importPowerUserSettings = true,
                importFavorites = true
            )
        )

        // Verify
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeSettingsRepo.textColorFlow.first()).isEqualTo(-999999)
        assertThat(fakeSettingsRepo.splitModeThresholdFlow.first()).isEqualTo(123)
        assertThat(fakeFavoritesRepo.favoriteComponentsFlow.first())
            .contains(componentName)
    }

    @Test
    fun `import - when both camelCase and snake_case present - last value wins`() = runTest {
        val json = """
        {
            "version": "1.0.0",
            "settings": {
                "textColor": -111,
                "text_color": -222,
                "favoriteComponents": [],
                "hiddenComponents": []
            }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(json, ImportOptions(importThemeSettings = true))

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeSettingsRepo.textColorFlow.first()).isEqualTo(-222)
    }

    @Test
    fun `importFromJson ignores malformed mixed case keys`() = runTest {
        // Initiale Werte setzen
        fakeSettingsRepo.setSplitModeThreshold(42)
        fakeSettingsRepo.setTextColor(-111)
        fakeSettingsRepo.setChipBackgroundColor(-222)

        val mixedCaseJson = """
        {
          "version": "1.0.0",
          "settings": {
            "split_modeThreshold": 99,
            "text_Color": -123456,
            "chipBackground_color": -654321,
            "favoriteComponents": [],
            "hiddenComponents": []
          }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(
            mixedCaseJson,
            ImportOptions(importThemeSettings = true, importPowerUserSettings = true)
        )

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // Werte sollten UNVERÄNDERT sein (malformed keys ignoriert)
        assertThat(fakeSettingsRepo.splitModeThresholdFlow.first()).isEqualTo(42)
        assertThat(fakeSettingsRepo.textColorFlow.first()).isEqualTo(-111)
        assertThat(fakeSettingsRepo.chipBackgroundColorFlow.first()).isEqualTo(-222)
    }

    // --- Helper ---

    private fun createTestAppInfo(packageName: String) =
        com.github.reygnn.kolibri_launcher.domain.model.AppInfo(
            originalName = "App $packageName",
            displayName = "App $packageName",
            packageName = packageName,
            className = "$packageName.Main",
            isSystemApp = false,
            isFavorite = false
        )
}