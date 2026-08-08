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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * ============================================================================
 * BACKUP-DATA-ASSEMBLER — IMPORT-ORDER STALE-CACHE TEST (AUDIT-9 #2)
 * ============================================================================
 *
 * Pins the Phase-2 fix for AUDIT-9 #2. The fix makes import Phase 2 REUSE the
 * exact favorites set Phase 1 just wrote, instead of re-deriving it by
 * re-reading `favoriteComponentsFlow.first()`.
 *
 * Historical context: `FavoritesRepositoryImpl.favoriteComponentsFlow` used to
 * be a `WhileSubscribed(FLOW_SHARING_TIMEOUT_MS, replay = 1)` hot share whose
 * retained replay value lagged a Phase-1 `saveFavoriteComponents` write while no
 * UI collector was subscribed — exactly the state during an import from the
 * Settings/Backup screen (Home stopped). Before the fix, Phase 2 re-read that
 * flow, saw the pre-import favorites (`emptySet` on a fresh restore), and
 * filtered the imported order against them — silently discarding it
 * (`saveOrder(emptyList())`).
 *
 * NOTE (DATASTORE_READ_SPEC Belang A): that flow is now a plain COLD flow, so the
 * production replay-lag can no longer occur — a `.first()` would read fresh. This
 * test therefore no longer reproduces a real production hazard; it uses a
 * deliberately lagging flow stub purely to guard the code STRUCTURE (Phase 2 must
 * reuse the just-written set, never re-derive from a re-read). A full re-author
 * (or retirement) is the tracked spec §7 follow-up; kept green here as a
 * structural regression guard.
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupDataAssemblerImportOrderStaleCacheTest {

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

    private val appA = AppInfo(
        originalName = "A",
        displayName = "A",
        packageName = "com.example.a",
        className = "com.example.a.Main",
    )
    private val appB = AppInfo(
        originalName = "B",
        displayName = "B",
        packageName = "com.example.b",
        className = "com.example.b.Main",
    )
    private val compA = appA.componentName
    private val compB = appB.componentName

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

    @Test
    fun `import order survives a stale favoriteComponentsFlow replay cache`() = runTest {
        // ── ARRANGE
        // Both favorites are installed, so Phase 1 writes {A, B}.
        every { installedAppsRepository.getInstalledApps() } returns
            MutableStateFlow(listOf(appA, appB))

        // Simulate the production WhileSubscribed(replay = 1) hazard: the hot
        // flow's replay cache still reflects the PRE-import favorites (empty on
        // a fresh restore) and never observes Phase 1's write. If Phase 2
        // trusted this flow it would filter the order against an empty set.
        every { favoritesRepository.favoriteComponentsFlow } returns flowOf(emptySet())

        val backup = BackupData(
            version = "1.0.0",
            timestamp = 0L,
            appVersion = "test",
            settings = LauncherSettings(
                favoriteComponents = setOf(compA, compB),
                favoritesOrder = listOf(compA, compB),
            ),
        )

        val savedOrder = slot<List<String>>()
        coEvery { favoritesOrderRepository.saveOrder(capture(savedOrder)) } returns true

        // ── ACT
        val result = makeAssembler().performImport(backup, ImportOptions(), wallpaperRestorer)

        // ── ASSERT: the imported order is preserved because Phase 2 uses the
        // set Phase 1 wrote, not the lagging flow. Pre-fix this was empty.
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(savedOrder.captured).containsExactly(compA, compB).inOrder()
    }
}
