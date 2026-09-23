package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.AppLoad
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.InstalledAppsRepository
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.model.ReconcileResult
import com.github.reygnn.nyx_launcher.home.model.SkipReason
import com.github.reygnn.nyx_launcher.home.repository.FakeHomeLayoutRepository
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * FAIL-CLOSED reconcile (RHL-INV-1): only a genuine non-empty [AppLoad.Loaded]
 * reconciles; Failed / empty / prime-timeout all Skip with zero mutation + zero
 * save. Reads the SHARED reactive loader (post-migration) via the prime pattern.
 *
 * The fake is a hot [MutableStateFlow] (never completes), so the empty case
 * exercises the real `withTimeoutOrNull` → LOAD_EMPTY skip instead of a completing
 * flow (which would make the prime's `.first {}` throw).
 */
class ReconcileHomeLayoutUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val grid = GridSpec(columns = 4, rows = 6)
    private val ids = ItemIdFactory { ItemId("new") }
    private fun ck(p: String) = ComponentKey(p, "$p.Main")
    private fun appInfo(p: String) = AppInfo(originalName = p, displayName = p, packageName = p, className = "$p.Main")

    private class FakeSharedLoader(initial: AppLoad) : InstalledAppsRepository {
        val flow = MutableStateFlow(initial)
        override fun getInstalledApps(): Flow<AppLoad> = flow
        override suspend fun triggerAppsUpdate() = Unit
        override suspend fun purgeRepository() = Unit
    }

    private fun layoutWith(vararg pkgs: String): HomeLayout = HomeLayout(
        grid,
        pages = 1,
        items = pkgs.mapIndexed { i, p -> PlacedItem(HomeItem.App(ItemId(p), ck(p)), CellPos(0, i, 0)) },
        dock = emptyList(),
    )

    private fun useCase(layoutRepo: FakeHomeLayoutRepository, apps: InstalledAppsRepository) =
        ReconcileHomeLayoutUseCase(layoutRepo, apps, ids, mainDispatcherRule.dispatcher)

    @Test
    fun failed_load_is_skipped_and_never_saves() = runTest(mainDispatcherRule.dispatcher) {
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb"))
        val apps = FakeSharedLoader(AppLoad.Failed(RuntimeException("enumeration boom")))

        val result = useCase(layoutRepo, apps)()

        assertThat(result).isEqualTo(ReconcileResult.Skipped(SkipReason.LOAD_FAILED))
        assertThat(layoutRepo.saveCount).isEqualTo(0) // FAIL-CLOSED: home untouched
        assertThat(layoutRepo.current.items).hasSize(2)
    }

    @Test
    fun empty_load_times_out_and_is_skipped_never_saves() = runTest(mainDispatcherRule.dispatcher) {
        // The conflated initial Loaded(emptyList()) never satisfies the prime, so the
        // window elapses → LOAD_EMPTY skip (subsumes the old ENUMERATION_EMPTY). Still
        // fail-closed: a genuinely-empty / stuck load must never empty the home screen.
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb"))
        val apps = FakeSharedLoader(AppLoad.Loaded(emptyList()))

        val result = useCase(layoutRepo, apps)()

        assertThat(result).isEqualTo(ReconcileResult.Skipped(SkipReason.LOAD_EMPTY))
        assertThat(layoutRepo.saveCount).isEqualTo(0)
        assertThat(layoutRepo.current.items).hasSize(2)
    }

    @Test
    fun loaded_with_dead_app_prunes_and_saves_once() = runTest(mainDispatcherRule.dispatcher) {
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb"))
        val apps = FakeSharedLoader(AppLoad.Loaded(listOf(appInfo("pa")))) // pb uninstalled

        val result = useCase(layoutRepo, apps)()

        assertThat(result).isInstanceOf(ReconcileResult.Reconciled::class.java)
        assertThat(layoutRepo.saveCount).isEqualTo(1)
        assertThat(layoutRepo.current.items.map { it.item.id }).containsExactly(ItemId("pa"))
    }

    @Test
    fun loaded_all_installed_is_unchanged_and_does_not_save() = runTest(mainDispatcherRule.dispatcher) {
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb"))
        val apps = FakeSharedLoader(AppLoad.Loaded(listOf(appInfo("pa"), appInfo("pb"))))

        val result = useCase(layoutRepo, apps)()

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(layoutRepo.saveCount).isEqualTo(0)
    }
}
