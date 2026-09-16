package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import com.github.reygnn.nyx_launcher.home.usecase.FitHomeGridUseCase
import com.github.reygnn.nyx_launcher.home.usecase.GetDrawerAppsUseCase
import com.github.reygnn.nyx_launcher.home.usecase.MoveItemUseCase
import com.github.reygnn.nyx_launcher.home.usecase.ObserveHomeLayoutUseCase
import com.github.reygnn.nyx_launcher.home.usecase.PlaceItemUseCase
import com.github.reygnn.nyx_launcher.home.usecase.RemoveFromFolderUseCase
import com.github.reygnn.nyx_launcher.home.usecase.RemoveItemUseCase
import com.github.reygnn.nyx_launcher.home.usecase.RenameFolderUseCase
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Unit test for [HomeViewModel]. Pure JVM (no Android runtime): the VM is a
 * StateFlow holder over the use cases. Covers the drawer-refresh contract
 * reworked under AUDIT-1 A1-04 — cancel-in-flight + never-blank-a-populated-list
 * (A1-01) — plus that each dispatch forwards to its use case (A1-02).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val observeHomeLayout = mockk<ObserveHomeLayoutUseCase>(relaxed = true)
    private val getDrawerApps = mockk<GetDrawerAppsUseCase>()
    private val moveItem = mockk<MoveItemUseCase>(relaxed = true)
    private val placeItem = mockk<PlaceItemUseCase>(relaxed = true)
    private val removeFromFolder = mockk<RemoveFromFolderUseCase>(relaxed = true)
    private val removeItem = mockk<RemoveItemUseCase>(relaxed = true)
    private val renameFolder = mockk<RenameFolderUseCase>(relaxed = true)
    private val fitHomeGrid = mockk<FitHomeGridUseCase>(relaxed = true)
    private val preferences = mockk<PreferencesRepository> {
        every { monochromeIcons() } returns flowOf(false)
    }

    /** Build the VM after [getDrawerApps] is stubbed (init calls refreshDrawer). */
    private fun createViewModel(): HomeViewModel {
        every { observeHomeLayout() } returns emptyFlow()
        return HomeViewModel(
            observeHomeLayout,
            getDrawerApps,
            moveItem,
            placeItem,
            removeFromFolder,
            removeItem,
            renameFolder,
            fitHomeGrid,
            preferences,
            mainDispatcherRule.dispatcher,
        )
    }

    @Test
    fun init_populates_drawerApps_from_a_non_empty_result() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { getDrawerApps() } returns listOf(APP_A)
            val viewModel = createViewModel()

            advanceUntilIdle()

            assertThat(viewModel.drawerApps.value).containsExactly(APP_A)
        }

    @Test
    fun refresh_returning_empty_after_populated_keeps_previous_list() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { getDrawerApps() } returns listOf(APP_A)
            val viewModel = createViewModel()
            advanceUntilIdle()

            // A transient enumeration failure (A1-01) returns an empty list; the
            // populated drawer must survive it.
            coEvery { getDrawerApps() } returns emptyList()
            viewModel.refreshDrawer()
            advanceUntilIdle()

            assertThat(viewModel.drawerApps.value).containsExactly(APP_A)
        }

    @Test
    fun refresh_returning_empty_while_empty_stays_empty() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { getDrawerApps() } returns emptyList()
            val viewModel = createViewModel()

            advanceUntilIdle()

            assertThat(viewModel.drawerApps.value).isEmpty()
        }

    @Test
    fun rapid_refresh_cancels_in_flight_query_and_newest_result_wins() =
        runTest(mainDispatcherRule.dispatcher) {
            // First query is slow (SLOW), the second returns immediately (APP_B).
            var call = 0
            coEvery { getDrawerApps() } coAnswers {
                if (call++ == 0) {
                    delay(1_000)
                    listOf(SLOW)
                } else {
                    listOf(APP_B)
                }
            }

            val viewModel = createViewModel() // init fires refresh #1 (the slow one)
            runCurrent() // let refresh #1 start and suspend inside delay()

            viewModel.refreshDrawer() // #2 cancels the in-flight slow query
            advanceUntilIdle() // #1's delay would resume here — but it was cancelled

            // The slow result must never land; the newest query wins.
            assertThat(viewModel.drawerApps.value).containsExactly(APP_B)
        }

    @Test
    fun move_forwards_to_move_item_use_case() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { getDrawerApps() } returns emptyList()
            val viewModel = createViewModel()

            viewModel.move(ITEM, TARGET)
            advanceUntilIdle()

            coVerify { moveItem(ITEM, TARGET) }
        }

    @Test
    fun place_forwards_to_place_item_use_case() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { getDrawerApps() } returns emptyList()
            val viewModel = createViewModel()

            viewModel.place(KEY, TARGET)
            advanceUntilIdle()

            coVerify { placeItem(KEY, TARGET) }
        }

    @Test
    fun remove_forwards_to_remove_item_use_case() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { getDrawerApps() } returns emptyList()
            val viewModel = createViewModel()

            viewModel.remove(ITEM)
            advanceUntilIdle()

            coVerify { removeItem(ITEM) }
        }

    @Test
    fun apply_device_grid_forwards_the_measured_spec() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { getDrawerApps() } returns emptyList()
            val viewModel = createViewModel()

            viewModel.applyDeviceGrid(columns = 5, rows = 7)
            advanceUntilIdle()

            coVerify { fitHomeGrid(GridSpec(columns = 5, rows = 7)) }
        }

    private companion object {
        val KEY = ComponentKey("pa", "pa.Main")
        val APP_A = LauncherApp(KEY, label = "A")
        val APP_B = LauncherApp(ComponentKey("pb", "pb.Main"), label = "B")
        val SLOW = LauncherApp(ComponentKey("ps", "ps.Main"), label = "Slow")
        val ITEM = ItemId("a")
        val TARGET: DropTarget = DropTarget.Cell(CellPos(0, 0, 0))
    }
}
