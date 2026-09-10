package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.nyx_launcher.home.model.AppLoadResult
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.model.ReconcileResult
import com.github.reygnn.nyx_launcher.home.repository.FakeHomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.FakeInstalledAppsRepository
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ReconcileHomeLayoutUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val grid = GridSpec(columns = 4, rows = 6)
    private val ids = ItemIdFactory { ItemId("new") }
    private fun ck(p: String) = ComponentKey(p, "$p.Main")
    private fun launcherApp(p: String) = LauncherApp(ck(p), p, null)

    private fun layoutWith(vararg pkgs: String): HomeLayout = HomeLayout(
        grid,
        pages = 1,
        items = pkgs.mapIndexed { i, p -> PlacedItem(HomeItem.App(ItemId(p), ck(p)), CellPos(0, i, 0)) },
        dock = emptyList(),
    )

    private fun useCase(layoutRepo: FakeHomeLayoutRepository, apps: FakeInstalledAppsRepository) =
        ReconcileHomeLayoutUseCase(layoutRepo, apps, ids, mainDispatcherRule.dispatcher)

    @Test
    fun error_load_is_skipped_and_never_saves() = runTest(mainDispatcherRule.dispatcher) {
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb"))
        val apps = FakeInstalledAppsRepository(AppLoadResult.Error(AppLoadResult.Reason.ENUMERATION_FAILED))

        val result = useCase(layoutRepo, apps)()

        assertThat(result).isInstanceOf(ReconcileResult.Skipped::class.java)
        assertThat(layoutRepo.saveCount).isEqualTo(0) // FAIL-CLOSED: home untouched
        assertThat(layoutRepo.current.items).hasSize(2)
    }

    @Test
    fun loaded_with_dead_app_prunes_and_saves_once() = runTest(mainDispatcherRule.dispatcher) {
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb"))
        val apps = FakeInstalledAppsRepository(listOf(launcherApp("pa"))) // pb uninstalled

        val result = useCase(layoutRepo, apps)()

        assertThat(result).isInstanceOf(ReconcileResult.Reconciled::class.java)
        assertThat(layoutRepo.saveCount).isEqualTo(1)
        assertThat(layoutRepo.current.items.map { it.item.id }).containsExactly(ItemId("pa"))
    }

    @Test
    fun loaded_all_installed_is_unchanged_and_does_not_save() = runTest(mainDispatcherRule.dispatcher) {
        val layoutRepo = FakeHomeLayoutRepository(layoutWith("pa", "pb"))
        val apps = FakeInstalledAppsRepository(listOf(launcherApp("pa"), launcherApp("pb")))

        val result = useCase(layoutRepo, apps)()

        assertThat(result).isEqualTo(ReconcileResult.Unchanged)
        assertThat(layoutRepo.saveCount).isEqualTo(0)
    }
}
