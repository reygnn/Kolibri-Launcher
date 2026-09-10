package com.github.reygnn.kolibri_launcher.data

import io.mockk.mockk

import android.content.Context
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BackupRepositoryImplStrictTest {

    @get:Rule
    val timberRule = TimberRule()

    // Fakes (Stateful in-memory implementations)
    private lateinit var favoritesRepo: FakeFavoritesRepository
    private lateinit var favoritesOrderRepo: FakeFavoritesOrderRepository
    private lateinit var hiddenAppsRepo: FakeHiddenAppsRepository
    private lateinit var customNamesRepo: FakeCustomNamesRepository
    private lateinit var installedAppsRepo: FakeInstalledAppsRepository
    private lateinit var swipeActionsRepo: FakeSwipeActionsRepository
    private lateinit var settingsRepo: FakeSettingsRepository
    private lateinit var fakeWallpaperRepo: FakeWallpaperRepository
    private val wallpaperFileManager: WallpaperFileManager = mockk(relaxed = true)

    // Mocks
    private val context: Context = mockk(relaxed = true)

    // System Under Test
    private lateinit var backupManager: BackupRepositoryImpl

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        // 1. Initialize Fakes
        favoritesRepo = FakeFavoritesRepository()
        favoritesOrderRepo = FakeFavoritesOrderRepository()
        hiddenAppsRepo = FakeHiddenAppsRepository()
        customNamesRepo = FakeCustomNamesRepository()
        installedAppsRepo = FakeInstalledAppsRepository()
        swipeActionsRepo = FakeSwipeActionsRepository()
        settingsRepo = FakeSettingsRepository()
        fakeWallpaperRepo = FakeWallpaperRepository()

        // 2. Pre-fill Installed Apps
        // Necessary so the Import logic (which filters based on installed apps) works correctly.
        val dummyApp = AppInfo(
            originalName = "Example App",
            displayName = "Example App",
            packageName = "com.example.app",
            className = "com.example.app.MainActivity",
            isFavorite = false
        )
        installedAppsRepo.installedApps = listOf(dummyApp)

        // 3. Initialize BackupRepositoryImpl with Fakes
        backupManager = BackupRepositoryImplTestFactory.create(
            favoritesRepository = favoritesRepo,
            favoritesOrderRepository = favoritesOrderRepo,
            hiddenAppsRepository = hiddenAppsRepo,
            customNamesRepository = customNamesRepo,
            installedAppsRepository = installedAppsRepo,
            swipeActionsRepository = swipeActionsRepo,
            settingsRepository = settingsRepo,
            wallpaperRepository = fakeWallpaperRepo,
            wallpaperFileManager = wallpaperFileManager,
            context = context
        )
    }

    /**
     * TEST: Fallback Mechanism (Hybrid Parsing)
     *
     * Scenario: The JSON is structurally valid, but the root object contains a data type
     * mismatch (String instead of Long for 'timestamp') that causes kotlinx.serialization to fail.
     *
     * Expected Behavior:
     * 1. `json.decodeFromString` fails with SerializationException.
     * 2. `catch` block intercepts the error.
     * 3. `tryStrictParsing` takes over using org.json.
     * 4. `org.json`'s `optLong` handles the bad timestamp string gracefully.
     * 5. The strict parsing logic successfully extracts the settings.
     */
    @Test
    fun `importFromJson triggers strict parsing fallback when kotlinx fails and imports correctly`() = runTest(testDispatcher) {
        // GIVEN - A JSON that breaks kotlinx (timestamp is string) but works with org.json
        val trickyJson = """
            {
              "version": "1.0.0",
              "timestamp": "THIS_STRING_BREAKS_KOTLINX_SERIALIZATION",
              "settings": {
                "text_color": -65536,
                "favoriteComponents": [],
                "favoritesOrder": [],
                "hiddenComponents": []
              }
            }
        """.trimIndent()

        val options = ImportOptions(
            importThemeSettings = true // We want to verify text_color
        )

        // WHEN
        val result = backupManager.importFromJson(trickyJson, options)

        // THEN
        // 1. The import must be successful (despite the internal exception)
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // 2. Verify that data was actually written to the repository
        // -65536 is Color.RED
        val importedTextColor = settingsRepo.textColorFlow.first()
        assertThat(importedTextColor).isEqualTo(-65536)
    }

    /**
     * TEST: Type Confusion Protection
     *
     * Scenario: A JSON file contains a type mismatch inside the 'settings' object
     * (e.g., text_color is a String instead of an Int).
     *
     * Expected Behavior:
     * `validateJsonTypes` detects the mismatch and returns false *before* any parsing is attempted.
     */
    @Test
    fun `importFromJson rejects malformed types via validateJsonTypes before parsing`() = runTest(testDispatcher) {
        // GIVEN - JSON with Type Confusion Attack (String instead of Int)
        val maliciousJson = """
            {
              "version": "1.0.0",
              "settings": {
                "text_color": "I_AM_NOT_A_NUMBER_HACKER_ATTACK",
                "favoriteComponents": []
              }
            }
        """.trimIndent()

        val options = ImportOptions(importThemeSettings = true)

        // WHEN
        val result = backupManager.importFromJson(maliciousJson, options)

        // THEN
        // Must be InvalidFormat because parseBackupData returns null
        assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    /**
     * TEST: Snake Case Compatibility (org.json path)
     *
     * Scenario: The JSON uses snake_case keys (standard for manual edits or older versions)
     * instead of camelCase keys.
     *
     * Expected Behavior:
     * The `mergeWithStrictValues` logic (powered by org.json) correctly identifies
     * the snake_case keys and maps them to the internal model.
     */
    @Test
    fun `importFromJson correctly imports strict snake_case values`() = runTest(testDispatcher) {
        // GIVEN - JSON using snake_case keys explicitly
        val validJson = """
            {
              "version": "1.0.0",
              "timestamp": 123456789,
              "settings": {
                "is_font_bold": true,
                "favoriteComponents": []
              }
            }
        """.trimIndent()

        val options = ImportOptions(
            importPowerUserSettings = true,
            importThemeSettings = true
        )

        // WHEN
        val result = backupManager.importFromJson(validJson, options)

        // THEN
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // Check Boolean
        val isBold = settingsRepo.isFontBoldStateFlow.first()
        assertThat(isBold).isTrue()
    }

    /**
     * TEST: Unsigned Color Integer Handling (Strict Parsing)
     *
     * Scenario: JSON contains a large unsigned integer for color (e.g., 0xFFFFFFFF for White).
     * org.json might read this as a Long. We need to ensure it's correctly cast to Int (-1).
     *
     * Expected Behavior:
     * The strict parser logic `getLong(key).toInt()` handles the overflow correctly.
     */
    @Test
    fun `importFromJson via strict parsing handles unsigned integer colors correctly`() = runTest(testDispatcher) {
        // GIVEN - JSON with White color as unsigned integer (4294967295)
        // We force strict parsing by breaking the timestamp again
        val jsonWithUnsignedColor = """
            {
              "version": "1.0.0",
              "timestamp": "BREAK_ME",
              "settings": {
                "text_color": 4294967295
              }
            }
        """.trimIndent()

        val options = ImportOptions(importThemeSettings = true)

        // WHEN
        val result = backupManager.importFromJson(jsonWithUnsignedColor, options)

        // THEN
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        val importedColor = settingsRepo.textColorFlow.first()
        // 4294967295 as unsigned int is -1 (White) as signed int
        assertThat(importedColor).isEqualTo(-1)
    }

    /**
     * TEST: List Parsing (Strict Parsing)
     *
     * Scenario: Ensure `getStrictStringList` works with actual data.
     */
    @Test
    fun `importFromJson via strict parsing correctly reads populated string lists`() = runTest(testDispatcher) {
        // GIVEN - A valid app that matches the installed app in setup()
        // Note: AppInfo logic generates "package/package.Class" if class name doesn't start with dot
        val validApp = "com.example.app/com.example.app.MainActivity"

        // We force strict parsing via timestamp break
        val jsonWithList = """
            {
              "version": "1.0.0",
              "timestamp": "BREAK_ME",
              "settings": {
                "favoriteComponents": ["$validApp"]
              }
            }
        """.trimIndent()

        val options = ImportOptions(importFavorites = true)

        // WHEN
        val result = backupManager.importFromJson(jsonWithList, options)

        // THEN
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        val favorites = favoritesRepo.favoriteComponentsFlow.first()
        assertThat(favorites).contains(validApp)
    }

    /**
     * TEST: camelCase scalar recovery on the strict-parsing path (AUDIT-3 #3)
     *
     * The app serializes with kotlinx and no @SerialName, so real backups use
     * camelCase keys. When the kotlinx primary path throws (here forced via a
     * string timestamp) the org.json strict-recovery path takes over. Before
     * the fix that path looked up snake_case keys only, so every scalar setting
     * in a real (camelCase) backup silently reset to its default. This asserts
     * the camelCase scalars are recovered on the strict path.
     */
    @Test
    fun `importFromJson strict fallback recovers camelCase scalar settings`() = runTest(testDispatcher) {
        // camelCase settings (the app's real encodeToString output); timestamp
        // is a string to force the org.json strict path.
        val camelCaseJson = """
            {
              "version": "1.0.0",
              "timestamp": "BREAK_ME",
              "settings": {
                "textColor": -65536,
                "isFontBold": true,
                "favoriteComponents": []
              }
            }
        """.trimIndent()

        val options = ImportOptions(
            importThemeSettings = true,
            importPowerUserSettings = true
        )

        // WHEN
        val result = backupManager.importFromJson(camelCaseJson, options)

        // THEN — both scalars survive the strict path instead of resetting.
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(settingsRepo.textColorFlow.first()).isEqualTo(-65536)
        assertThat(settingsRepo.isFontBoldStateFlow.first()).isTrue()
    }
}