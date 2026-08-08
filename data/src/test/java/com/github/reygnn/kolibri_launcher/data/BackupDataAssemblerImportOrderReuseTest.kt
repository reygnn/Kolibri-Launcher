package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.AppLoad
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
 * BACKUP-DATA-ASSEMBLER — IMPORT-ORDER REUSE TEST (AUDIT-9 #2)
 * ============================================================================
 *
 * Pins the Phase-2 fix for AUDIT-9 #2: import Phase 2 filters the imported ORDER
 * against the exact favorites set Phase 1 just wrote (`importedFavorites`),
 * NOT against a re-read of `favoriteComponentsFlow`.
 *
 * The guard is discriminating by construction: the test imports favorites {A, B}
 * (Phase 1) and stubs `favoriteComponentsFlow` to return a DIVERGENT value
 * (`emptySet`). If Phase 2 ever regresses to re-reading the flow, it would filter
 * the order against the empty set and drop it (`saveOrder(emptyList())`); because
 * it reuses the Phase-1 set, the order survives. The assertion fails iff the
 * reuse is broken.
 *
 * Why the flow divergence is synthetic: before DATASTORE_READ_SPEC Belang A the
 * flow was a `WhileSubscribed(replay = 1)` hot share whose replay lagged a Phase-1
 * write while no collector was subscribed (the Settings/Backup-screen state) —
 * that was the real production hazard. The flow is a plain COLD flow now, so a
 * `.first()` reads fresh and the lag can no longer occur; the stub only exists to
 * make a re-read regression observable. This is a structural guard, not a
 * reproduction of a live hazard.
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupDataAssemblerImportOrderReuseTest {

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
    fun `import Phase 2 filters the order against the Phase-1 imported favorites, not the flow`() = runTest {
        // ── ARRANGE
        // Both favorites are installed, so Phase 1 writes {A, B}.
        every { installedAppsRepository.getInstalledApps() } returns
            MutableStateFlow(AppLoad.Loaded(listOf(appA, appB)))

        // Stub the flow to a DIVERGENT value (empty): it disagrees with the set
        // Phase 1 imports ({A, B}). If Phase 2 regressed to re-reading the flow it
        // would filter the order against this empty set and drop it; reusing the
        // Phase-1 set keeps the order. (The divergence is synthetic — the flow is
        // cold in production and cannot actually lag; see the class KDoc.)
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

        // ── ASSERT: the imported order is preserved because Phase 2 filters it
        // against the Phase-1 set {A, B}, not the divergent (empty) flow. A re-read
        // regression would capture an empty list here.
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat(savedOrder.captured).containsExactly(compA, compB).inOrder()
    }
}
