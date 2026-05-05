package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.BackupData
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * ============================================================================
 * BACKUP-DATA-ASSEMBLER — COLD-PATH IMPORT TESTS
 * ============================================================================
 *
 * Pins the behaviour fixed in TODO §18 (entry now closed): when
 * `performImport` is called from a cold JVM (no live UI subscriber on
 * `InstalledAppsRepository.getInstalledApps()`), the internal `.first()`
 * must wait for the upstream PackageManager query to actually populate
 * the StateFlow before filtering — otherwise every restored component
 * is dropped as "not installed".
 *
 * The production fix replaces a bare `.first()` with a predicate-bounded
 * `withTimeoutOrNull(BACKUP_IMPORT_PRIME_TIMEOUT_MS) { first { it.isNotEmpty() } }`
 * (`error(...)` on null). These tests pin three cases:
 *
 *  1. Cold path — repo emits emptyList() initially and a non-empty list
 *     only after a delay. Import must wait, then succeed.
 *  2. Warm path — repo emits non-empty immediately. Import must succeed
 *     synchronously (regression guard against over-eagerly waiting).
 *  3. Timeout — repo never emits non-empty. Import must fail with
 *     IllegalStateException carrying the timeout message.
 *
 * Scope is intentionally narrow: we don't re-test the 10 import phases
 * (those are covered by `BackupRepositoryImpl*Test`). We only verify
 * that the gate at the top of `performImport` behaves correctly.
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupDataAssemblerColdImportTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private val favoritesRepository: FavoritesRepository = mockk(relaxed = true)
    private val favoritesOrderRepository: FavoritesOrderRepository = mockk(relaxed = true)
    private val hiddenAppsRepository: HiddenAppsRepository = mockk(relaxed = true)
    private val customNamesRepository: CustomNamesRepository = mockk(relaxed = true)
    private val installedAppsRepository: InstalledAppsRepository = mockk(relaxed = true)
    private val swipeActionsRepository: SwipeActionsRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val wallpaperRepository: WallpaperRepository = mockk(relaxed = true)
    private val wallpaperRestorer: WallpaperRestorer = mockk(relaxed = true)

    private val targetComponent = "com.example.alpha/com.example.alpha.MainActivity"

    private val installedApp = AppInfo(
        originalName = "Alpha",
        displayName = "Alpha",
        packageName = "com.example.alpha",
        className = "com.example.alpha.MainActivity",
    )

    /**
     * Phase 2 of import re-reads `favoriteComponentsFlow` to compute the
     * order. We stub it with a flow that completes immediately so the
     * import doesn't hang on it — Phase 1's WhileSubscribed gate is what
     * we're testing here, not Phase 2's behaviour.
     */
    private fun stubFavoritesFlowForPhase2(componentsAfterPhase1: Set<String>) {
        every { favoritesRepository.favoriteComponentsFlow } returns flowOf(componentsAfterPhase1)
    }

    private fun makeAssembler(): BackupDataAssembler = BackupDataAssembler(
        favoritesRepository = favoritesRepository,
        favoritesOrderRepository = favoritesOrderRepository,
        hiddenAppsRepository = hiddenAppsRepository,
        customNamesRepository = customNamesRepository,
        installedAppsRepository = installedAppsRepository,
        swipeActionsRepository = swipeActionsRepository,
        settingsRepository = settingsRepository,
        wallpaperRepository = wallpaperRepository,
        appVersionName = "test",
    )

    private fun backupWithFavorite(component: String) = BackupData(
        version = "1.0.0",
        timestamp = 0L,
        appVersion = "test",
        settings = LauncherSettings(favoriteComponents = setOf(component)),
    )

    @Test
    fun `performImport waits for non-empty installed apps before filtering favorites`() = runTest {
        // ── ARRANGE: WhileSubscribed-style StateFlow with empty initial
        // value; the upstream "PackageManager query" only completes after
        // a 200 ms virtual-time delay. A bare .first() would have dropped
        // the favorite as not-installed.
        val installedAppsFlow = MutableStateFlow<List<AppInfo>>(emptyList())
        every { installedAppsRepository.getInstalledApps() } returns installedAppsFlow

        stubFavoritesFlowForPhase2(setOf(targetComponent))

        launch {
            delay(200)
            installedAppsFlow.value = listOf(installedApp)
        }

        // ── ACT
        val result = makeAssembler()
            .performImport(backupWithFavorite(targetComponent), ImportOptions(), wallpaperRestorer)

        // ── ASSERT: the favorite survives the filter
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val success = result as ImportResult.Success
        assertThat(success.importedCount).isEqualTo(1)
        assertThat(success.skippedCount).isEqualTo(0)
        assertThat(success.missingApps).isEmpty()

        coVerify(exactly = 1) {
            favoritesRepository.saveFavoriteComponents(listOf(targetComponent))
        }
    }

    @Test
    fun `performImport completes synchronously when installed apps are already non-empty`() = runTest {
        // ── ARRANGE: the warm-subscriber case (UI was already mounted).
        // The first emission is non-empty, so the predicate matches
        // immediately and no virtual time elapses.
        val installedAppsFlow = MutableStateFlow(listOf(installedApp))
        every { installedAppsRepository.getInstalledApps() } returns installedAppsFlow

        stubFavoritesFlowForPhase2(setOf(targetComponent))

        // ── ACT
        val timeBefore = testScheduler.currentTime
        val result = makeAssembler()
            .performImport(backupWithFavorite(targetComponent), ImportOptions(), wallpaperRestorer)
        val elapsed = testScheduler.currentTime - timeBefore

        // ── ASSERT: success without burning virtual time on the gate
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(elapsed).isEqualTo(0L)

        coVerify(exactly = 1) {
            favoritesRepository.saveFavoriteComponents(listOf(targetComponent))
        }
    }

    @Test
    fun `performImport throws IllegalStateException when installed apps never populate within timeout`() = runTest {
        // ── ARRANGE: the pathological case — upstream never delivers.
        // withTimeoutOrNull's elapse uses virtual time, so this test
        // doesn't actually wait BACKUP_IMPORT_PRIME_TIMEOUT_MS in wall
        // time; runTest fast-forwards through it.
        val installedAppsFlow = MutableStateFlow<List<AppInfo>>(emptyList())
        every { installedAppsRepository.getInstalledApps() } returns installedAppsFlow

        // ── ACT + ASSERT
        val ex = assertFailsWith<IllegalStateException> {
            makeAssembler()
                .performImport(backupWithFavorite(targetComponent), ImportOptions(), wallpaperRestorer)
        }
        assertThat(ex.message).contains("Timed out waiting for InstalledAppsRepository")

        // ── ASSERT: no partial writes happened — the gate blocks before
        // any phase runs.
        coVerify(exactly = 0) { favoritesRepository.saveFavoriteComponents(any()) }
    }
}
