package com.github.reygnn.kolibri_launcher.data

import io.mockk.every
import io.mockk.mockk

import android.content.Context
import android.graphics.Color
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.BackupData
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSwipeActionsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeWallpaperRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit-Test für den BackupRepositoryImpl.
 *
 * Testet die Kern-Logik (exportToJson, importFromJson).
 * File I/O Tests (saveBackupToFile, loadBackupFromFile, previewBackup)
 * sollten als Instrumented Tests mit echtem Android-Framework getestet werden.
 */

@ExperimentalCoroutinesApi
class BackupRepositoryImplDoomsdayTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    @get:Rule
    val timberRule = TimberRule()

    // Mocks und Fakes
    private lateinit var mockContext: Context
    private lateinit var fakeFavoritesRepo: FakeFavoritesRepository
    private lateinit var fakeFavoritesOrderRepo: FakeFavoritesOrderRepository
    private lateinit var fakeVisibilityRepo: FakeHiddenAppsRepository
    private lateinit var fakeNamesRepo: FakeCustomNamesRepository
    private lateinit var fakeInstalledAppsRepo: FakeInstalledAppsRepository
    private lateinit var fakeSwipeActionsRepo: FakeSwipeActionsRepository
    private lateinit var fakeSettingsRepo: FakeSettingsRepository
    private lateinit var fakeWallpaperRepo: FakeWallpaperRepository
    private val mockWallpaperFileManager: WallpaperFileManager = mockk(relaxed = true)

    private lateinit var backupManager: BackupRepositoryImpl

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        mockContext = mockk<Context>(relaxed = true)
        fakeInstalledAppsRepo = FakeInstalledAppsRepository()
        fakeFavoritesRepo = FakeFavoritesRepository()
        fakeFavoritesOrderRepo = FakeFavoritesOrderRepository()
        fakeVisibilityRepo = FakeHiddenAppsRepository()
        fakeNamesRepo = FakeCustomNamesRepository()
        fakeSwipeActionsRepo = FakeSwipeActionsRepository()
        fakeSettingsRepo = FakeSettingsRepository()
        fakeWallpaperRepo = FakeWallpaperRepository()

        backupManager = BackupRepositoryImpl(
            fakeFavoritesRepo,
            fakeFavoritesOrderRepo,
            fakeVisibilityRepo,
            fakeNamesRepo,
            fakeInstalledAppsRepo,
            fakeSwipeActionsRepo,
            fakeSettingsRepo,
            fakeWallpaperRepo,
            mockWallpaperFileManager,
            mockContext
        )
    }

    // ========== EXPORT TESTS ==========

    @Test
    fun `exportToJson - with empty data - creates valid backup`() = runTest {
        val jsonString = backupManager.exportToJson()
        // Wir nutzen hier den Kotlinx Parser zur Kontrolle, ob das produzierte JSON valide ist
        val backup = json.decodeFromString<BackupData>(jsonString)

        Truth.assertThat(backup.version).isEqualTo("1.0.0")
        Truth.assertThat(backup.settings.favoriteComponents).isEmpty()
    }

    @Test
    fun `importFromJson - only gesture settings - imports only gestures`() = runTest {
        fakeInstalledAppsRepo.installedApps = emptyList()
        fakeSettingsRepo.doubleTap = false
        fakeSettingsRepo.swipeDown = false
        fakeSettingsRepo.color = Color.BLACK

        val backup = createTestBackup(
            favorites = setOf("com.app1/com.app1.MainActivity"),
            textColor = Color.RED,
            doubleTapToLockEnabled = true,
            swipeDownToNotificationsEnabled = true
        )
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(
            importFavorites = false,
            importOrder = false,
            importHiddenApps = false,
            importCustomNames = false,
            importSwipeActions = false,
            importThemeSettings = false,
            importGestureSettings = true
        )

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeSettingsRepo.doubleTap).isTrue()
        Truth.assertThat(fakeSettingsRepo.swipeDown).isTrue()
        Truth.assertThat(fakeSettingsRepo.color).isEqualTo(Color.BLACK)
        Truth.assertThat(fakeFavoritesRepo.favorites).isEmpty()
    }

    @Test
    fun `importFromJson - only theme settings - imports only theme not gestures`() = runTest {
        fakeInstalledAppsRepo.installedApps = emptyList()
        fakeSettingsRepo.color = Color.BLACK
        fakeSettingsRepo.chipBgColor = Color.BLACK
        fakeSettingsRepo.shadow = true
        fakeSettingsRepo.doubleTap = false
        fakeSettingsRepo.swipeDown = false

        val backup = createTestBackup(
            textColor = Color.GREEN,
            chipBackgroundColor = Color.MAGENTA,
            textShadowEnabled = false,
            doubleTapToLockEnabled = true,
            swipeDownToNotificationsEnabled = true
        )
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(
            importFavorites = false,
            importOrder = false,
            importHiddenApps = false,
            importCustomNames = false,
            importSwipeActions = false,
            importThemeSettings = true,
            importGestureSettings = false
        )

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeSettingsRepo.color).isEqualTo(Color.GREEN)
        Truth.assertThat(fakeSettingsRepo.chipBgColor).isEqualTo(Color.MAGENTA)
        Truth.assertThat(fakeSettingsRepo.shadow).isFalse()
        Truth.assertThat(fakeSettingsRepo.doubleTap).isFalse()
        Truth.assertThat(fakeSettingsRepo.swipeDown).isFalse()
    }

    @Test
    fun `exportToJson - with favorites - includes favorites in backup`() = runTest {
        fakeFavoritesRepo.favorites = setOf("com.app1/com.app1.MainActivity")
        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)
        Truth.assertThat(backup.settings.favoriteComponents).containsExactly("com.app1/com.app1.MainActivity")
    }

    @Test
    fun `exportToJson - with order - includes order in backup`() = runTest {
        fakeFavoritesOrderRepo.order = listOf(
            "com.app2/com.app2.MainActivity",
            "com.app1/com.app1.MainActivity"
        )

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        Truth.assertThat(backup.settings.favoritesOrder).containsExactly(
            "com.app2/com.app2.MainActivity",
            "com.app1/com.app1.MainActivity"
        ).inOrder()
    }

    @Test
    fun `exportToJson - with hidden apps - includes hidden in backup`() = runTest {
        fakeVisibilityRepo.hiddenApps = setOf(
            "com.app3/com.app3.MainActivity",
            "com.app4/com.app4.MainActivity"
        )

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        Truth.assertThat(backup.settings.hiddenComponents).hasSize(2)
        Truth.assertThat(backup.settings.hiddenComponents).containsExactly(
            "com.app3/com.app3.MainActivity",
            "com.app4/com.app4.MainActivity"
        )
    }

    @Test
    fun `exportToJson - with custom names - includes names in backup`() = runTest {
        fakeNamesRepo.setCustomNamesInBatch(
            mapOf(
                "com.app1" to "Custom App 1",
                "com.app2" to "Custom App 2"
            )
        )

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        Truth.assertThat(backup.settings.customAppNames).hasSize(2)
        Truth.assertThat(backup.settings.customAppNames).containsEntry("com.app1", "Custom App 1")
        Truth.assertThat(backup.settings.customAppNames).containsEntry("com.app2", "Custom App 2")
    }

    @Test
    fun `exportToJson - with swipe left only - includes left swipe in backup`() = runTest {
        fakeSwipeActionsRepo.swipeLeftApp = "com.app1/com.app1.MainActivity"
        fakeSwipeActionsRepo.swipeRightApp = null

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        Truth.assertThat(backup.settings.swipeLeftApp).isEqualTo("com.app1/com.app1.MainActivity")
        Truth.assertThat(backup.settings.swipeRightApp).isNull()
    }

    @Test
    fun `exportToJson - with swipe right only - includes right swipe in backup`() = runTest {
        fakeSwipeActionsRepo.swipeLeftApp = null
        fakeSwipeActionsRepo.swipeRightApp = "com.app2/com.app2.MainActivity"

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        Truth.assertThat(backup.settings.swipeLeftApp).isNull()
        Truth.assertThat(backup.settings.swipeRightApp).isEqualTo("com.app2/com.app2.MainActivity")
    }

    @Test
    fun `exportToJson - with both swipe actions - includes both in backup`() = runTest {
        fakeSwipeActionsRepo.swipeLeftApp = "com.app1/com.app1.MainActivity"
        fakeSwipeActionsRepo.swipeRightApp = "com.app2/com.app2.MainActivity"

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        Truth.assertThat(backup.settings.swipeLeftApp).isEqualTo("com.app1/com.app1.MainActivity")
        Truth.assertThat(backup.settings.swipeRightApp).isEqualTo("com.app2/com.app2.MainActivity")
    }

    @Test
    fun `exportToJson - with all data - creates complete backup`() = runTest {
        fakeFavoritesRepo.favorites = setOf("com.app1/com.app1.MainActivity")
        fakeFavoritesOrderRepo.order = listOf("com.app1/com.app1.MainActivity")
        fakeVisibilityRepo.hiddenApps = setOf("com.app2/com.app2.MainActivity")
        fakeNamesRepo.setCustomNamesInBatch(mapOf("com.app1" to "My App"))
        fakeSwipeActionsRepo.swipeLeftApp = "com.app3/com.app3.MainActivity"
        fakeSwipeActionsRepo.swipeRightApp = "com.app4/com.app4.MainActivity"
        fakeSettingsRepo.color = Color.RED
        fakeSettingsRepo.chipBgColor = Color.GREEN
        fakeSettingsRepo.shadow = false
        fakeSettingsRepo.doubleTap = true
        fakeSettingsRepo.swipeDown = true
        fakeSettingsRepo.autoShowKeyboard = true
        fakeSettingsRepo.autoLaunchApp = true
        fakeSettingsRepo.splitModeThreshold = 60

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        Truth.assertThat(backup.settings.favoriteComponents).hasSize(1)
        Truth.assertThat(backup.settings.favoritesOrder).hasSize(1)
        Truth.assertThat(backup.settings.hiddenComponents).hasSize(1)
        Truth.assertThat(backup.settings.customAppNames).hasSize(1)
        Truth.assertThat(backup.settings.swipeLeftApp).isNotNull()
        Truth.assertThat(backup.settings.swipeRightApp).isNotNull()
        Truth.assertThat(backup.settings.textColor).isEqualTo(Color.RED)
        Truth.assertThat(backup.settings.chipBackgroundColor).isEqualTo(Color.GREEN)
        Truth.assertThat(backup.settings.textShadowEnabled).isFalse()
        Truth.assertThat(backup.settings.doubleTapToLockEnabled).isTrue()
        Truth.assertThat(backup.settings.swipeDownToNotificationsEnabled).isTrue()
        Truth.assertThat(backup.settings.autoShowKeyboard).isTrue()
        Truth.assertThat(backup.settings.autoLaunchApp).isTrue()
        Truth.assertThat(backup.settings.splitModeThreshold).isEqualTo(60)
    }

    @Test
    fun `exportToJson - sets timestamp explicitly`() = runTest {
        val beforeExport = System.currentTimeMillis()

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        val afterExport = System.currentTimeMillis()

        Truth.assertThat(backup.timestamp).isAtLeast(beforeExport)
        Truth.assertThat(backup.timestamp).isAtMost(afterExport)
        Truth.assertThat(backup.timestamp).isGreaterThan(0L)
    }

    @Test
    fun `exportToJson - with splitModeThreshold enabled - includes in backup`() = runTest {
        fakeSettingsRepo.splitModeThreshold = 42

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        Truth.assertThat(backup.settings.splitModeThreshold).isEqualTo(42)
    }

    @Test
    fun `exportToJson - with splitModeThreshold at default zero - returns null`() = runTest {
        fakeSettingsRepo.splitModeThreshold = 0

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        Truth.assertThat(backup.settings.splitModeThreshold).isEqualTo(0)
    }

    @Test
    fun `exportToJson - with splitModeThreshold at 100px - includes in backup`() = runTest {
        fakeSettingsRepo.splitModeThreshold = 100

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        Truth.assertThat(backup.settings.splitModeThreshold).isEqualTo(100)
    }

    // ========== IMPORT TESTS - SELECTIVE ==========

    @Test
    fun `importFromJson - with importNothing option - returns error`() = runTest {
        fakeInstalledAppsRepo.installedApps = emptyList()

        val backup = createTestBackup()
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(
            importFavorites = false,
            importOrder = false,
            importHiddenApps = false,
            importCustomNames = false,
            importSwipeActions = false,
            importThemeSettings = false,
            importTimeBasedEvents = false,
            importGestureSettings = false,
            importQualityOfLife = false,
            importPowerUserSettings = false
        )

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Error::class.java)
    }

    @Test
    fun `importFromJson - only favorites - imports only favorites`() = runTest {
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.app1", "com.app1.MainActivity"),
            createAppInfo("com.app2", "com.app2.MainActivity")
        )
        fakeSettingsRepo.color = Color.BLACK

        val backup = createTestBackup(
            favorites = setOf("com.app1/com.app1.MainActivity"),
            order = listOf("com.app1/com.app1.MainActivity"),
            hidden = setOf("com.app2/com.app2.MainActivity"),
            names = mapOf("com.app1" to "Name"),
            swipeLeft = "com.app1/com.app1.MainActivity",
            swipeRight = "com.app2/com.app2.MainActivity",
            textColor = Color.RED
        )
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(
            importFavorites = true,
            importOrder = false,
            importHiddenApps = false,
            importCustomNames = false,
            importSwipeActions = false,
            importThemeSettings = false
        )

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeFavoritesRepo.favorites).hasSize(1)
        Truth.assertThat(fakeFavoritesOrderRepo.order).isEmpty()
        Truth.assertThat(fakeVisibilityRepo.hiddenApps).isEmpty()
        Truth.assertThat(fakeNamesRepo.getAllCustomNames()).isEmpty()
        Truth.assertThat(fakeSwipeActionsRepo.swipeLeftApp).isNull()
        Truth.assertThat(fakeSwipeActionsRepo.swipeRightApp).isNull()
        Truth.assertThat(fakeSettingsRepo.color).isEqualTo(Color.BLACK)
    }

    @Test
    fun `importFromJson - only order - imports only order`() = runTest {
        fakeFavoritesRepo.favorites = setOf("com.app1/com.app1.MainActivity")
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.app1", "com.app1.MainActivity")
        )

        val backup = createTestBackup(
            favorites = setOf("com.app1/com.app1.MainActivity"),
            order = listOf("com.app1/com.app1.MainActivity")
        )
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(
            importFavorites = false,
            importOrder = true,
            importHiddenApps = false,
            importCustomNames = false,
            importSwipeActions = false,
            importThemeSettings = false
        )

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeFavoritesOrderRepo.order).hasSize(1)
    }

    @Test
    fun `importFromJson - only hidden apps - imports only hidden`() = runTest {
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.app1", "com.app1.MainActivity")
        )

        val backup = createTestBackup(
            hidden = setOf("com.app1/com.app1.MainActivity")
        )
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(
            importFavorites = false,
            importOrder = false,
            importHiddenApps = true,
            importCustomNames = false,
            importSwipeActions = false,
            importThemeSettings = false
        )

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeVisibilityRepo.hiddenApps).hasSize(1)
    }

    @Test
    fun `importFromJson - only custom names - imports only names`() = runTest {
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.app1", "com.app1.MainActivity")
        )

        val backup = createTestBackup(
            names = mapOf("com.app1" to "Custom")
        )
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(
            importFavorites = false,
            importOrder = false,
            importHiddenApps = false,
            importCustomNames = true,
            importSwipeActions = false,
            importThemeSettings = false
        )

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeNamesRepo.getAllCustomNames()).hasSize(1)
        Truth.assertThat(fakeNamesRepo.batchSetCalled).isTrue()
    }

    @Test
    fun `importFromJson - only swipe actions - imports only swipes`() = runTest {
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.app1", "com.app1.MainActivity"),
            createAppInfo("com.app2", "com.app2.MainActivity")
        )

        val backup = createTestBackup(
            favorites = setOf("com.app1/com.app1.MainActivity"),
            swipeLeft = "com.app1/com.app1.MainActivity",
            swipeRight = "com.app2/com.app2.MainActivity"
        )
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(
            importFavorites = false,
            importOrder = false,
            importHiddenApps = false,
            importCustomNames = false,
            importSwipeActions = true,
            importThemeSettings = false
        )

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeFavoritesRepo.favorites).isEmpty()
        Truth.assertThat(fakeSwipeActionsRepo.swipeLeftApp)
            .isEqualTo("com.app1/com.app1.MainActivity")
        Truth.assertThat(fakeSwipeActionsRepo.swipeRightApp)
            .isEqualTo("com.app2/com.app2.MainActivity")
    }

    @Test
    fun `importFromJson - only theme settings - imports only theme`() = runTest {
        fakeInstalledAppsRepo.installedApps = emptyList()
        fakeSettingsRepo.color = Color.BLACK
        fakeSettingsRepo.chipBgColor = Color.BLACK
        fakeSettingsRepo.shadow = true

        val backup = createTestBackup(
            favorites = setOf("com.app1/com.app1.MainActivity"),
            textColor = Color.GREEN,
            chipBackgroundColor = Color.MAGENTA,
            textShadowEnabled = false
        )
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(
            importFavorites = false,
            importOrder = false,
            importHiddenApps = false,
            importCustomNames = false,
            importSwipeActions = false,
            importThemeSettings = true
        )

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        Truth.assertThat(fakeSettingsRepo.color).isEqualTo(Color.GREEN)
        Truth.assertThat(fakeSettingsRepo.chipBgColor).isEqualTo(Color.MAGENTA)
        Truth.assertThat(fakeSettingsRepo.shadow).isFalse()

        Truth.assertThat(fakeFavoritesRepo.favorites).isEmpty()
    }

    @Test
    fun `importFromJson - only power user settings - imports only threshold`() = runTest {
        fakeInstalledAppsRepo.installedApps = emptyList()
        fakeSettingsRepo.splitModeThreshold = 0
        fakeSettingsRepo.color = Color.BLACK

        val backup = createTestBackup(
            favorites = setOf("com.app1/com.app1.MainActivity"),
            textColor = Color.RED,
            splitModeThreshold = 42
        )
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(
            importFavorites = false,
            importOrder = false,
            importHiddenApps = false,
            importCustomNames = false,
            importSwipeActions = false,
            importThemeSettings = false,
            importGestureSettings = false,
            importTimeBasedEvents = false,
            importQualityOfLife = false,
            importPowerUserSettings = true
        )

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeSettingsRepo.splitModeThreshold).isEqualTo(42)
        Truth.assertThat(fakeSettingsRepo.color).isEqualTo(Color.BLACK)
        Truth.assertThat(fakeFavoritesRepo.favorites).isEmpty()
    }

    @Test
    fun `importFromJson - power user disabled - does not import threshold`() = runTest {
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.app1", "com.app1.MainActivity")
        )
        fakeSettingsRepo.splitModeThreshold = 0
        fakeSettingsRepo.color = Color.BLACK

        val backup = createTestBackup(
            favorites = setOf("com.app1/com.app1.MainActivity"),
            textColor = Color.RED,
            splitModeThreshold = 100
        )
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(
            importFavorites = true,
            importThemeSettings = true,
            importPowerUserSettings = false
        )

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // Assert 1: Threshold sollte NICHT importiert worden sein (bleibt 0)
        Truth.assertThat(fakeSettingsRepo.splitModeThreshold).isEqualTo(0)

        // Assert 2: Farbe sollte importiert worden sein (wird RED)
        Truth.assertThat(fakeSettingsRepo.color).isEqualTo(Color.RED)

        // Assert 3: Favorit sollte importiert worden sein (da Option true und App installiert)
        Truth.assertThat(fakeFavoritesRepo.favorites).hasSize(1)
    }

    @Test
    fun `importFromJson - threshold validates to 0-512 range`() = runTest {
        fakeInstalledAppsRepo.installedApps = emptyList()

        val backup = createTestBackup(splitModeThreshold = 999)
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(importPowerUserSettings = true)

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeSettingsRepo.splitModeThreshold).isEqualTo(512)
    }

    @Test
    fun `importFromJson - threshold validates negative to zero`() = runTest {
        fakeInstalledAppsRepo.installedApps = emptyList()

        val backup = createTestBackup(splitModeThreshold = -50)
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(importPowerUserSettings = true)

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeSettingsRepo.splitModeThreshold).isEqualTo(0)
    }

    @Test
    fun `importFromJson - swipe actions - filters non-installed left app`() = runTest {
        // 1. Mock: App2 ist installiert
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.app2", "com.app2.MainActivity")
        )

        // 2. JSON: Enthält linke (falsch) und rechte (richtig) App
        // Wir nutzen hier Helper, da BackupData korrektes JSON generiert
        val backup = createTestBackup(
            swipeLeft = "com.nonexistent/com.nonexistent.MainActivity",
            swipeRight = "com.app2/com.app2.MainActivity"
        )
        val jsonString = json.encodeToString(backup)

        val result = backupManager.importFromJson(jsonString, ImportOptions(importSwipeActions = true))

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val success = result as ImportResult.Success

        // 3. Assert: Links null, Rechts gesetzt
        Truth.assertThat(fakeSwipeActionsRepo.swipeLeftApp).isNull()
        Truth.assertThat(fakeSwipeActionsRepo.swipeRightApp).isEqualTo("com.app2/com.app2.MainActivity")

        // 4. Assert: Fehlende App wird gemeldet
        Truth.assertThat(success.missingApps).contains("com.nonexistent/com.nonexistent.MainActivity")
    }

    @Test
    fun `importFromJson - swipe actions - filters non-installed right app`() = runTest {
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.app1", "com.app1.MainActivity")
        )

        val backup = createTestBackup(
            swipeLeft = "com.app1/com.app1.MainActivity",
            swipeRight = "com.nonexistent/com.nonexistent.MainActivity"
        )
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(importSwipeActions = true)

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val success = result as ImportResult.Success
        Truth.assertThat(fakeSwipeActionsRepo.swipeLeftApp)
            .isEqualTo("com.app1/com.app1.MainActivity")
        Truth.assertThat(fakeSwipeActionsRepo.swipeRightApp).isNull()
        Truth.assertThat(success.missingApps)
            .contains("com.nonexistent/com.nonexistent.MainActivity")
    }

    @Test
    fun `importFromJson - swipe actions - filters both non-installed apps`() = runTest {
        fakeInstalledAppsRepo.installedApps = emptyList()

        val backup = createTestBackup(
            swipeLeft = "com.app1/com.app1.MainActivity",
            swipeRight = "com.app2/com.app2.MainActivity"
        )
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(importSwipeActions = true)

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val success = result as ImportResult.Success
        Truth.assertThat(fakeSwipeActionsRepo.swipeLeftApp).isNull()
        Truth.assertThat(fakeSwipeActionsRepo.swipeRightApp).isNull()
        Truth.assertThat(success.missingApps).hasSize(2)
    }

    @Test
    fun `importFromJson - all options - imports everything`() = runTest {
        // Setup valid state
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.app1", "com.app1.MainActivity")
        )
        // Erstelle valides Backup via Helper
        val backup = createTestBackup(
            favorites = setOf("com.app1/com.app1.MainActivity"),
            textColor = Color.BLUE,
            splitModeThreshold = 42
        )
        val jsonString = json.encodeToString(backup)

        val result = backupManager.importFromJson(jsonString, ImportOptions(
            importFavorites = true,
            importThemeSettings = true,
            importPowerUserSettings = true
        ))

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeFavoritesRepo.favorites).hasSize(1)
        Truth.assertThat(fakeSettingsRepo.color).isEqualTo(Color.BLUE)
        Truth.assertThat(fakeSettingsRepo.splitModeThreshold).isEqualTo(42)
    }

    // ========== VALIDATION TESTS ==========

    @Test
    fun `importFromJson - unsupported version - returns UnsupportedVersion`() = runTest {
        val backup = createTestBackup(version = "2.0.0")
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions()
        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.UnsupportedVersion::class.java)
        Truth.assertThat((result as ImportResult.UnsupportedVersion).version).isEqualTo("2.0.0")
    }

    @Test
    fun `importFromJson - invalid JSON - returns InvalidFormat`() = runTest {
        val invalidJson = "{ invalid json }"

        val options = ImportOptions()
        val result = backupManager.importFromJson(invalidJson, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.InvalidFormat::class.java)
    }

    @Test
    fun `importFromJson - exceeds package limit - returns LimitExceeded`() = runTest {
        // 1. Wir definieren das Ziel basierend auf der Konstante (+1 um das Limit zu sprengen)
        val limit = AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME
        val exceededCount = limit + 1

        // 2. Wir generieren dynamisch genau so viele Apps, wie nötig sind
        val appInfos = (1..exceededCount).map {
            createAppInfo("com.app$it", "com.app$it.MainActivity")
        }
        fakeInstalledAppsRepo.installedApps = appInfos

        val appNames = appInfos.map { it.componentName }.toSet()
        val backup = createTestBackup(favorites = appNames)
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(importFavorites = true)

        // Action
        val result = backupManager.importFromJson(jsonString, options)

        // Assertions
        Truth.assertThat(result).isInstanceOf(ImportResult.LimitExceeded::class.java)
        val limitExceeded = result as ImportResult.LimitExceeded

        // Prüfen, ob die gemeldete Anzahl mit unserer generierten Anzahl übereinstimmt
        Truth.assertThat(limitExceeded.packageCount).isEqualTo(exceededCount)
        // Prüfen, ob das Limit korrekt aus der Konstante gemeldet wird
        Truth.assertThat(limitExceeded.limit).isEqualTo(limit)
    }

    @Test
    fun `importFromJson - filters non-installed apps`() = runTest {
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.app1", "com.app1.MainActivity")
        )

        val backup = createTestBackup(
            favorites = setOf(
                "com.app1/com.app1.MainActivity",
                "com.nonexistent/com.nonexistent.MainActivity"
            )
        )
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(importFavorites = true)

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val success = result as ImportResult.Success
        Truth.assertThat(success.importedCount).isEqualTo(1)
        Truth.assertThat(success.skippedCount).isEqualTo(1)
        Truth.assertThat(success.missingApps)
            .contains("com.nonexistent/com.nonexistent.MainActivity")
        Truth.assertThat(fakeFavoritesRepo.favorites).hasSize(1)
        Truth.assertThat(fakeFavoritesRepo.favorites).contains("com.app1/com.app1.MainActivity")
    }

    @Test
    fun `importFromJson - filters order by current favorites`() = runTest {
        fakeFavoritesRepo.favorites = setOf("com.app1/com.app1.MainActivity")
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.app1", "com.app1.MainActivity"),
            createAppInfo("com.app2", "com.app2.MainActivity")
        )

        val backup = createTestBackup(
            order = listOf(
                "com.app1/com.app1.MainActivity",
                "com.app2/com.app2.MainActivity"
            )
        )
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(
            importFavorites = false,
            importOrder = true,
            importHiddenApps = false,
            importCustomNames = false,
            importSwipeActions = false
        )

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeFavoritesOrderRepo.order)
            .containsExactly("com.app1/com.app1.MainActivity")
    }

    @Test
    fun `importFromJson - filters hidden apps by installed`() = runTest {
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.app1", "com.app1.MainActivity")
        )

        val backup = createTestBackup(
            hidden = setOf(
                "com.app1/com.app1.MainActivity",
                "com.nonexistent/com.nonexistent.MainActivity"
            )
        )
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(importHiddenApps = true)

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeVisibilityRepo.hiddenApps).hasSize(1)
        Truth.assertThat(fakeVisibilityRepo.hiddenApps).contains("com.app1/com.app1.MainActivity")
    }

    @Test
    fun `importFromJson - filters custom names by installed packages`() = runTest {
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.app1", "com.app1.MainActivity")
        )

        val backup = createTestBackup(
            names = mapOf(
                "com.app1" to "Name 1",
                "com.nonexistent" to "Name 2"
            )
        )
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(importCustomNames = true)

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val importedNames = fakeNamesRepo.getAllCustomNames()
        Truth.assertThat(importedNames).hasSize(1)
        Truth.assertThat(importedNames).containsKey("com.app1")
    }

    // ========== EDGE CASES ==========

    @Test
    fun `importFromJson - empty backup - succeeds`() = runTest {
        fakeInstalledAppsRepo.installedApps = emptyList()
        val backup = createTestBackup()
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions()
        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val success = result as ImportResult.Success
        Truth.assertThat(success.importedCount).isEqualTo(0)
        Truth.assertThat(success.skippedCount).isEqualTo(0)
    }

    @Test
    fun `importFromJson - custom names with empty map - succeeds without trigger`() = runTest {
        fakeInstalledAppsRepo.installedApps = emptyList()
        val backup = createTestBackup(names = emptyMap())
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(importCustomNames = true)

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeNamesRepo.batchSetCalled).isFalse()
    }

    @Test
    fun `importFromJson - multiple components from same package - within limit`() = runTest {
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.app1", "com.app1.MainActivity"),
            createAppInfo("com.app1", "com.app1.SecondActivity"),
            createAppInfo("com.app1", "com.app1.ThirdActivity")
        )

        val backup = createTestBackup(
            favorites = setOf(
                "com.app1/com.app1.MainActivity",
                "com.app1/com.app1.SecondActivity",
                "com.app1/com.app1.ThirdActivity"
            )
        )
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(importFavorites = true)

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeFavoritesRepo.favorites).hasSize(3)
    }

    @Test
    fun `importFromJson - performance test with large dataset`() = runTest {
        val installedApps = (1..100).map { createAppInfo("com.app$it", "com.app$it.MainActivity") }
        fakeInstalledAppsRepo.installedApps = installedApps

        val favorites = (1..8).map { "com.app$it/com.app$it.MainActivity" }.toSet()
        val hidden = (20..69).map { "com.app$it/com.app$it.MainActivity" }.toSet()
        val names = (1..60).associate { "com.app$it" to "Custom $it" }

        val backup = createTestBackup(
            favorites = favorites,
            hidden = hidden,
            names = names,
            swipeLeft = "com.app1/com.app1.MainActivity",
            swipeRight = "com.app2/com.app2.MainActivity",
            textColor = Color.YELLOW,
            chipBackgroundColor = Color.RED,
            textShadowEnabled = false,
            doubleTapToLockEnabled = true,
            swipeDownToNotificationsEnabled = true,
            autoShowKeyboard = true,
            autoLaunchApp = true,
            splitModeThreshold = 42
        )
        val jsonString = json.encodeToString(backup)

        val startTime = System.currentTimeMillis()
        val result = backupManager.importFromJson(jsonString, ImportOptions())
        val duration = System.currentTimeMillis() - startTime

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(duration).isLessThan(1000)

        Truth.assertThat(fakeFavoritesRepo.favorites).hasSize(8)
        Truth.assertThat(fakeVisibilityRepo.hiddenApps).hasSize(50)
        Truth.assertThat(fakeNamesRepo.getAllCustomNames()).hasSize(60)
        Truth.assertThat(fakeSwipeActionsRepo.swipeLeftApp).isNotNull()
        Truth.assertThat(fakeSwipeActionsRepo.swipeRightApp).isNotNull()
        Truth.assertThat(fakeSettingsRepo.color).isEqualTo(Color.YELLOW)
        Truth.assertThat(fakeSettingsRepo.chipBgColor).isEqualTo(Color.RED)
        Truth.assertThat(fakeSettingsRepo.shadow).isFalse()
        Truth.assertThat(fakeSettingsRepo.doubleTap).isTrue()
        Truth.assertThat(fakeSettingsRepo.swipeDown).isTrue()
        Truth.assertThat(fakeSettingsRepo.autoShowKeyboard).isTrue()
        Truth.assertThat(fakeSettingsRepo.autoLaunchApp).isTrue()
        Truth.assertThat(fakeSettingsRepo.splitModeThreshold).isEqualTo(42)
    }

    @Test
    fun `importFromJson - old backup without theme keys - does not change theme`() = runTest {
        fakeSettingsRepo.color = Color.CYAN
        fakeSettingsRepo.chipBgColor = Color.BLUE
        fakeSettingsRepo.shadow = false

        val backup = createTestBackup(
            favorites = setOf("com.app1/com.app1.MainActivity"),
            textColor = null,
            chipBackgroundColor = null,
            textShadowEnabled = null
        )
        val oldBackupJson = json.encodeToString(backup)

        val options = ImportOptions(importThemeSettings = true)

        val result = backupManager.importFromJson(oldBackupJson, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeSettingsRepo.color).isEqualTo(Color.CYAN)
        Truth.assertThat(fakeSettingsRepo.chipBgColor).isEqualTo(Color.BLUE)
        Truth.assertThat(fakeSettingsRepo.shadow).isFalse()
    }

    @Test
    fun `importFromJson - old backup without gesture keys - does not change gestures`() = runTest {
        fakeSettingsRepo.doubleTap = true
        fakeSettingsRepo.swipeDown = false

        val backup = createTestBackup(
            favorites = setOf("com.app1/com.app1.MainActivity"),
            doubleTapToLockEnabled = null,
            swipeDownToNotificationsEnabled = null
        )
        val oldBackupJson = json.encodeToString(backup)

        val options = ImportOptions(importGestureSettings = true)

        val result = backupManager.importFromJson(oldBackupJson, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeSettingsRepo.doubleTap).isTrue()
        Truth.assertThat(fakeSettingsRepo.swipeDown).isFalse()
    }

    @Test
    fun `importFromJson - old backup without threshold key - does not change threshold`() =
        runTest {
            fakeSettingsRepo.splitModeThreshold = 100

            val backup = createTestBackup(
                favorites = setOf("com.app1/com.app1.MainActivity"),
                splitModeThreshold = null
            )
            val oldBackupJson = json.encodeToString(backup)

            val options = ImportOptions(importPowerUserSettings = true)

            val result = backupManager.importFromJson(oldBackupJson, options)

            Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
            Truth.assertThat(fakeSettingsRepo.splitModeThreshold).isEqualTo(100)
        }

    @Test
    fun `importFromJson - threshold zero in backup - imports as zero`() = runTest {
        fakeSettingsRepo.splitModeThreshold = 42

        val backup = createTestBackup(splitModeThreshold = 0)
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(importPowerUserSettings = true)

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeSettingsRepo.splitModeThreshold).isEqualTo(0)
    }

    @Test
    fun `importFromJson - threshold at preset values - imports correctly`() = runTest {
        fakeInstalledAppsRepo.installedApps = emptyList()

        val presetValues = listOf(0, 42, 60, 100, 512)

        for (presetValue in presetValues) {
            val backup = createTestBackup(splitModeThreshold = presetValue)
            val jsonString = json.encodeToString(backup)
            val options = ImportOptions(importPowerUserSettings = true)

            val result = backupManager.importFromJson(jsonString, options)

            Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
            Truth.assertThat(fakeSettingsRepo.splitModeThreshold).isEqualTo(presetValue)
        }
    }

    // ========== EXPORT TESTS - AUTO SHOW KEYBOARD ==========

    @Test
    fun `exportToJson - with autoShowKeyboard enabled - includes in backup`() = runTest {
        fakeSettingsRepo.autoShowKeyboard = true

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        Truth.assertThat(backup.settings.autoShowKeyboard).isTrue()
    }

    @Test
    fun `exportToJson - with autoShowKeyboard disabled default - returns null`() = runTest {
        fakeSettingsRepo.autoShowKeyboard = false

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        Truth.assertThat(backup.settings.autoShowKeyboard).isFalse()
    }

    // ========== IMPORT TESTS - QUALITY OF LIFE SETTINGS ==========

    @Test
    fun `importFromJson - only quality of life settings - imports only QoL`() = runTest {
        fakeInstalledAppsRepo.installedApps = emptyList()
        fakeSettingsRepo.autoShowKeyboard = false
        fakeSettingsRepo.color = Color.BLACK

        val backup = createTestBackup(
            favorites = setOf("com.app1/com.app1.MainActivity"),
            textColor = Color.RED,
            autoShowKeyboard = true,
            autoLaunchApp = true
        )
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(
            importFavorites = false,
            importOrder = false,
            importHiddenApps = false,
            importCustomNames = false,
            importSwipeActions = false,
            importThemeSettings = false,
            importGestureSettings = false,
            importTimeBasedEvents = false,
            importQualityOfLife = true
        )

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeSettingsRepo.autoShowKeyboard).isTrue()
        Truth.assertThat(fakeSettingsRepo.autoLaunchApp).isTrue()
        Truth.assertThat(fakeSettingsRepo.color).isEqualTo(Color.BLACK)
        Truth.assertThat(fakeFavoritesRepo.favorites).isEmpty()
    }

    @Test
    fun `importFromJson - quality of life disabled - does not import autoShowKeyboard`() = runTest {
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.app1", "com.app1.MainActivity")
        )
        fakeSettingsRepo.autoShowKeyboard = false
        fakeSettingsRepo.autoLaunchApp = false
        fakeSettingsRepo.color = Color.BLACK

        val backup = createTestBackup(
            favorites = setOf("com.app1/com.app1.MainActivity"),
            textColor = Color.RED,
            autoShowKeyboard = true,
            autoLaunchApp = true
        )
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(
            importFavorites = true,
            importOrder = false,
            importHiddenApps = false,
            importCustomNames = false,
            importSwipeActions = false,
            importThemeSettings = false,
            importGestureSettings = false,
            importTimeBasedEvents = false,
            importQualityOfLife = false
        )

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeSettingsRepo.autoShowKeyboard).isFalse()
        Truth.assertThat(fakeSettingsRepo.autoLaunchApp).isFalse()
        Truth.assertThat(fakeFavoritesRepo.favorites).hasSize(1)
        Truth.assertThat(fakeSettingsRepo.color).isEqualTo(Color.BLACK)
    }

    @Test
    fun `importFromJson - old backup without QoL keys - does not change QoL settings`() = runTest {
        fakeSettingsRepo.autoShowKeyboard = true
        fakeSettingsRepo.autoLaunchApp = true

        val backup = createTestBackup(
            favorites = setOf("com.app1/com.app1.MainActivity"),
            autoShowKeyboard = null,
            autoLaunchApp = null
        )
        val oldBackupJson = json.encodeToString(backup)

        val options = ImportOptions(importQualityOfLife = true)

        val result = backupManager.importFromJson(oldBackupJson, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeSettingsRepo.autoShowKeyboard).isTrue()
        Truth.assertThat(fakeSettingsRepo.autoLaunchApp).isTrue()
    }

    // ========== THEME SETTINGS CHECKS ==========

    @Test
    fun `importFromJson - extended theme settings - imports layout and font settings`() = runTest {
        // Arrange
        fakeInstalledAppsRepo.installedApps = emptyList()
        // Reset Fake values (Assuming defaults)
        fakeSettingsRepo.layoutScale = 1.0f
        fakeSettingsRepo.verticalPadding = 1.0f
        fakeSettingsRepo.isFontBold = false
        fakeSettingsRepo.contentTopMargin = 1.0f

        val backup = createTestBackup(
            layoutScale = 1.2f,
            verticalPaddingScale = 0.8f,
            isFontBold = true,
            contentTopMarginScale = 0.5f
        )
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(
            importThemeSettings = true
            // all others false by default
        )

        // Act
        val result = backupManager.importFromJson(jsonString, options)

        // Assert
        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        Truth.assertThat(fakeSettingsRepo.layoutScale).isEqualTo(1.2f)
        Truth.assertThat(fakeSettingsRepo.verticalPadding).isEqualTo(0.8f)
        Truth.assertThat(fakeSettingsRepo.isFontBold).isTrue()
        Truth.assertThat(fakeSettingsRepo.contentTopMargin).isEqualTo(0.5f)
    }

    // ========== TIME-BASED EVENTS TEST ==========

    @Test
    fun `importFromJson - only time based events - imports only time settings`() = runTest {
        // Arrange
        fakeInstalledAppsRepo.installedApps = emptyList()
        fakeSettingsRepo.showCalendar = false
        fakeSettingsRepo.showAlarm = false
        fakeSettingsRepo.color = Color.BLACK // Sollte nicht geändert werden

        val backup = createTestBackup(
            showCalendarEvent = true,
            showAlarm = true,
            textColor = Color.RED // Sollte ignoriert werden
        )
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(
            importTimeBasedEvents = true,
            importThemeSettings = false
            // others false
        )

        // Act
        val result = backupManager.importFromJson(jsonString, options)

        // Assert
        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // Time settings imported?
        Truth.assertThat(fakeSettingsRepo.showCalendar).isTrue()
        Truth.assertThat(fakeSettingsRepo.showAlarm).isTrue()

        // Theme settings NOT imported?
        Truth.assertThat(fakeSettingsRepo.color).isEqualTo(Color.BLACK)
    }

    // ========== SPEZIAL-TESTS: THEME RESTORE MIT VERSCHIEDENEN VERSIONEN ==========

    @Test
    fun `importFromJson - theme only - older backup version - imports correctly`() = runTest {
        // Szenario 1: Älteres Backup (v0.5.0) wird in aktuelle App importiert.
        // Erwartung: Theme wird übernommen, fehlende neue Keys werden ignoriert/auf Standard gesetzt.

        fakeSettingsRepo.color = Color.BLACK
        fakeSettingsRepo.doubleTap = false // Gesture Setting (sollte ignoriert werden)

        val backup = createTestBackup(
            appVersion = "0.5.0", // Ältere Version
            textColor = Color.RED,
            chipBackgroundColor = Color.YELLOW,
            // Ein Setting, das NICHT Theme ist, um Isolation zu testen
            doubleTapToLockEnabled = true
        )
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(
            importThemeSettings = true,
            // Alle anderen explizit false
            importFavorites = false,
            importGestureSettings = false
        )

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // Check: Theme wurde übernommen
        Truth.assertThat(fakeSettingsRepo.color).isEqualTo(Color.RED)
        Truth.assertThat(fakeSettingsRepo.chipBgColor).isEqualTo(Color.YELLOW)

        // Check: Anderes Setting wurde NICHT übernommen (Isolation)
        Truth.assertThat(fakeSettingsRepo.doubleTap).isFalse()
    }

    @Test
    fun `importFromJson - theme only - current backup version - imports correctly`() = runTest {
        // Szenario 2: Aktuelles Backup (v1.0.0) mit aktueller Launcher Version.
        // Erwartung: Standard Erfolgsfall.

        fakeSettingsRepo.color = Color.BLACK

        val backup = createTestBackup(
            appVersion = "1.0.0", // Gleiche Version
            textColor = Color.BLUE,
            chipBackgroundColor = Color.MAGENTA
        )
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(importThemeSettings = true)

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        Truth.assertThat(fakeSettingsRepo.color).isEqualTo(Color.BLUE)
        Truth.assertThat(fakeSettingsRepo.chipBgColor).isEqualTo(Color.MAGENTA)
    }

    @Test
    fun `importFromJson - theme only - newer backup version - imports known keys`() = runTest {
        // Szenario 3: Neueres Backup (v2.0.0) in ältere App Version.
        // Erwartung: Solange die Schema-Version (BackupData.version) kompatibel ist,
        // sollte der Import funktionieren. Unbekannte Keys (neue Features der Zukunft)
        // werden durch Json { ignoreUnknownKeys = true } ignoriert.

        fakeSettingsRepo.color = Color.BLACK

        val backup = createTestBackup(
            appVersion = "2.0.0", // Neuere Version (z.B. Beta)
            textColor = Color.GREEN,
            chipBackgroundColor = Color.CYAN
        )
        // Hinweis: Wenn wir hier manuell JSON bauen würden mit einem unbekannten Key "holographicMode = true",
        // würde der Test prüfen, ob der Parser nicht abstürzt. Da wir das Objekt serialisieren,
        // testen wir hier primär, dass die Versionsnummer den Import nicht blockiert.

        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(importThemeSettings = true)

        val result = backupManager.importFromJson(jsonString, options)

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        Truth.assertThat(fakeSettingsRepo.color).isEqualTo(Color.GREEN)
        Truth.assertThat(fakeSettingsRepo.chipBgColor).isEqualTo(Color.CYAN)
    }

    // ========== DOOMSDAY TESTS - ROCKY BALBOA EDITION ==========

    @Test
    fun `doomsday - toxic JSON - wrong data types (String instead of Int)`() = runTest {
        // SZENARIO: "splitModeThreshold" ist ein String, sollte Int sein.
        // Das muss manuell als String gebaut werden, da das Datenmodell das nicht zulässt.
        val toxicJson = """
            {
                "version": "1.0.0",
                "timestamp": 123456,
                "settings": {
                    "split_mode_threshold": "THIS IS TEXT NOT A NUMBER",
                    "text_color": 0
                }
            }
        """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importPowerUserSettings = true))

        // Strict Parser: getInt("splitModeThreshold") wirft JSONException -> InvalidFormat
        Truth.assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `doomsday - toxic values - massive integer overflow`() = runTest {
        // SZENARIO: User will den Launcher crashen und setzt Threshold auf MAX_INT + 1
        // oder einfach eine riesige Zahl.

        val hugeValue = 999999
        val toxicJson = createJsonWithThreshold(hugeValue)

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importPowerUserSettings = true))

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // WICHTIG: Der Wert muss auf das Maximum (512) gecapped sein!
        // Wenn hier 999999 steht, hast du eine Sicherheitslücke.
        Truth.assertThat(fakeSettingsRepo.splitModeThreshold).isEqualTo(512)
    }

    @Test
    fun `doomsday - toxic values - negative layout scale (physics violation)`() = runTest {
        // SZENARIO: User setzt layoutScale auf -5.0.
        // Schriftgröße würde negativ -> Crash beim Rendern.

        val backup = createTestBackup(layoutScale = -5.0f)
        val jsonString = json.encodeToString(backup)

        val result = backupManager.importFromJson(jsonString, ImportOptions(importThemeSettings = true))

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // WICHTIG: Muss auf Minimum (0.5f oder 0.0f je nach Def) gecapped sein.
        // Darf auf keinen Fall negativ sein.
        Truth.assertThat(fakeSettingsRepo.layoutScale).isAtLeast(0.0f)
    }

    @Test
    fun `doomsday - partial corruption - missing required fields`() = runTest {
        // SZENARIO: JSON ist syntaktisch korrekt, aber "settings" Objekt fehlt.
        val brokenJson = """
            {
                "version": "1.0.0",
                "timestamp": 123456
                // "settings" missing completely
            }
        """.trimIndent()

        val result = backupManager.importFromJson(brokenJson, ImportOptions())

        // Kotlin Serialization prüft 'required' fields. Da Settings meist required ist:
        Truth.assertThat(result).isInstanceOf(ImportResult.InvalidFormat::class.java)
    }

    @Test
    fun `terminator - extremely long string in component name`() = runTest {
        // 10MB String als Package Name
        val hugeString = "a".repeat(10_000_000)
        val toxicJson = """
        {
            "version": "1.0.0",
            "timestamp": 123456,
            "settings": {
                "favoriteComponents": ["$hugeString/MainActivity"],
                "favoritesOrder": [],
                "hiddenComponents": []
            }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importFavorites = true))
        // Sollte nicht crashen, egal ob Success oder Error
        Truth.assertThat(result).isNotNull()
    }

    @Test
    fun `terminator - negative color value`() = runTest {
        val toxicJson = """
        {
            "version": "1.0.0",
            "timestamp": 123456,
            "settings": {
                "textColor": -999999999,
                "favoriteComponents": [],
                "favoritesOrder": [],
                "hiddenComponents": []
            }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importThemeSettings = true))
        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        // Farbe sollte trotzdem gesetzt werden (negative Werte sind valide für Android Colors)
    }

    @Test
    fun `terminator - MAX_LONG timestamp`() = runTest {
        val toxicJson = """
        {
            "version": "1.0.0",
            "timestamp": ${Long.MAX_VALUE},
            "settings": {
                "favoriteComponents": [],
                "favoritesOrder": [],
                "hiddenComponents": []
            }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions())
        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
    }

    @Test
    fun `alien - NaN float value`() = runTest {
        val toxicJson = """
        {
            "version": "1.0.0",
            "timestamp": 123456,
            "settings": {
                "layout_scale": "NaN",
                "favoriteComponents": [],
                "favoritesOrder": [],
                "hiddenComponents": []
            }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importThemeSettings = true))
        Truth.assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `alien - Infinity float value`() = runTest {
        val toxicJson = """
        {
            "version": "1.0.0",
            "timestamp": 123456,
            "settings": {
                "layoutScale": 1e309,
                "favoriteComponents": [],
                "favoritesOrder": [],
                "hiddenComponents": []
            }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importThemeSettings = true))
        // Entweder InvalidFormat oder Success mit gecapptem Wert
        if (result is ImportResult.Success) {
            Truth.assertThat(fakeSettingsRepo.layoutScale).isFinite()
            Truth.assertThat(fakeSettingsRepo.layoutScale).isAtMost(AppConstants.LAYOUT_SCALE_MAX)
        }
    }

    @Test
    fun `alien - array instead of object for customAppNames`() = runTest {
        val toxicJson = """
        {
            "version": "1.0.0",
            "timestamp": 123456,
            "settings": {
                "customAppNames": ["not", "an", "object"],
                "favoriteComponents": [],
                "favoritesOrder": [],
                "hiddenComponents": []
            }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importCustomNames = true))
        Truth.assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `alien - object instead of array for favorites`() = runTest {
        val toxicJson = """
        {
            "version": "1.0.0",
            "timestamp": 123456,
            "settings": {
                "favoriteComponents": {"not": "an array"},
                "favoritesOrder": [],
                "hiddenComponents": []
            }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importFavorites = true))
        Truth.assertThat(result).isEqualTo(ImportResult.InvalidFormat)
    }

    @Test
    fun `alien - null inside favorites array - should skip nulls gracefully`() = runTest {
        val toxicJson = """
    {
        "version": "1.0.0",
        "timestamp": 123456,
        "settings": {
            "favoriteComponents": ["com.app1/Activity", null, "com.app2/Activity"],
            "favoritesOrder": [],
            "hiddenComponents": [],
            "customAppNames": {}
        }
    }
    """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importFavorites = true))

        // Tolerantes Verhalten: null wird übersprungen, valide Strings werden verarbeitet
        // Die 2 Apps sind nicht installiert → landen in skippedCount/missingApps
        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
    }

    @Test
    fun `predator - unicode injection in package name`() = runTest {
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.app1", "com.app1.MainActivity")
        )

        // Zero-Width-Joiner und andere unsichtbare Zeichen
        val toxicJson = """
        {
            "version": "1.0.0",
            "timestamp": 123456,
            "settings": {
                "favoriteComponents": ["com.app1\u200B/com.app1.MainActivity"],
                "favoritesOrder": [],
                "hiddenComponents": []
            }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importFavorites = true))
        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        // Der manipulierte String sollte NICHT matchen
        Truth.assertThat(fakeFavoritesRepo.favorites).isEmpty()
    }

    @Test
    fun `predator - null byte injection`() = runTest {
        val toxicJson = """
        {
            "version": "1.0.0",
            "timestamp": 123456,
            "settings": {
                "favoriteComponents": ["com.app1\u0000/MainActivity"],
                "favoritesOrder": [],
                "hiddenComponents": []
            }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importFavorites = true))
        // Sollte nicht crashen
        Truth.assertThat(result).isNotNull()
    }

    @Test
    fun `predator - deeply nested JSON (stack overflow attempt)`() = runTest {
        // 1000 Level tiefe Verschachtelung
        val openBrackets = "{\"a\":".repeat(1000)
        val closeBrackets = "}".repeat(1000)
        val toxicJson = """
        {
            "version": "1.0.0",
            "timestamp": 123456,
            "settings": {
                "favoriteComponents": [],
                "favoritesOrder": [],
                "hiddenComponents": [],
                "customAppNames": $openBrackets"deep"$closeBrackets
            }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importCustomNames = true))
        // Sollte entweder InvalidFormat oder Error sein, aber nicht crashen
        Truth.assertThat(result).isAnyOf(ImportResult.InvalidFormat, ImportResult.Error::class.java)
    }

    @Test
    fun `predator - JSON with BOM (Byte Order Mark)`() = runTest {
        val bom = "\uFEFF"
        val toxicJson = bom + """
        {
            "version": "1.0.0",
            "timestamp": 123456,
            "settings": {
                "favoriteComponents": [],
                "favoritesOrder": [],
                "hiddenComponents": []
            }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions())
        // Sollte trotzdem funktionieren oder graceful failen
        Truth.assertThat(result).isNotNull()
    }

    @Test
    fun `predator - duplicate keys in JSON`() = runTest {
        val toxicJson = """
        {
            "version": "1.0.0",
            "version": "999.0.0",
            "timestamp": 123456,
            "settings": {
                "splitModeThreshold": 42,
                "splitModeThreshold": 999,
                "favoriteComponents": [],
                "favoritesOrder": [],
                "hiddenComponents": []
            }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importPowerUserSettings = true))
        // Verhalten ist JSON-Parser abhängig, sollte aber nicht crashen
        Truth.assertThat(result).isNotNull()
    }

    @Test
    fun `apocalypse - combined attack`() = runTest {
        val toxicJson = """
        {
            "version": "1.0.0\u0000injected",
            "timestamp": -1,
            "settings": {
                "favoriteComponents": ["${"x".repeat(10000)}/Activity", null],
                "favoritesOrder": {"not": "array"},
                "hiddenComponents": [],
                "customAppNames": [],
                "textColor": "not a color",
                "layoutScale": 1e999,
                "splitModeThreshold": 99999999999999999999
            }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions())
        // Der einzige akzeptable Outcome: KEIN CRASH
        Truth.assertThat(result).isNotNull()
    }

    @Test
    fun `doomsday - type confusion - boolean as string - blocked by validator`() = runTest {
        // SZENARIO: autoShowKeyboard erwartet Boolean, bekommt aber String "true"
        // (Manche Parser sind hier zu tolerant, wir wollen strikt sein)
        val toxicJson = """
        {
            "version": "1.0.0",
            "timestamp": 123456,
            "settings": {
                "auto_show_keyboard": "not_a_boolean", 
                "text_color": -1
            }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importQualityOfLife = true))

        // Erwartung: Der Validator (validateJsonTypes) erkennt, dass das kein Boolean ist.
        // parseBackupData returns null -> ImportResult.InvalidFormat
        Truth.assertThat(result).isInstanceOf(ImportResult.InvalidFormat::class.java)
    }

    @Test
    fun `doomsday - integer overflow - color values larger than max int`() = runTest {
        // SZENARIO: ARGB Farbe Weiss ist 0xFFFFFFFF.
        // Als vorzeichenbehafteter 32-Bit Int ist das -1.
        // Als "reine Zahl" im JSON ist das 4294967295.
        // Standard getInt() würde hier crashen.

        val hugeColorValue = 4294967295L // 0xFFFFFFFF unsigned
        val toxicJson = """
        {
            "version": "1.0.0",
            "timestamp": 123456,
            "settings": {
                "text_color": $hugeColorValue
            }
        }
    """.trimIndent()

        fakeSettingsRepo.color = 0 // Reset

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importThemeSettings = true))

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // WICHTIG: Erwartet wird -1 (Color.WHITE), nicht ein Crash oder 0
        Truth.assertThat(fakeSettingsRepo.color).isEqualTo(-1)
    }

    @Test
    fun `doomsday - phantom data - settings fields are explicitly null`() = runTest {
        // SZENARIO: Ein JSON-Generator hat "null" geschrieben statt das Feld wegzulassen.
        fakeSettingsRepo.splitModeThreshold = 50 // Alter Wert

        val toxicJson = """
        {
            "version": "1.0.0",
            "timestamp": 123456,
            "settings": {
                "splitModeThreshold": null,
                "textColor": null
            }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importPowerUserSettings = true))

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // Erwartung: Der alte Wert (50) bleibt erhalten.
        // Der "null"-Wert im JSON überschreibt NICHT den Default/Backup Wert.
        Truth.assertThat(fakeSettingsRepo.splitModeThreshold).isEqualTo(50)
    }

    @Test
    fun `doomsday - structure mismatch - favorites is an object instead of list`() = runTest {
        // SZENARIO: favoriteComponents sollte ["a", "b"] sein, ist aber {}
        val toxicJson = """
        {
            "version": "1.0.0",
            "timestamp": 123456,
            "settings": {
                "favoriteComponents": { "bad": "structure" }
            }
        }
    """.trimIndent()

        // Hier wird kotlinx crashen.
        // Dann übernimmt parseStrictly.
        // getStrictStringList versucht getJSONArray. Das wird fehlschlagen (JSONException).
        // parseStrictly fängt JSONException -> null.
        // importFromJson -> InvalidFormat.

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importFavorites = true))

        Truth.assertThat(result).isInstanceOf(ImportResult.InvalidFormat::class.java)
    }

    @Test
    fun `doomsday - physics violation - extreme float values`() = runTest {
        // SZENARIO: Layout Scale ist viel zu groß.
        val toxicJson = """
        {
            "version": "1.0.0",
            "timestamp": 123456,
            "settings": {
                "layout_scale": 9999.99
            }
        }
    """.trimIndent()

        fakeSettingsRepo.layoutScale = 1.0f

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importThemeSettings = true))

        Truth.assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // Erwartung: Der Wert wurde auf MAX_SCALE (wahrscheinlich 2.0f oder ähnlich) gecapped.
        Truth.assertThat(fakeSettingsRepo.layoutScale).isEqualTo(AppConstants.LAYOUT_SCALE_MAX)
    }

    @Test
    fun `doomsday - empty shell - valid json but missing content`() = runTest {
        val toxicJson = "{}"

        // validateJsonTypes prüft auf "settings". Fehlt -> return true (erstmal ok).
        // kotlinx decoded ein Default-Objekt (da encodeDefaults=true/params optional).
        // ABER: parseStrictly würde bei fehlendem "settings" meckern.
        // Da kotlinx hier wahrscheinlich erfolgreich decoded (mit leeren Defaults),
        // müssen wir schauen, was passiert.

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importFavorites = true))

        // Kotlinx Serialization macht aus "{}" ein BackupData mit Default-Werten.
        // Settings ist required in BackupData?
        // Wenn in BackupData `val settings: LauncherSettings` steht, schlägt kotlinx fehl (MissingFieldException).
        // Dann greift StrictParsing -> wirft "Missing required field: settings".
        // Ergebnis -> InvalidFormat.

        Truth.assertThat(result).isInstanceOf(ImportResult.InvalidFormat::class.java)
    }

    @Test
    fun `doomsday - empty strings everywhere`() = runTest {
        val toxicJson = """
        {
            "version": "",
            "timestamp": 123456,
            "settings": {
                "favoriteComponents": ["", "/", "com.app1/"],
                "favoritesOrder": [],
                "hiddenComponents": [],
                "swipeLeftApp": ""
            }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions())
        // Leere Version sollte als unsupported gelten oder InvalidFormat
        Truth.assertThat(result).isNotNull()
    }

    @Test
    fun `doomsday - scientific notation in integers`() = runTest {
        // Manche JSON-Generatoren schreiben große Zahlen als 1e5
        val toxicJson = """
        {
            "version": "1.0.0",
            "timestamp": 1e10,
            "settings": {
                "split_mode_threshold": 1e2,
                "text_color": -1
            }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importPowerUserSettings = true))

        if (result is ImportResult.Success) {
            // 1e2 = 100, sollte akzeptiert werden
            Truth.assertThat(fakeSettingsRepo.splitModeThreshold).isEqualTo(100)
        }
    }

    @Test
    fun `doomsday - whitespace and control characters in strings`() = runTest {
        val toxicJson = """
        {
            "version": "1.0.0",
            "timestamp": 123456,
            "settings": {
                "favoriteComponents": ["com.app1/\t\n\rMainActivity"],
                "favoritesOrder": [],
                "hiddenComponents": []
            }
        }
    """.trimIndent()

        val result = backupManager.importFromJson(toxicJson, ImportOptions(importFavorites = true))
        Truth.assertThat(result).isNotNull()
    }



    // Helper für manuelles JSON bauen
    private fun createJsonWithThreshold(value: Int): String {
        return """
            {
                "version": "1.0.0",
                "timestamp": 123456,
                "appVersion": "1.0.0",
                "settings": {
                    "split_mode_threshold": $value
                }
            }
        """.trimIndent()
    }


    // ========== HELPER METHODS ==========

    private fun createAppInfo(packageName: String, className: String): AppInfo {
        return AppInfo(
            originalName = packageName,
            displayName = packageName,
            packageName = packageName,
            className = className
        )
    }

    private fun createTestBackup(
        version: String = "1.0.0", // Schema Version
        appVersion: String = "1.0.0",  // App-Version für den unitTest. nicht anpassen.
        timestamp: Long = System.currentTimeMillis(),
        favorites: Set<String> = emptySet(),
        order: List<String> = emptyList(),
        hidden: Set<String> = emptySet(),
        names: Map<String, String> = emptyMap(),
        swipeLeft: String? = null,
        swipeRight: String? = null,
        textColor: Int? = null,
        chipBackgroundColor: Int? = null,
        textShadowEnabled: Boolean? = null,
        layoutScale: Float? = null,
        verticalPaddingScale: Float? = null,
        isFontBold: Boolean? = null,
        contentTopMarginScale: Float? = null,
        showCalendarEvent: Boolean? = null,
        showAlarm: Boolean? = null,
        doubleTapToLockEnabled: Boolean? = null,
        swipeDownToNotificationsEnabled: Boolean? = null,
        autoShowKeyboard: Boolean? = null,
        autoLaunchApp: Boolean? = null,
        splitModeThreshold: Int? = null
    ): BackupData {
        return BackupData(
            version = version,
            timestamp = timestamp,
            appVersion = appVersion,
            settings = LauncherSettings(
                favoriteComponents = favorites,
                favoritesOrder = order,
                hiddenComponents = hidden,
                customAppNames = names,
                swipeLeftApp = swipeLeft,
                swipeRightApp = swipeRight,
                textColor = textColor,
                chipBackgroundColor = chipBackgroundColor,
                textShadowEnabled = textShadowEnabled,
                layoutScale = layoutScale,
                verticalPaddingScale = verticalPaddingScale,
                isFontBold = isFontBold,
                contentTopMarginScale = contentTopMarginScale,
                showCalendarEvent = showCalendarEvent,
                showAlarm = showAlarm,
                doubleTapToLockEnabled = doubleTapToLockEnabled,
                swipeDownToNotificationsEnabled = swipeDownToNotificationsEnabled,
                autoShowKeyboard = autoShowKeyboard,
                autoLaunchApp = autoLaunchApp,
                splitModeThreshold = splitModeThreshold
            )
        )
    }
}