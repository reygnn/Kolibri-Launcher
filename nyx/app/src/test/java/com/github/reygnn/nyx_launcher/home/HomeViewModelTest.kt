package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.DrawerFolder
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderId
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderIdFactory
import com.github.reygnn.nyx_launcher.home.model.DrawerFolders
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import com.github.reygnn.nyx_launcher.home.repository.FakeAppUsageRepository
import com.github.reygnn.nyx_launcher.home.repository.FakeDrawerFoldersRepository
import com.github.reygnn.nyx_launcher.home.repository.FakeHiddenAppsRepository
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import com.github.reygnn.nyx_launcher.home.usecase.RecordAppLaunchUseCase
import com.github.reygnn.nyx_launcher.home.usecase.FitHomeGridUseCase
import com.github.reygnn.nyx_launcher.home.usecase.GetDrawerAppsUseCase
import com.github.reygnn.nyx_launcher.home.usecase.GetDrawerContentUseCase
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
        every { searchAutoLaunch() } returns flowOf(false)
        every { usageSortEnabled() } returns flowOf(false)
    }
    private val wallpaperDisplaySettings = mockk<WallpaperDisplaySettings> {
        every { wallpaperScrimAlphaStateFlow } returns flowOf(0f)
    }

    // Real fake repo + projection use case + a deterministic id stub, so the drawer-folder
    // mutation methods can be asserted against the resulting membership state.
    private val drawerFolders = FakeDrawerFoldersRepository()
    private val hiddenApps = FakeHiddenAppsRepository()
    private val appUsage = FakeAppUsageRepository()
    private val getDrawerContent = GetDrawerContentUseCase(drawerFolders, hiddenApps, appUsage)
    private val drawerFolderIdFactory = DrawerFolderIdFactory { DrawerFolderId("new-folder") }

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
            getDrawerContent,
            drawerFolders,
            drawerFolderIdFactory,
            hiddenApps,
            RecordAppLaunchUseCase(appUsage),
            wallpaperDisplaySettings,
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
    fun hideApp_then_unhideApp_updates_the_hidden_set() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { getDrawerApps() } returns listOf(APP_A)
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.hideApp(APP_A.key)
            advanceUntilIdle()
            assertThat(hiddenApps.current).containsExactly(APP_A.key)

            // unhide takes the `it - key` branch and removes it.
            viewModel.unhideApp(APP_A.key)
            advanceUntilIdle()
            assertThat(hiddenApps.current).isEmpty()
        }

    @Test
    fun hide_already_hidden_or_unhide_not_hidden_is_a_no_op_write() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { getDrawerApps() } returns listOf(APP_A)
            val viewModel = createViewModel()
            advanceUntilIdle()

            // Unhiding an app that isn't hidden → transform returns null → no write.
            viewModel.unhideApp(APP_A.key)
            advanceUntilIdle()
            assertThat(hiddenApps.updateCount).isEqualTo(0)

            viewModel.hideApp(APP_A.key)
            advanceUntilIdle()
            val countAfterHide = hiddenApps.updateCount

            // Hiding an already-hidden app → transform returns null → no second write.
            viewModel.hideApp(APP_A.key)
            advanceUntilIdle()
            assertThat(hiddenApps.updateCount).isEqualTo(countAfterHide)
            assertThat(hiddenApps.current).containsExactly(APP_A.key)
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

    @Test
    fun create_drawer_folder_forwards_creating_a_folder_from_target_then_source() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { getDrawerApps() } returns emptyList()
            val viewModel = createViewModel()

            viewModel.createDrawerFolder(source = APP_A.key, target = APP_B.key)
            advanceUntilIdle()

            val folder = drawerFolders.current.folders.single()
            assertThat(folder.id).isEqualTo(DrawerFolderId("new-folder"))
            assertThat(folder.members).containsExactly(APP_B.key, APP_A.key).inOrder()
        }

    @Test
    fun add_to_drawer_folder_forwards_adding_the_source_as_a_member() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { getDrawerApps() } returns emptyList()
            val viewModel = createViewModel()
            drawerFolders.update {
                DrawerFolders(listOf(DrawerFolder(DrawerFolderId("f1"), "", listOf(APP_B.key, SLOW.key))))
            }

            viewModel.addToDrawerFolder(source = APP_A.key, folderId = DrawerFolderId("f1"))
            advanceUntilIdle()

            assertThat(drawerFolders.current.folders.single().members)
                .containsExactly(APP_B.key, SLOW.key, APP_A.key).inOrder()
        }

    @Test
    fun extract_from_drawer_folder_forwards_shrinking_the_folder() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { getDrawerApps() } returns emptyList()
            val viewModel = createViewModel()
            drawerFolders.update {
                DrawerFolders(listOf(DrawerFolder(DrawerFolderId("f1"), "", listOf(APP_A.key, APP_B.key, SLOW.key))))
            }

            viewModel.extractFromDrawerFolder(DrawerFolderId("f1"), member = APP_B.key)
            advanceUntilIdle()

            assertThat(drawerFolders.current.folders.single().members)
                .containsExactly(APP_A.key, SLOW.key).inOrder()
        }

    @Test
    fun rename_drawer_folder_forwards_setting_the_new_title() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { getDrawerApps() } returns emptyList()
            val viewModel = createViewModel()
            drawerFolders.update {
                DrawerFolders(listOf(DrawerFolder(DrawerFolderId("f1"), "", listOf(APP_A.key, APP_B.key))))
            }

            viewModel.renameDrawerFolder(DrawerFolderId("f1"), title = "Work")
            advanceUntilIdle()

            assertThat(drawerFolders.current.folders.single().title).isEqualTo("Work")
        }

    @Test
    fun add_all_to_drawer_folder_forwards_bulk_membership() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { getDrawerApps() } returns emptyList()
            val viewModel = createViewModel()
            drawerFolders.update {
                DrawerFolders(listOf(DrawerFolder(DrawerFolderId("f1"), "", listOf(APP_A.key, APP_B.key))))
            }

            viewModel.addAllToDrawerFolder(DrawerFolderId("f1"), listOf(SLOW.key))
            advanceUntilIdle()

            assertThat(drawerFolders.current.folders.single().members)
                .containsExactly(APP_A.key, APP_B.key, SLOW.key).inOrder()
        }

    @Test
    fun drawer_vendor_groups_group_the_flat_app_list_by_maker() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { getDrawerApps() } returns listOf(
                LauncherApp(ComponentKey("com.google.a", "com.google.a.M"), "GA"),
                LauncherApp(ComponentKey("com.google.b", "com.google.b.M"), "GB"),
                LauncherApp(ComponentKey("com.other", "com.other.M"), "O"), // single → dropped
            )
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertThat(viewModel.drawerVendorGroups().map { it.label }).containsExactly("Google")
        }

    @Test
    fun addable_vendor_groups_exclude_current_members_and_drop_emptied_groups() =
        runTest(mainDispatcherRule.dispatcher) {
            val g1 = ComponentKey("com.google.a", "com.google.a.M")
            val g2 = ComponentKey("com.google.b", "com.google.b.M")
            coEvery { getDrawerApps() } returns listOf(LauncherApp(g1, "GA"), LauncherApp(g2, "GB"))
            val viewModel = createViewModel()
            advanceUntilIdle()

            // g1 already in the folder → only g2 remains addable.
            val addable = viewModel.addableVendorGroups(setOf(g1))
            assertThat(addable.single().label).isEqualTo("Google")
            assertThat(addable.single().keys).containsExactly(g2)

            // Every app already a member → the group drops out entirely.
            assertThat(viewModel.addableVendorGroups(setOf(g1, g2))).isEmpty()
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
