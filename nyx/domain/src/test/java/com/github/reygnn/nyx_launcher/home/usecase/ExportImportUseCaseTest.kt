package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.repository.FakeHomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.FakeLayoutSerializer
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Export path only. The layout IMPORT path lives in `:nyx:data`'s NyxBackupManager (which
 * saves the restored layout then runs the structural-only [ReconcileHomeLayoutUseCase]); the
 * no-prune "keep uninstalled refs" property of that reconcile is pinned by
 * `ReconcileHomeLayoutUseCaseTest` and `NyxNoAutoPruneTest`.
 */
class ExportImportUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val grid = GridSpec(columns = 4, rows = 6)
    private fun ck(p: String) = ComponentKey(p, "$p.Main")
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
}
