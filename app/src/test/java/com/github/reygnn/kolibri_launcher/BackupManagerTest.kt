package com.github.reygnn.kolibri_launcher


import android.content.Context
import android.graphics.Color
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.BackupData
import com.github.reygnn.kolibri_launcher.data.BackupManager
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock


/**
 * Unit-Test für den BackupManager.
 *
 * Testet die Kern-Logik (exportToJson, importFromJson).
 * File I/O Tests (saveBackupToFile, loadBackupFromFile, previewBackup)
 * sollten als Instrumented Tests mit echtem Android-Framework getestet werden.
 */

@ExperimentalCoroutinesApi
class BackupManagerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Mocks und Fakes
    private lateinit var mockContext: Context
    private lateinit var fakeFavoritesRepo: FakeFavoritesRepository
    private lateinit var fakeFavoritesOrderRepo: FakeFavoritesOrderRepository
    private lateinit var fakeVisibilityRepo: FakeAppVisibilityRepository
    private lateinit var fakeNamesRepo: FakeAppNamesRepository
    private lateinit var fakeInstalledAppsRepo: SimpleFakeInstalledAppsRepository
    private lateinit var fakeSwipeActionsRepo: FakeSwipeActionsRepository
    private lateinit var fakeSettingsRepo: FakeSettingsRepository

    private lateinit var backupManager: BackupManager

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        fakeInstalledAppsRepo = SimpleFakeInstalledAppsRepository()
        fakeFavoritesRepo = FakeFavoritesRepository()
        fakeFavoritesOrderRepo = FakeFavoritesOrderRepository()
        fakeVisibilityRepo = FakeAppVisibilityRepository()
        fakeNamesRepo = FakeAppNamesRepository()
        fakeSwipeActionsRepo = FakeSwipeActionsRepository()
        fakeSettingsRepo = FakeSettingsRepository()

        backupManager = BackupManager(
            fakeFavoritesRepo,
            fakeFavoritesOrderRepo,
            fakeVisibilityRepo,
            fakeNamesRepo,
            fakeInstalledAppsRepo,
            fakeSwipeActionsRepo,
            fakeSettingsRepo,
            mockContext
        )
    }

    // ========== EXPORT TESTS ==========

    @Test
    fun `exportToJson - with empty data - creates valid backup`() = runTest {
        val jsonString = backupManager.exportToJson()

        val backup = json.decodeFromString<BackupData>(jsonString)

        assertThat(backup.version).isEqualTo("1.0.0")
        assertThat(backup.timestamp).isGreaterThan(0L)
        assertThat(backup.settings.favoriteComponents).isEmpty()
        assertThat(backup.settings.favoritesOrder).isEmpty()
        assertThat(backup.settings.hiddenComponents).isEmpty()
        assertThat(backup.settings.customAppNames).isEmpty()
        assertThat(backup.settings.swipeLeftApp).isNull()
        assertThat(backup.settings.swipeRightApp).isNull()
        assertThat(backup.settings.textColor).isNull()
        assertThat(backup.settings.chipBackgroundColor).isNull()
        assertThat(backup.settings.textShadowEnabled).isNull()  // Default=true → null
        assertThat(backup.settings.doubleTapToLockEnabled).isNull()  // Default=false → null
        assertThat(backup.settings.swipeDownToNotificationsEnabled).isNull() // Default=false → null
        assertThat(backup.settings.autoShowKeyboard).isNull()
        assertThat(backup.settings.autoLaunchApp).isNull()
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

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeSettingsRepo.doubleTap).isTrue()
        assertThat(fakeSettingsRepo.swipeDown).isTrue()
        assertThat(fakeSettingsRepo.color).isEqualTo(Color.BLACK)
        assertThat(fakeFavoritesRepo.favorites).isEmpty()
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
            importGestureSettings = false  // Explizit false
        )

        val result = backupManager.importFromJson(jsonString, options)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeSettingsRepo.color).isEqualTo(Color.GREEN)
        assertThat(fakeSettingsRepo.chipBgColor).isEqualTo(Color.MAGENTA)
        assertThat(fakeSettingsRepo.shadow).isFalse()
        assertThat(fakeSettingsRepo.doubleTap).isFalse()
        assertThat(fakeSettingsRepo.swipeDown).isFalse()
    }

    @Test
    fun `exportToJson - with favorites - includes favorites in backup`() = runTest {
        fakeFavoritesRepo.favorites = setOf(
            "com.app1/com.app1.MainActivity",
            "com.app2/com.app2.MainActivity"
        )

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        assertThat(backup.settings.favoriteComponents).hasSize(2)
        assertThat(backup.settings.favoriteComponents).containsExactly(
            "com.app1/com.app1.MainActivity",
            "com.app2/com.app2.MainActivity"
        )
    }

    @Test
    fun `exportToJson - with order - includes order in backup`() = runTest {
        fakeFavoritesOrderRepo.order = listOf(
            "com.app2/com.app2.MainActivity",
            "com.app1/com.app1.MainActivity"
        )

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        assertThat(backup.settings.favoritesOrder).containsExactly(
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

        assertThat(backup.settings.hiddenComponents).hasSize(2)
        assertThat(backup.settings.hiddenComponents).containsExactly(
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

        assertThat(backup.settings.customAppNames).hasSize(2)
        assertThat(backup.settings.customAppNames).containsEntry("com.app1", "Custom App 1")
        assertThat(backup.settings.customAppNames).containsEntry("com.app2", "Custom App 2")
    }

    @Test
    fun `exportToJson - with swipe left only - includes left swipe in backup`() = runTest {
        fakeSwipeActionsRepo.swipeLeftApp = "com.app1/com.app1.MainActivity"
        fakeSwipeActionsRepo.swipeRightApp = null

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        assertThat(backup.settings.swipeLeftApp).isEqualTo("com.app1/com.app1.MainActivity")
        assertThat(backup.settings.swipeRightApp).isNull()
    }

    @Test
    fun `exportToJson - with swipe right only - includes right swipe in backup`() = runTest {
        fakeSwipeActionsRepo.swipeLeftApp = null
        fakeSwipeActionsRepo.swipeRightApp = "com.app2/com.app2.MainActivity"

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        assertThat(backup.settings.swipeLeftApp).isNull()
        assertThat(backup.settings.swipeRightApp).isEqualTo("com.app2/com.app2.MainActivity")
    }

    @Test
    fun `exportToJson - with both swipe actions - includes both in backup`() = runTest {
        fakeSwipeActionsRepo.swipeLeftApp = "com.app1/com.app1.MainActivity"
        fakeSwipeActionsRepo.swipeRightApp = "com.app2/com.app2.MainActivity"

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        assertThat(backup.settings.swipeLeftApp).isEqualTo("com.app1/com.app1.MainActivity")
        assertThat(backup.settings.swipeRightApp).isEqualTo("com.app2/com.app2.MainActivity")
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

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        assertThat(backup.settings.favoriteComponents).hasSize(1)
        assertThat(backup.settings.favoritesOrder).hasSize(1)
        assertThat(backup.settings.hiddenComponents).hasSize(1)
        assertThat(backup.settings.customAppNames).hasSize(1)
        assertThat(backup.settings.swipeLeftApp).isNotNull()
        assertThat(backup.settings.swipeRightApp).isNotNull()
        assertThat(backup.settings.textColor).isEqualTo(Color.RED)
        assertThat(backup.settings.chipBackgroundColor).isEqualTo(Color.GREEN)
        assertThat(backup.settings.textShadowEnabled).isFalse()
        assertThat(backup.settings.doubleTapToLockEnabled).isTrue()
        assertThat(backup.settings.swipeDownToNotificationsEnabled).isTrue()
        assertThat(backup.settings.autoShowKeyboard).isTrue()
        assertThat(backup.settings.autoLaunchApp).isTrue()
    }

    @Test
    fun `exportToJson - sets timestamp explicitly`() = runTest {
        val beforeExport = System.currentTimeMillis()

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        val afterExport = System.currentTimeMillis()

        assertThat(backup.timestamp).isAtLeast(beforeExport)
        assertThat(backup.timestamp).isAtMost(afterExport)
        assertThat(backup.timestamp).isGreaterThan(0L)
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
            importQualityOfLife = false
        )

        val result = backupManager.importFromJson(jsonString, options)

        assertThat(result).isInstanceOf(ImportResult.Error::class.java)
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

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeFavoritesRepo.favorites).hasSize(1)
        assertThat(fakeFavoritesOrderRepo.order).isEmpty()
        assertThat(fakeVisibilityRepo.hiddenApps).isEmpty()
        assertThat(fakeNamesRepo.getAllCustomNames()).isEmpty()
        assertThat(fakeSwipeActionsRepo.swipeLeftApp).isNull()
        assertThat(fakeSwipeActionsRepo.swipeRightApp).isNull()
        assertThat(fakeSettingsRepo.color).isEqualTo(Color.BLACK)
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

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeFavoritesOrderRepo.order).hasSize(1)
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

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeVisibilityRepo.hiddenApps).hasSize(1)
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

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeNamesRepo.getAllCustomNames()).hasSize(1)
        assertThat(fakeNamesRepo.batchSetCalled).isTrue()
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

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeFavoritesRepo.favorites).isEmpty()
        assertThat(fakeSwipeActionsRepo.swipeLeftApp).isEqualTo("com.app1/com.app1.MainActivity")
        assertThat(fakeSwipeActionsRepo.swipeRightApp).isEqualTo("com.app2/com.app2.MainActivity")
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

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        assertThat(fakeSettingsRepo.color).isEqualTo(Color.GREEN)
        assertThat(fakeSettingsRepo.chipBgColor).isEqualTo(Color.MAGENTA)
        assertThat(fakeSettingsRepo.shadow).isFalse()

        assertThat(fakeFavoritesRepo.favorites).isEmpty()
    }

    @Test
    fun `importFromJson - swipe actions - filters non-installed left app`() = runTest {
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.app2", "com.app2.MainActivity")
        )

        val backup = createTestBackup(
            swipeLeft = "com.nonexistent/com.nonexistent.MainActivity",
            swipeRight = "com.app2/com.app2.MainActivity"
        )
        val jsonString = json.encodeToString(backup)
        val options = ImportOptions(importSwipeActions = true)

        val result = backupManager.importFromJson(jsonString, options)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val success = result as ImportResult.Success
        assertThat(fakeSwipeActionsRepo.swipeLeftApp).isNull()
        assertThat(fakeSwipeActionsRepo.swipeRightApp).isEqualTo("com.app2/com.app2.MainActivity")
        assertThat(success.missingApps).contains("com.nonexistent/com.nonexistent.MainActivity")
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

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val success = result as ImportResult.Success
        assertThat(fakeSwipeActionsRepo.swipeLeftApp).isEqualTo("com.app1/com.app1.MainActivity")
        assertThat(fakeSwipeActionsRepo.swipeRightApp).isNull()
        assertThat(success.missingApps).contains("com.nonexistent/com.nonexistent.MainActivity")
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

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val success = result as ImportResult.Success
        assertThat(fakeSwipeActionsRepo.swipeLeftApp).isNull()
        assertThat(fakeSwipeActionsRepo.swipeRightApp).isNull()
        assertThat(success.missingApps).hasSize(2)
    }

    @Test
    fun `importFromJson - all options - imports everything`() = runTest {
        fakeInstalledAppsRepo.installedApps = listOf(
            createAppInfo("com.app1", "com.app1.MainActivity"),
            createAppInfo("com.app2", "com.app2.MainActivity")
        )
        fakeSettingsRepo.color = Color.BLACK
        fakeSettingsRepo.chipBgColor = Color.BLACK
        fakeSettingsRepo.shadow = true
        fakeSettingsRepo.doubleTap = false
        fakeSettingsRepo.swipeDown = false
        fakeSettingsRepo.autoShowKeyboard = false
        fakeSettingsRepo.autoLaunchApp = false

        val backup = createTestBackup(
            favorites = setOf("com.app1/com.app1.MainActivity"),
            order = listOf("com.app1/com.app1.MainActivity"),
            hidden = setOf("com.app2/com.app2.MainActivity"),
            names = mapOf("com.app1" to "Name"),
            swipeLeft = "com.app1/com.app1.MainActivity",
            swipeRight = "com.app2/com.app2.MainActivity",
            textColor = Color.BLUE,
            chipBackgroundColor = Color.CYAN,
            textShadowEnabled = false,
            doubleTapToLockEnabled = true,
            swipeDownToNotificationsEnabled = true,
            autoShowKeyboard = true,
            autoLaunchApp = true
        )
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(
            importFavorites = true,
            importOrder = true,
            importHiddenApps = true,
            importCustomNames = true,
            importSwipeActions = true,
            importThemeSettings = true,
            importGestureSettings = true,
            importQualityOfLife = true
        )

        val result = backupManager.importFromJson(jsonString, options)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeFavoritesRepo.favorites).hasSize(1)
        assertThat(fakeFavoritesOrderRepo.order).hasSize(1)
        assertThat(fakeVisibilityRepo.hiddenApps).hasSize(1)
        assertThat(fakeNamesRepo.getAllCustomNames()).hasSize(1)
        assertThat(fakeSwipeActionsRepo.swipeLeftApp).isNotNull()
        assertThat(fakeSwipeActionsRepo.swipeRightApp).isNotNull()
        assertThat(fakeSettingsRepo.color).isEqualTo(Color.BLUE)
        assertThat(fakeSettingsRepo.chipBgColor).isEqualTo(Color.CYAN)
        assertThat(fakeSettingsRepo.shadow).isFalse()
        assertThat(fakeSettingsRepo.doubleTap).isTrue()
        assertThat(fakeSettingsRepo.swipeDown).isTrue()
        assertThat(fakeSettingsRepo.autoShowKeyboard).isTrue()
        assertThat(fakeSettingsRepo.autoLaunchApp).isTrue()
    }

    // ========== VALIDATION TESTS ==========

    @Test
    fun `importFromJson - unsupported version - returns UnsupportedVersion`() = runTest {
        val backup = createTestBackup(version = "2.0.0")
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions()
        val result = backupManager.importFromJson(jsonString, options)

        assertThat(result).isInstanceOf(ImportResult.UnsupportedVersion::class.java)
        assertThat((result as ImportResult.UnsupportedVersion).version).isEqualTo("2.0.0")
    }

    @Test
    fun `importFromJson - invalid JSON - returns InvalidFormat`() = runTest {
        val invalidJson = "{ invalid json }"

        val options = ImportOptions()
        val result = backupManager.importFromJson(invalidJson, options)

        assertThat(result).isInstanceOf(ImportResult.InvalidFormat::class.java)
    }

    @Test
    fun `importFromJson - exceeds package limit - returns LimitExceeded`() = runTest {
        val appInfos = (1..300).map { createAppInfo("com.app$it", "com.app$it.MainActivity") }
        fakeInstalledAppsRepo.installedApps = appInfos

        val appNames = appInfos.map { it.componentName }.toSet()
        val backup = createTestBackup(favorites = appNames)
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(importFavorites = true)

        val result = backupManager.importFromJson(jsonString, options)

        assertThat(result).isInstanceOf(ImportResult.LimitExceeded::class.java)
        val limitExceeded = result as ImportResult.LimitExceeded
        assertThat(limitExceeded.packageCount).isEqualTo(300)
        assertThat(limitExceeded.limit).isEqualTo(AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME)
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

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val success = result as ImportResult.Success
        assertThat(success.importedCount).isEqualTo(1)
        assertThat(success.skippedCount).isEqualTo(1)
        assertThat(success.missingApps).contains("com.nonexistent/com.nonexistent.MainActivity")
        assertThat(fakeFavoritesRepo.favorites).hasSize(1)
        assertThat(fakeFavoritesRepo.favorites).contains("com.app1/com.app1.MainActivity")
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

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeFavoritesOrderRepo.order).containsExactly("com.app1/com.app1.MainActivity")
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

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeVisibilityRepo.hiddenApps).hasSize(1)
        assertThat(fakeVisibilityRepo.hiddenApps).contains("com.app1/com.app1.MainActivity")
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

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val importedNames = fakeNamesRepo.getAllCustomNames()
        assertThat(importedNames).hasSize(1)
        assertThat(importedNames).containsKey("com.app1")
    }

    // ========== EDGE CASES ==========

    @Test
    fun `importFromJson - empty backup - succeeds`() = runTest {
        fakeInstalledAppsRepo.installedApps = emptyList()
        val backup = createTestBackup()
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions()
        val result = backupManager.importFromJson(jsonString, options)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val success = result as ImportResult.Success
        assertThat(success.importedCount).isEqualTo(0)
        assertThat(success.skippedCount).isEqualTo(0)
    }

    @Test
    fun `importFromJson - custom names with empty map - succeeds without trigger`() = runTest {
        fakeInstalledAppsRepo.installedApps = emptyList()
        val backup = createTestBackup(names = emptyMap())
        val jsonString = json.encodeToString(backup)

        val options = ImportOptions(importCustomNames = true)

        val result = backupManager.importFromJson(jsonString, options)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeNamesRepo.batchSetCalled).isFalse()
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

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeFavoritesRepo.favorites).hasSize(3)
    }

    @Test
    fun `importFromJson - performance test with large dataset`() = runTest {
        // Simuliere 100 installierte Apps
        val installedApps = (1..100).map { createAppInfo("com.app$it", "com.app$it.MainActivity") }
        fakeInstalledAppsRepo.installedApps = installedApps

        // Backup mit 8 Favoriten (8 verschiedene Packages - Maximum erlaubt)
        val favorites = (1..8).map { "com.app$it/com.app$it.MainActivity" }.toSet()

        // 50 versteckte Apps
        val hidden = (20..69).map { "com.app$it/com.app$it.MainActivity" }.toSet()

        // 60 Custom Names
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
            autoLaunchApp = true
        )
        val jsonString = json.encodeToString(backup)

        val startTime = System.currentTimeMillis()
        val result = backupManager.importFromJson(jsonString, ImportOptions())
        val duration = System.currentTimeMillis() - startTime

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(duration).isLessThan(1000) // Sollte < 1 Sekunde sein

        // Validiere Ergebnisse
        assertThat(fakeFavoritesRepo.favorites).hasSize(8)  // 8 Favoriten
        assertThat(fakeVisibilityRepo.hiddenApps).hasSize(50)  // 50 hidden
        assertThat(fakeNamesRepo.getAllCustomNames()).hasSize(60)  // 60 names
        assertThat(fakeSwipeActionsRepo.swipeLeftApp).isNotNull()
        assertThat(fakeSwipeActionsRepo.swipeRightApp).isNotNull()
        assertThat(fakeSettingsRepo.color).isEqualTo(Color.YELLOW)
        assertThat(fakeSettingsRepo.chipBgColor).isEqualTo(Color.RED)
        assertThat(fakeSettingsRepo.shadow).isFalse()
        assertThat(fakeSettingsRepo.doubleTap).isTrue()
        assertThat(fakeSettingsRepo.swipeDown).isTrue()
        assertThat(fakeSettingsRepo.autoShowKeyboard).isTrue()
        assertThat(fakeSettingsRepo.autoLaunchApp).isTrue()
    }

    @Test
    fun `importFromJson - old backup without theme keys - does not change theme`() = runTest {
        // Setze initiale Werte, die *nicht* geändert werden dürfen
        fakeSettingsRepo.color = Color.CYAN
        fakeSettingsRepo.chipBgColor = Color.BLUE
        fakeSettingsRepo.shadow = false

        // Ein JSON-String, der die Keys 'textColor', 'textShadowEnabled'
        // und 'chipBackgroundColor' *nicht* enthält
        val backup = createTestBackup(
            favorites = setOf("com.app1/com.app1.MainActivity"),
            textColor = null,
            chipBackgroundColor = null,
            textShadowEnabled = null
        )
        val oldBackupJson = json.encodeToString(backup)

        // Sicherstellen, dass die Keys wirklich fehlen
//        assertThat(oldBackupJson).doesNotContain("text_color")
//        assertThat(oldBackupJson).doesNotContain("chip_bg_color")
//        assertThat(oldBackupJson).doesNotContain("text_shadow_enabled")

        val options = ImportOptions(importThemeSettings = true)

        val result = backupManager.importFromJson(oldBackupJson, options)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // Prüfen: Theme-Werte sind unverändert, da im Backup null (nicht vorhanden)
        assertThat(fakeSettingsRepo.color).isEqualTo(Color.CYAN)
        assertThat(fakeSettingsRepo.chipBgColor).isEqualTo(Color.BLUE)
        assertThat(fakeSettingsRepo.shadow).isFalse()
    }

    @Test
    fun `importFromJson - old backup without gesture keys - does not change gestures`() = runTest {
        // Setze initiale Werte, die *nicht* geändert werden dürfen
        fakeSettingsRepo.doubleTap = true
        fakeSettingsRepo.swipeDown = false

        // Ein JSON-String, der die Keys nicht enthält
        val backup = createTestBackup(
            favorites = setOf("com.app1/com.app1.MainActivity"),
            doubleTapToLockEnabled = null,
            swipeDownToNotificationsEnabled = null
        )
        val oldBackupJson = json.encodeToString(backup)

        // Sicherstellen, dass die Keys wirklich fehlen
//        assertThat(oldBackupJson).doesNotContain("double_tap_to_lock_enabled")
//        assertThat(oldBackupJson).doesNotContain("swipe_down_to_notifications_enabled")

        val options = ImportOptions(importGestureSettings = true)

        val result = backupManager.importFromJson(oldBackupJson, options)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // Prüfen: Gesture-Werte sind unverändert, da im Backup null (nicht vorhanden)
        assertThat(fakeSettingsRepo.doubleTap).isTrue()
        assertThat(fakeSettingsRepo.swipeDown).isFalse()
    }

    // ========== EXPORT TESTS - AUTO SHOW KEYBOARD ==========

    @Test
    fun `exportToJson - with autoShowKeyboard enabled - includes in backup`() = runTest {
        fakeSettingsRepo.autoShowKeyboard = true

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        assertThat(backup.settings.autoShowKeyboard).isTrue()
    }

    @Test
    fun `exportToJson - with autoShowKeyboard disabled default - returns null`() = runTest {
        fakeSettingsRepo.autoShowKeyboard = false

        val jsonString = backupManager.exportToJson()
        val backup = json.decodeFromString<BackupData>(jsonString)

        // Default (false) wird als null exportiert (takeIf { it })
        assertThat(backup.settings.autoShowKeyboard).isNull()
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

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(fakeSettingsRepo.autoShowKeyboard).isTrue()
        assertThat(fakeSettingsRepo.autoLaunchApp).isTrue()
        assertThat(fakeSettingsRepo.color).isEqualTo(Color.BLACK)
        assertThat(fakeFavoritesRepo.favorites).isEmpty()
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
            importThemeSettings = false,  // Explizit false
            importGestureSettings = false,
            importTimeBasedEvents = false,
            importQualityOfLife = false  // Explizit false
        )

        val result = backupManager.importFromJson(jsonString, options)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // importQualityOfLife Settings dürfen NICHT importiert worden sein
        assertThat(fakeSettingsRepo.autoShowKeyboard).isFalse()
        assertThat(fakeSettingsRepo.autoLaunchApp).isFalse()

        // Aber Favorites WURDEN importiert
        assertThat(fakeFavoritesRepo.favorites).hasSize(1)

        // Theme wurde NICHT importiert
        assertThat(fakeSettingsRepo.color).isEqualTo(Color.BLACK)
    }

    @Test
    fun `importFromJson - old backup without QoL keys - does not change QoL settings`() = runTest {
        // Setze initialen Wert, der *nicht* geändert werden darf
        fakeSettingsRepo.autoShowKeyboard = true
        fakeSettingsRepo.autoLaunchApp = true

        // Ein JSON-String, der den Key 'autoShowKeyboard' *nicht* enthält
        val backup = createTestBackup(
            favorites = setOf("com.app1/com.app1.MainActivity"),
            autoShowKeyboard = null,
            autoLaunchApp = null
        )
        val oldBackupJson = json.encodeToString(backup)

        // Sicherstellen, dass die Keys wirklich fehlen
//        assertThat(oldBackupJson).doesNotContain("auto_show_keyboard")
//        assertThat(oldBackupJson).doesNotContain("auto_launch_app")

        val options = ImportOptions(importQualityOfLife = true)

        val result = backupManager.importFromJson(oldBackupJson, options)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // Prüfen: QoL-Werte sind unverändert, da im Backup null (nicht vorhanden)
        assertThat(fakeSettingsRepo.autoShowKeyboard).isTrue()
        assertThat(fakeSettingsRepo.autoLaunchApp).isTrue()
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
        version: String = "1.0.0",
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
        doubleTapToLockEnabled: Boolean? = null,
        swipeDownToNotificationsEnabled: Boolean? = null,
        autoShowKeyboard: Boolean? = null,
        autoLaunchApp: Boolean? = null
    ): BackupData {
        return BackupData(
            version = version,
            timestamp = timestamp,
            appVersion = "1.0.0",
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
                doubleTapToLockEnabled = doubleTapToLockEnabled,
                swipeDownToNotificationsEnabled = swipeDownToNotificationsEnabled,
                autoShowKeyboard = autoShowKeyboard,
                autoLaunchApp = autoLaunchApp
            )
        )
    }
}

// ========== FAKE REPOSITORIES ==========

class FakeFavoritesRepository : FavoritesRepository {
    private val flow = MutableStateFlow(setOf<String>())

    var favorites: Set<String>
        get() = flow.value
        set(value) {
            flow.value = value
        }

    override val favoriteComponentsFlow = flow

    override suspend fun isFavoriteComponent(componentName: String?) = componentName in favorites
    override suspend fun cleanupFavoriteComponents(installedComponentNames: List<String>) {}
    override suspend fun toggleFavoriteComponent(componentName: String) = true
    override suspend fun addFavoriteComponent(componentName: String) = true
    override suspend fun removeFavoriteComponent(componentName: String) = true
    override suspend fun saveFavoriteComponents(componentNames: List<String>) {
        favorites = componentNames.toSet()
    }

    override suspend fun purgeRepository() {
        favorites = emptySet()
    }
}

class FakeFavoritesOrderRepository : FavoritesOrderRepository {
    private val flow = MutableStateFlow(listOf<String>())

    var order: List<String>
        get() = flow.value
        set(value) {
            flow.value = value
        }

    override val favoriteComponentsOrderFlow = flow

    override suspend fun sortFavoriteComponents(favoriteApps: List<AppInfo>, order: List<String>) =
        favoriteApps

    override suspend fun saveOrder(orderedComponentNames: List<String>): Boolean {
        order = orderedComponentNames
        return true
    }

    override suspend fun purgeRepository() {
        order = emptyList()
    }
}

class FakeAppVisibilityRepository : HiddenAppsRepository {
    private val flow = MutableStateFlow(setOf<String>())

    var hiddenApps: Set<String>
        get() = flow.value
        set(value) {
            flow.value = value
        }

    override val hiddenAppsFlow = flow

    override suspend fun isComponentHidden(componentName: String?) = componentName in hiddenApps
    override suspend fun hideComponent(componentName: String?) = true
    override suspend fun showComponent(componentName: String?) = true
    override suspend fun updateComponentVisibilities(
        componentsToHide: Set<String>,
        componentsToShow: Set<String>
    ) {
        hiddenApps = (hiddenApps + componentsToHide) - componentsToShow
    }

    override suspend fun purgeRepository() {
        hiddenApps = emptySet()
    }
}

class SimpleFakeInstalledAppsRepository : InstalledAppsRepository {
    var installedApps = listOf<AppInfo>()

    override fun getInstalledApps(): Flow<List<AppInfo>> {
        return flowOf(installedApps)
    }

    override suspend fun triggerAppsUpdate() {
        // Nichts tun im Test
    }

    override suspend fun purgeRepository() {
        // Nichts tun im Test
    }
}

// Fake SwipeActionsRepository
class FakeSwipeActionsRepository : SwipeActionsRepository {
    private val leftFlow = MutableStateFlow<String?>(null)
    private val rightFlow = MutableStateFlow<String?>(null)

    var swipeLeftApp: String?
        get() = leftFlow.value
        set(value) {
            leftFlow.value = value
        }

    var swipeRightApp: String?
        get() = rightFlow.value
        set(value) {
            rightFlow.value = value
        }

    override val swipeLeftAppFlow = leftFlow
    override val swipeRightAppFlow = rightFlow

    override suspend fun setSwipeAction(slot: SwipeSlot, componentName: String?) {
        when (slot) {
            SwipeSlot.LEFT -> swipeLeftApp = componentName
            SwipeSlot.RIGHT -> swipeRightApp = componentName
            SwipeSlot.NONE -> {}
        }
    }

    override suspend fun purgeRepository() {
        swipeLeftApp = null
        swipeRightApp = null
    }
}

class FakeSettingsRepository : SettingsRepository {

    private val shadowFlow = MutableStateFlow(true) // Default: true
    private val colorFlow = MutableStateFlow(0) // Default: 0 (Auto)
    private val chipBgColorFlow = MutableStateFlow(0) // Default: 0 (Auto)
    private val calendarFlow = MutableStateFlow(false) // Default false
    private val alarmFlow = MutableStateFlow(false) // Default: false
    private val doubleTapFlow = MutableStateFlow(false) // Default false
    private val swipeDownFlow = MutableStateFlow(false) // Default false
    private val autoShowKeyboardFlowState = MutableStateFlow(false)
    private val autoLaunchAppFlowState = MutableStateFlow(false)


    var shadow: Boolean
        get() = shadowFlow.value
        set(value) {
            shadowFlow.value = value
        }

    var color: Int
        get() = colorFlow.value
        set(value) {
            colorFlow.value = value
        }

    var chipBgColor: Int
        get() = chipBgColorFlow.value
        set(value) {
            chipBgColorFlow.value = value
        }

    var showCalendar: Boolean
        get() = calendarFlow.value
        set(value) {
            calendarFlow.value = value
        }

    var showAlarm: Boolean
        get() = alarmFlow.value
        set(value) {
            alarmFlow.value = value
        }

    var doubleTap: Boolean
        get() = doubleTapFlow.value
        set(value) {
            doubleTapFlow.value = value
        }

    var swipeDown: Boolean
        get() = swipeDownFlow.value
        set(value) {
            swipeDownFlow.value = value
        }

    var autoShowKeyboard: Boolean
        get() = autoShowKeyboardFlowState.value
        set(value) {
            autoShowKeyboardFlowState.value = value
        }

    var autoLaunchApp: Boolean
        get() = autoLaunchAppFlowState.value
        set(value) { autoLaunchAppFlowState.value = value }

    override val textShadowEnabledFlow: Flow<Boolean> = shadowFlow
    override val textColorFlow: Flow<Int> = colorFlow
    override val chipBackgroundColorFlow: Flow<Int> = chipBgColorFlow

    override suspend fun setTextShadowEnabled(isEnabled: Boolean) {
        shadow = isEnabled
    }

    override suspend fun setTextColor(color: Int) {
        this.color = color
    }

    override suspend fun setChipBackgroundColor(color: Int) {
        this.chipBgColor = color
    }

    override val sortOrderFlow: Flow<SortOrder> = flowOf(SortOrder.TIME_WEIGHTED_USAGE)
    override suspend fun setSortOrder(sortOrder: SortOrder) {}

    override val doubleTapToLockEnabledFlow: Flow<Boolean> = doubleTapFlow
    override suspend fun setDoubleTapToLock(isEnabled: Boolean) {
        doubleTap = isEnabled
    }

    override val swipeDownToNotificationsEnabledFlow: Flow<Boolean> = swipeDownFlow
    override suspend fun setSwipeDownToNotifications(isEnabled: Boolean) {
        swipeDown = isEnabled
    }

    override val readabilityModeFlow: Flow<String> = flowOf("smart_contrast")
    override suspend fun setReadabilityMode(mode: String) {}

    override val onboardingCompletedFlow: Flow<Boolean> = flowOf(false)
    override suspend fun setOnboardingCompleted() {}

    override val showCalendarEventFlow: Flow<Boolean> = calendarFlow
    override suspend fun setShowCalendarEvent(isEnabled: Boolean) {
        showCalendar = isEnabled
    }

    override val showAlarmFlow: Flow<Boolean> = alarmFlow
    override suspend fun setShowAlarm(isEnabled: Boolean) {
        showAlarm = isEnabled
    }

    override val autoShowKeyboardFlow: Flow<Boolean> = autoShowKeyboardFlowState
    override suspend fun setAutoShowKeyboard(isEnabled: Boolean) {
        autoShowKeyboard = isEnabled
    }

    override val autoLaunchAppFlow: Flow<Boolean> = autoLaunchAppFlowState
    override suspend fun setAutoLaunchApp(isEnabled: Boolean) {
        autoLaunchApp = isEnabled
    }

    override suspend fun purgeRepository() {
        color = 0
        shadow = true
        chipBgColor = 0
        showCalendar = false
        showAlarm = false
        doubleTap = false
        swipeDown = false
        autoShowKeyboard = false
        autoLaunchApp = false
    }
}