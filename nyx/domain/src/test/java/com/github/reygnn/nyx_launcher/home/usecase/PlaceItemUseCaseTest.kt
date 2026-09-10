package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.MoveResult
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.repository.FakeHomeLayoutRepository
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class PlaceItemUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val grid = GridSpec(columns = 4, rows = 6)
    private val ids = ItemIdFactory { ItemId("new") }
    private fun ck(p: String) = ComponentKey(p, "$p.Main")
    private fun empty() = HomeLayout(grid, 1, emptyList(), emptyList())

    @Test
    fun places_a_new_app_and_saves() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeHomeLayoutRepository(empty())
        val result = PlaceItemUseCase(repo, ids, mainDispatcherRule.dispatcher)(
            ck("pa"), DropTarget.Cell(CellPos(0, 1, 1)),
        )
        assertThat(result).isInstanceOf(MoveResult.Moved::class.java)
        assertThat(repo.saveCount).isEqualTo(1)
        assertThat(repo.current.items.single().pos).isEqualTo(CellPos(0, 1, 1))
    }

    @Test
    fun placing_an_already_placed_app_moves_it_without_duplicating() = runTest(mainDispatcherRule.dispatcher) {
        val start = empty().copy(
            items = listOf(PlacedItem(HomeItem.App(ItemId("a"), ck("pa")), CellPos(0, 0, 0))),
        )
        val repo = FakeHomeLayoutRepository(start)
        PlaceItemUseCase(repo, ids, mainDispatcherRule.dispatcher)(ck("pa"), DropTarget.Cell(CellPos(0, 2, 2)))

        assertThat(repo.current.items).hasSize(1) // HEU-INV-1: no duplicate
        assertThat(repo.current.items.single().pos).isEqualTo(CellPos(0, 2, 2))
    }
}
