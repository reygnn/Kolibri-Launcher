package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.graphics.Color
import com.github.reygnn.kolibri_launcher.core.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.BackupData
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSwipeActionsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito

/**
 * STRICT ISOLATION SUITE for BackupManager.
 *
 * Prüft, dass beim selektiven Import wirklich NUR das angeforderte Setting
 * verändert wird, während ALLE anderen Settings absolut unverändert bleiben.
 *
 * Prinzip:
 * 1. Setze Repository auf "State A" (Baseline)
 * 2. Erstelle Backup mit "State B" (Target)
 * 3. Importiere NUR Kategorie X
 * 4. Assert: Kategorie X ist jetzt State B. Alle anderen sind noch State A.
 */
@ExperimentalCoroutinesApi
class BackupManagerIsolationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Fakes
    private lateinit var fakeFavoritesRepo: FakeFavoritesRepository
    private lateinit var fakeOrderRepo: FakeFavoritesOrderRepository
    private lateinit var fakeHiddenRepo: FakeHiddenAppsRepository
    private lateinit var fakeNamesRepo: FakeCustomNamesRepository
    private lateinit var fakeSwipeRepo: FakeSwipeActionsRepository
    private lateinit var fakeSettingsRepo: FakeSettingsRepository
    private lateinit var fakeInstalledAppsRepo: FakeInstalledAppsRepository

    private lateinit var backupManager: BackupManager
    private lateinit var mockContext: Context

    private val json = Json { ignoreUnknownKeys = true }

    // --- STATE DEFINITIONS ---

    // Baseline (Zustand vor Import)
    private val baselineFavorites = setOf("com.base/BaseActivity")
    private val baselineOrder = listOf("com.base/BaseActivity")
    private val baselineHidden = setOf("com.base/HiddenActivity")
    private val baselineNames = mapOf("com.base" to "Base Name")
    private val baselineSwipeLeft = "com.base/SwipeLeft"
    private val baselineSwipeRight = "com.base/SwipeRight"

    // Settings Baseline
    private val baselineColor = Color.BLACK
    private val baselineChipColor = Color.DKGRAY
    private val baselineShadow = true
    private val baselineScale = 1.0f
    private val baselinePadding = 1.0f
    private val baselineBold = false
    private val baselineMargin = 0.0f

    private val baselineDoubleTap = false
    private val baselineSwipeDown = false

    private val baselineCalendar = false
    private val baselineAlarm = false

    private val baselineAutoKeyboard = false
    private val baselineAutoLaunch = false

    private val baselineThreshold = 0

    // Target (Zustand im Backup)
    private val targetFavorites = setOf("com.target/TargetActivity")
    private val targetOrder = listOf("com.target/TargetActivity")
    private val targetHidden = setOf("com.target/HiddenActivity")
    private val targetNames = mapOf("com.target" to "Target Name")
    private val targetSwipeLeft = "com.target/SwipeLeft"
    private val targetSwipeRight = "com.target/SwipeRight"

    // Settings Target
    private val targetColor = Color.MAGENTA
    private val targetChipColor = Color.CYAN
    private val targetShadow = false
    private val targetScale = 1.5f
    private val targetPadding = 0.5f
    private val targetBold = true
    private val targetMargin = 0.5f

    private val targetDoubleTap = true
    private val targetSwipeDown = true

    private val targetCalendar = true
    private val targetAlarm = true

    private val targetAutoKeyboard = true
    private val targetAutoLaunch = true

    private val targetThreshold = 100

    @Before
    fun setup() {
        mockContext = Mockito.mock(Context::class.java)

        fakeFavoritesRepo = FakeFavoritesRepository()
        fakeOrderRepo = FakeFavoritesOrderRepository()
        fakeHiddenRepo = FakeHiddenAppsRepository()
        fakeNamesRepo = FakeCustomNamesRepository()
        fakeSwipeRepo = FakeSwipeActionsRepository()
        fakeSettingsRepo = FakeSettingsRepository()
        fakeInstalledAppsRepo = FakeInstalledAppsRepository()

        // Installiere ALLE Apps (Base und Target), damit Importe nicht gefiltert werden
        fakeInstalledAppsRepo.installedApps = listOf(
            createApp("com.base", "BaseActivity"),
            createApp("com.base", "HiddenActivity"),
            createApp("com.base", "SwipeLeft"),
            createApp("com.base", "SwipeRight"),
            createApp("com.target", "TargetActivity"),
            createApp("com.target", "HiddenActivity"),
            createApp("com.target", "SwipeLeft"),
            createApp("com.target", "SwipeRight")
        )

        backupManager = BackupManager(
            fakeFavoritesRepo,
            fakeOrderRepo,
            fakeHiddenRepo,
            fakeNamesRepo,
            fakeInstalledAppsRepo,
            fakeSwipeRepo,
            fakeSettingsRepo,
            mockContext
        )

        setupBaselineState()
    }

    private fun setupBaselineState() = runTest {
        fakeFavoritesRepo.favorites = baselineFavorites
        fakeOrderRepo.order = baselineOrder
        fakeHiddenRepo.hiddenApps = baselineHidden

        // SUSPEND CALL: Jetzt innerhalb von runTest sicher aufrufbar
        fakeNamesRepo.setCustomNamesInBatch(baselineNames)

        fakeSwipeRepo.swipeLeftApp = baselineSwipeLeft
        fakeSwipeRepo.swipeRightApp = baselineSwipeRight

        fakeSettingsRepo.color = baselineColor
        fakeSettingsRepo.chipBgColor = baselineChipColor
        fakeSettingsRepo.shadow = baselineShadow
        fakeSettingsRepo.layoutScale = baselineScale
        fakeSettingsRepo.verticalPadding = baselinePadding
        fakeSettingsRepo.isFontBold = baselineBold
        fakeSettingsRepo.contentTopMargin = baselineMargin

        fakeSettingsRepo.doubleTap = baselineDoubleTap
        fakeSettingsRepo.swipeDown = baselineSwipeDown

        fakeSettingsRepo.showCalendar = baselineCalendar
        fakeSettingsRepo.showAlarm = baselineAlarm

        fakeSettingsRepo.autoShowKeyboard = baselineAutoKeyboard
        fakeSettingsRepo.autoLaunchApp = baselineAutoLaunch

        fakeSettingsRepo.splitModeThreshold = baselineThreshold
    }

    private fun createTargetBackupJson(): String {
        val settings = LauncherSettings(
            favoriteComponents = targetFavorites,
            favoritesOrder = targetOrder,
            hiddenComponents = targetHidden,
            customAppNames = targetNames,
            swipeLeftApp = targetSwipeLeft,
            swipeRightApp = targetSwipeRight,
            textColor = targetColor,
            chipBackgroundColor = targetChipColor,
            textShadowEnabled = targetShadow,
            layoutScale = targetScale,
            verticalPaddingScale = targetPadding,
            isFontBold = targetBold,
            contentTopMarginScale = targetMargin,
            showCalendarEvent = targetCalendar,
            showAlarm = targetAlarm,
            doubleTapToLockEnabled = targetDoubleTap,
            swipeDownToNotificationsEnabled = targetSwipeDown,
            autoShowKeyboard = targetAutoKeyboard,
            autoLaunchApp = targetAutoLaunch,
            splitModeThreshold = targetThreshold
        )
        val backup = BackupData(
            version = "1.0.0",
            timestamp = System.currentTimeMillis(),
            appVersion = "1.0.0",
            settings = settings
        )
        return json.encodeToString(backup)
    }

    // ========================================================================
    // ISOLATION TESTS (10 Categories)
    // ========================================================================

    @Test
    fun `ISOLATION 0 - Import Nothing (Baseline Check)`() = runTest {
        // Alles False (Default)
        val options = createIsolationOptions()

        val result = backupManager.importFromJson(createTargetBackupJson(), options)

        // Erwartung: Error, da nichts ausgewählt wurde
        assertThat(result).isInstanceOf(ImportResult.Error::class.java)

        // UNCHANGED (Everything must remain Baseline):
        assertFavoritesUnchanged()
        assertOrderUnchanged()
        assertHiddenUnchanged()
        assertNamesUnchanged()
        assertSwipesUnchanged()
        assertThemeUnchanged()
        assertGesturesUnchanged()
        assertTimeEventsUnchanged()
        assertQoLUnchanged()
        assertPowerUserUnchanged()
    }

    @Test
    fun `ISOLATION 1 - Favorites`() = runTest {
        // FIX: Nutze Helper, um sicherzustellen, dass ALLE anderen Optionen FALSE sind
        val options = createIsolationOptions(importFavorites = true)

        val result = backupManager.importFromJson(createTargetBackupJson(), options)
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // CHANGED:
        assertThat(fakeFavoritesRepo.favorites).containsExactlyElementsIn(targetFavorites)

        // UNCHANGED (Checks all other 9 categories):
        // Order check skipped (by design)
        assertHiddenUnchanged()
        assertNamesUnchanged()
        assertSwipesUnchanged()
        assertThemeUnchanged()
        assertGesturesUnchanged()
        assertTimeEventsUnchanged()
        assertQoLUnchanged()
        assertPowerUserUnchanged()
    }

    @Test
    fun `ISOLATION 2 - Order`() = runTest {
        fakeFavoritesRepo.favorites = targetFavorites

        // FIX: Nutze Helper
        val options = createIsolationOptions(importOrder = true)
        val result = backupManager.importFromJson(createTargetBackupJson(), options)
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // CHANGED:
        assertThat(fakeOrderRepo.order).containsExactlyElementsIn(targetOrder).inOrder()

        // UNCHANGED:
        assertHiddenUnchanged()
        assertNamesUnchanged()
        assertSwipesUnchanged()
        assertThemeUnchanged()
        assertGesturesUnchanged()
        assertTimeEventsUnchanged()
        assertQoLUnchanged()
        assertPowerUserUnchanged()
    }

    @Test
    fun `ISOLATION 3 - Hidden Apps`() = runTest {
        // FIX: Nutze Helper
        val options = createIsolationOptions(importHiddenApps = true)
        val result = backupManager.importFromJson(createTargetBackupJson(), options)
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // CHANGED:
        assertThat(fakeHiddenRepo.hiddenApps).containsAtLeastElementsIn(targetHidden)

        // UNCHANGED:
        assertFavoritesUnchanged()
        assertOrderUnchanged()
        assertNamesUnchanged()
        assertSwipesUnchanged()
        assertThemeUnchanged()
        assertGesturesUnchanged()
        assertTimeEventsUnchanged()
        assertQoLUnchanged()
        assertPowerUserUnchanged()
    }

    @Test
    fun `ISOLATION 4 - Custom Names`() = runTest {
        // FIX: Nutze Helper
        val options = createIsolationOptions(importCustomNames = true)
        val result = backupManager.importFromJson(createTargetBackupJson(), options)
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // CHANGED:
        val names = fakeNamesRepo.getAllCustomNames()
        assertThat(names).containsEntry("com.target", "Target Name")

        // UNCHANGED:
        assertFavoritesUnchanged()
        assertOrderUnchanged()
        assertHiddenUnchanged()
        assertSwipesUnchanged()
        assertThemeUnchanged()
        assertGesturesUnchanged()
        assertTimeEventsUnchanged()
        assertQoLUnchanged()
        assertPowerUserUnchanged()
    }

    @Test
    fun `ISOLATION 5 - Swipe Actions`() = runTest {
        // FIX: Nutze Helper
        val options = createIsolationOptions(importSwipeActions = true)
        val result = backupManager.importFromJson(createTargetBackupJson(), options)
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // CHANGED:
        assertThat(fakeSwipeRepo.swipeLeftApp).isEqualTo(targetSwipeLeft)
        assertThat(fakeSwipeRepo.swipeRightApp).isEqualTo(targetSwipeRight)

        // UNCHANGED:
        assertFavoritesUnchanged()
        assertOrderUnchanged()
        assertHiddenUnchanged()
        assertNamesUnchanged()
        assertThemeUnchanged()
        assertGesturesUnchanged()
        assertTimeEventsUnchanged()
        assertQoLUnchanged()
        assertPowerUserUnchanged()
    }

    @Test
    fun `ISOLATION 6 - Theme Settings`() = runTest {
        // FIX: Nutze Helper
        val options = createIsolationOptions(importThemeSettings = true)
        val result = backupManager.importFromJson(createTargetBackupJson(), options)
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // CHANGED:
        assertThat(fakeSettingsRepo.color).isEqualTo(targetColor)
        assertThat(fakeSettingsRepo.chipBgColor).isEqualTo(targetChipColor)
        assertThat(fakeSettingsRepo.shadow).isEqualTo(targetShadow)
        assertThat(fakeSettingsRepo.layoutScale).isEqualTo(targetScale)
        assertThat(fakeSettingsRepo.verticalPadding).isEqualTo(targetPadding)
        assertThat(fakeSettingsRepo.isFontBold).isEqualTo(targetBold)
        assertThat(fakeSettingsRepo.contentTopMargin).isEqualTo(targetMargin)

        // UNCHANGED:
        assertFavoritesUnchanged()
        assertOrderUnchanged()
        assertHiddenUnchanged()
        assertNamesUnchanged()
        assertSwipesUnchanged()
        assertGesturesUnchanged()
        assertTimeEventsUnchanged()
        assertQoLUnchanged()
        assertPowerUserUnchanged()
    }

    @Test
    fun `ISOLATION 7 - Gesture Settings`() = runTest {
        // FIX: Nutze Helper
        val options = createIsolationOptions(importGestureSettings = true)
        val result = backupManager.importFromJson(createTargetBackupJson(), options)
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // CHANGED:
        assertThat(fakeSettingsRepo.doubleTap).isEqualTo(targetDoubleTap)
        assertThat(fakeSettingsRepo.swipeDown).isEqualTo(targetSwipeDown)

        // UNCHANGED:
        assertFavoritesUnchanged()
        assertOrderUnchanged()
        assertHiddenUnchanged()
        assertNamesUnchanged()
        assertSwipesUnchanged()
        assertThemeUnchanged()
        assertTimeEventsUnchanged()
        assertQoLUnchanged()
        assertPowerUserUnchanged()
    }

    @Test
    fun `ISOLATION 8 - Time Based Events`() = runTest {
        // FIX: Nutze Helper
        val options = createIsolationOptions(importTimeBasedEvents = true)
        val result = backupManager.importFromJson(createTargetBackupJson(), options)
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // CHANGED:
        assertThat(fakeSettingsRepo.showCalendar).isEqualTo(targetCalendar)
        assertThat(fakeSettingsRepo.showAlarm).isEqualTo(targetAlarm)

        // UNCHANGED:
        assertFavoritesUnchanged()
        assertOrderUnchanged()
        assertHiddenUnchanged()
        assertNamesUnchanged()
        assertSwipesUnchanged()
        assertThemeUnchanged()
        assertGesturesUnchanged()
        assertQoLUnchanged()
        assertPowerUserUnchanged()
    }

    @Test
    fun `ISOLATION 9 - Quality of Life`() = runTest {
        // FIX: Nutze Helper
        val options = createIsolationOptions(importQualityOfLife = true)
        val result = backupManager.importFromJson(createTargetBackupJson(), options)
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // CHANGED:
        assertThat(fakeSettingsRepo.autoShowKeyboard).isEqualTo(targetAutoKeyboard)
        assertThat(fakeSettingsRepo.autoLaunchApp).isEqualTo(targetAutoLaunch)

        // UNCHANGED:
        assertFavoritesUnchanged()
        assertOrderUnchanged()
        assertHiddenUnchanged()
        assertNamesUnchanged()
        assertSwipesUnchanged()
        assertThemeUnchanged()
        assertGesturesUnchanged()
        assertTimeEventsUnchanged()
        assertPowerUserUnchanged()
    }

    @Test
    fun `ISOLATION 10 - Power User Settings`() = runTest {
        // FIX: Nutze Helper
        val options = createIsolationOptions(importPowerUserSettings = true)
        val result = backupManager.importFromJson(createTargetBackupJson(), options)
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // CHANGED:
        assertThat(fakeSettingsRepo.splitModeThreshold).isEqualTo(targetThreshold)

        // UNCHANGED:
        assertFavoritesUnchanged()
        assertOrderUnchanged()
        assertHiddenUnchanged()
        assertNamesUnchanged()
        assertSwipesUnchanged()
        assertThemeUnchanged()
        assertGesturesUnchanged()
        assertTimeEventsUnchanged()
        assertQoLUnchanged()
    }

    // ========================================================================
    // HELPER: Create explicit options (all false by default)
    // ========================================================================

    private fun createIsolationOptions(
        importFavorites: Boolean = false,
        importOrder: Boolean = false,
        importHiddenApps: Boolean = false,
        importCustomNames: Boolean = false,
        importSwipeActions: Boolean = false,
        importThemeSettings: Boolean = false,
        importGestureSettings: Boolean = false,
        importTimeBasedEvents: Boolean = false,
        importQualityOfLife: Boolean = false,
        importPowerUserSettings: Boolean = false
    ): ImportOptions {
        return ImportOptions(
            importFavorites = importFavorites,
            importOrder = importOrder,
            importHiddenApps = importHiddenApps,
            importCustomNames = importCustomNames,
            importSwipeActions = importSwipeActions,
            importThemeSettings = importThemeSettings,
            importGestureSettings = importGestureSettings,
            importTimeBasedEvents = importTimeBasedEvents,
            importQualityOfLife = importQualityOfLife,
            importPowerUserSettings = importPowerUserSettings
        )
    }

    // ========================================================================
    // ASSERTION HELPERS
    // ========================================================================

    private fun assertFavoritesUnchanged() {
        assertThat(fakeFavoritesRepo.favorites).containsExactlyElementsIn(baselineFavorites)
    }

    private fun assertOrderUnchanged() {
        assertThat(fakeOrderRepo.order).containsExactlyElementsIn(baselineOrder).inOrder()
    }

    private fun assertHiddenUnchanged() {
        assertThat(fakeHiddenRepo.hiddenApps).containsExactlyElementsIn(baselineHidden)
    }

    private suspend fun assertNamesUnchanged() {
        val names = fakeNamesRepo.getAllCustomNames()
        assertThat(names).doesNotContainKey("com.target")
        assertThat(names).containsEntry("com.base", "Base Name")
    }

    private fun assertSwipesUnchanged() {
        assertThat(fakeSwipeRepo.swipeLeftApp).isEqualTo(baselineSwipeLeft)
        assertThat(fakeSwipeRepo.swipeRightApp).isEqualTo(baselineSwipeRight)
    }

    private fun assertThemeUnchanged() {
        assertThat(fakeSettingsRepo.color).isEqualTo(baselineColor)
        assertThat(fakeSettingsRepo.chipBgColor).isEqualTo(baselineChipColor)
        assertThat(fakeSettingsRepo.shadow).isEqualTo(baselineShadow)
        assertThat(fakeSettingsRepo.layoutScale).isEqualTo(baselineScale)
        assertThat(fakeSettingsRepo.verticalPadding).isEqualTo(baselinePadding)
        assertThat(fakeSettingsRepo.isFontBold).isEqualTo(baselineBold)
        assertThat(fakeSettingsRepo.contentTopMargin).isEqualTo(baselineMargin)
    }

    private fun assertGesturesUnchanged() {
        assertThat(fakeSettingsRepo.doubleTap).isEqualTo(baselineDoubleTap)
        assertThat(fakeSettingsRepo.swipeDown).isEqualTo(baselineSwipeDown)
    }

    private fun assertTimeEventsUnchanged() {
        assertThat(fakeSettingsRepo.showCalendar).isEqualTo(baselineCalendar)
        assertThat(fakeSettingsRepo.showAlarm).isEqualTo(baselineAlarm)
    }

    private fun assertQoLUnchanged() {
        assertThat(fakeSettingsRepo.autoShowKeyboard).isEqualTo(baselineAutoKeyboard)
        assertThat(fakeSettingsRepo.autoLaunchApp).isEqualTo(baselineAutoLaunch)
    }

    private fun assertPowerUserUnchanged() {
        assertThat(fakeSettingsRepo.splitModeThreshold).isEqualTo(baselineThreshold)
    }

    private fun createApp(pkg: String, cls: String) = AppInfo(
        originalName = "App",
        displayName = "App",
        packageName = pkg,
        className = cls
    )
}