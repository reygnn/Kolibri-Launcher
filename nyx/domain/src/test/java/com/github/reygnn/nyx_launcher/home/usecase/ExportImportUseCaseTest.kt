package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.repository.FakeHomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.FakeInstalledAppsRepository
import com.github.reygnn.nyx_launcher.home.repository.FakeLayoutSerializer
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ExportImportUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val grid = GridSpec(columns = 4, rows = 6)
    private fun ck(p: String) = ComponentKey(p, "$p.Main")
    private fun launcherApp(p: String) = LauncherApp(ck(p), p, null)
    private fun empty() = HomeLayout(grid, 1, emptyList(), emptyList())
    private fun appAt(p: String, x: Int) = PlacedItem(HomeItem.App(ItemId(p), ck(p)), CellPos(0, x, 0))

    @Test
    fun export_serializes_the_current_layout() = runTest(mainDispatcherRule.dispatcher) {
        val layout = empty().copy(items = listOf(appAt("pa", 0)))
        val repo = FakeHomeLayoutRepository(layout)
        val serializer = FakeLayoutSerializer(onSerialize = { "BLOB:${it.items.size}" })

        val result = ExportLayoutUseCase(repo, serializer, mainDispatcherRule.dispatcher)()

        assertThat(result).isEqualTo("BLOB:1")
    }

    @Test
    fun import_invalid_data_is_rejected_without_saving() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeHomeLayoutRepository(empty())
        val apps = FakeInstalledAppsRepository(listOf(launcherApp("pa")))
        val serializer = FakeLayoutSerializer(onDeserialize = { null }) // unparseable
        val useCase = ImportLayoutUseCase(repo, serializer, reconcileWith(repo, apps), mainDispatcherRule.dispatcher)

        val result = useCase("garbage")

        assertThat(result).isEqualTo(ImportResult.InvalidData)
        assertThat(repo.saveCount).isEqualTo(0)
    }

    @Test
    fun import_saves_then_reconciles_pruning_missing_apps() = runTest(mainDispatcherRule.dispatcher) {
        // Imported layout references pb, but only pa is installed here → reconcile prunes pb.
        val imported = empty().copy(items = listOf(appAt("pa", 0), appAt("pb", 1)))
        val repo = FakeHomeLayoutRepository(empty())
        val apps = FakeInstalledAppsRepository(listOf(launcherApp("pa")))
        val serializer = FakeLayoutSerializer(onDeserialize = { imported })
        val useCase = ImportLayoutUseCase(repo, serializer, reconcileWith(repo, apps), mainDispatcherRule.dispatcher)

        val result = useCase("valid")

        assertThat(result).isEqualTo(ImportResult.Success)
        // saved once by import, once by reconcile (pb pruned) ⇒ final has only pa.
        assertThat(repo.current.items.map { it.item.id }).containsExactly(ItemId("pa"))
    }

    private fun reconcileWith(repo: FakeHomeLayoutRepository, apps: FakeInstalledAppsRepository) =
        ReconcileHomeLayoutUseCase(
            layoutRepository = repo,
            appsRepository = apps,
            idFactory = ItemIdFactory { ItemId("new") },
            dispatcher = mainDispatcherRule.dispatcher,
        )
}
