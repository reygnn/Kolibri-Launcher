package com.github.reygnn.kolibri_launcher.ui

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.favorites.FavoritesSortViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * JVM tests for [FavoritesSortViewModel]. Covers the apps-state contract
 * (initial population, idempotency) and the persist-then-broadcast-or-toast
 * orchestration for all three user-facing actions (drag-and-drop, sort,
 * reset). The Fragment-side wiring (`setFragmentResult`, adapter
 * `submitList`, drag callbacks) is not exercised here — those live in
 * `FavoritesSortFragment` and require an Android runtime.
 *
 * Default repository is the project [FakeFavoritesOrderRepository]; happy-path
 * persistence is asserted against `fake.savedOrder` rather than via mock
 * verification. Failure-injection tests build a one-off
 * `mockk<FavoritesOrderRepository>(relaxed = true)` because the fake exposes
 * no failNextSave hook.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesSortViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var fakeRepository: FakeFavoritesOrderRepository
    private lateinit var viewModel: FavoritesSortViewModel

    // Three apps with display names ordered alphabetically as
    // Browser → Camera → Mail. The initial order is intentionally
    // *not* alphabetical so the sort tests have something to do.
    private val camera = AppInfo("Camera", "Camera", "com.cam", "com.cam.M")
    private val browser = AppInfo("Browser", "Browser", "com.brw", "com.brw.M")
    private val mail = AppInfo("Mail", "Mail", "com.mail", "com.mail.M")
    private val initialOrder = listOf(camera, browser, mail)
    private val alphabeticalOrder = listOf(browser, camera, mail)

    @Before
    fun setup() {
        fakeRepository = FakeFavoritesOrderRepository()
        viewModel = newViewModel(fakeRepository)
    }

    private fun newViewModel(repo: FavoritesOrderRepository) = FavoritesSortViewModel(
        favoritesOrderRepository = repo,
        mainDispatcher = mainDispatcherRule.testDispatcher,
    )

    // ------------------------------------------------------------------
    // setInitialApps
    // ------------------------------------------------------------------

    @Test
    fun `apps is empty before setInitialApps`() = runTest(mainDispatcherRule.testDispatcher) {
        assertEquals(emptyList<AppInfo>(), viewModel.apps.value)
    }

    @Test
    fun `setInitialApps populates the apps state`() = runTest(mainDispatcherRule.testDispatcher) {
        viewModel.setInitialApps(initialOrder)
        assertEquals(initialOrder, viewModel.apps.value)
    }

    @Test
    fun `setInitialApps is idempotent and does not overwrite later changes`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.setInitialApps(initialOrder)
            viewModel.onMoved(listOf(browser, camera, mail))
            advanceUntilIdle()

            // A second setInitialApps (e.g. after rotation triggers another
            // onCreate while the VM survives) must not reset to the args.
            viewModel.setInitialApps(listOf(mail, browser, camera))
            assertEquals(listOf(browser, camera, mail), viewModel.apps.value)
        }

    // ------------------------------------------------------------------
    // onMoved
    // ------------------------------------------------------------------

    @Test
    fun `onMoved updates apps and persists in component-name order`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.setInitialApps(initialOrder)
            val newOrder = listOf(browser, camera, mail)

            viewModel.event.test {
                viewModel.onMoved(newOrder)
                advanceUntilIdle()

                assertEquals(newOrder, viewModel.apps.value)
                assertEquals(
                    listOf(browser.componentName, camera.componentName, mail.componentName),
                    fakeRepository.savedOrder,
                )
                assertEquals(1, fakeRepository.saveOrderCallCount)
                assertEquals(UiEvent.FavoritesOrderChanged, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `onMoved emits error toast and skips OrderChanged when saveOrder returns false`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val brokenRepo = mockk<FavoritesOrderRepository>(relaxed = true) {
                coEvery { saveOrder(any()) } returns false
            }
            viewModel = newViewModel(brokenRepo)
            viewModel.setInitialApps(initialOrder)

            viewModel.event.test {
                viewModel.onMoved(listOf(browser, camera, mail))
                advanceUntilIdle()
                assertEquals(UiEvent.ShowToast(R.string.error_saving_order), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `onMoved emits error toast when saveOrder throws`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val brokenRepo = mockk<FavoritesOrderRepository>(relaxed = true) {
                coEvery { saveOrder(any()) } throws RuntimeException("disk full")
            }
            viewModel = newViewModel(brokenRepo)
            viewModel.setInitialApps(initialOrder)

            viewModel.event.test {
                viewModel.onMoved(listOf(browser, camera, mail))
                advanceUntilIdle()
                assertEquals(UiEvent.ShowToast(R.string.error_saving_order), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ------------------------------------------------------------------
    // onSortAlphabetically
    // ------------------------------------------------------------------

    @Test
    fun `onSortAlphabetically sorts case-insensitively, persists, emits sorted toast`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.setInitialApps(initialOrder)

            viewModel.event.test {
                viewModel.onSortAlphabetically()
                advanceUntilIdle()

                assertEquals(alphabeticalOrder, viewModel.apps.value)
                assertEquals(
                    alphabeticalOrder.map { it.componentName },
                    fakeRepository.savedOrder,
                )
                assertEquals(1, fakeRepository.saveOrderCallCount)
                assertEquals(UiEvent.FavoritesOrderChanged, awaitItem())
                assertEquals(
                    UiEvent.ShowToast(R.string.favorites_sorted_alphabetically),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `onSortAlphabetically emits only error toast when persistence fails`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val brokenRepo = mockk<FavoritesOrderRepository>(relaxed = true) {
                coEvery { saveOrder(any()) } returns false
            }
            viewModel = newViewModel(brokenRepo)
            viewModel.setInitialApps(initialOrder)

            viewModel.event.test {
                viewModel.onSortAlphabetically()
                advanceUntilIdle()
                // Apps state still reflects the (failed-to-persist) sort —
                // matches pre-extraction behavior; the user sees the order
                // change visually but a toast tells them the save failed.
                assertEquals(alphabeticalOrder, viewModel.apps.value)
                assertEquals(UiEvent.ShowToast(R.string.error_saving_order), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `onSortAlphabetically is case-insensitive across mixed casings`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val a = AppInfo("apple", "apple", "com.a", "com.a.M")
            val b = AppInfo("Banana", "Banana", "com.b", "com.b.M")
            val c = AppInfo("CHERRY", "CHERRY", "com.c", "com.c.M")
            viewModel.setInitialApps(listOf(c, a, b))

            viewModel.onSortAlphabetically()
            advanceUntilIdle()

            assertEquals(listOf(a, b, c), viewModel.apps.value)
        }

    // ------------------------------------------------------------------
    // onResetToOriginal
    // ------------------------------------------------------------------

    @Test
    fun `onResetToOriginal restores the captured initial order, persists, emits reset toast`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.setInitialApps(initialOrder)

            viewModel.event.test {
                // Subscribe first, then drive the moves: the event Channel
                // buffers and delivers every event, so onMoved's
                // FavoritesOrderChanged is consumed here rather than dropped.
                viewModel.onMoved(listOf(mail, browser, camera))
                advanceUntilIdle()
                assertEquals(UiEvent.FavoritesOrderChanged, awaitItem())

                viewModel.onResetToOriginal()
                advanceUntilIdle()

                assertEquals(initialOrder, viewModel.apps.value)
                assertEquals(
                    initialOrder.map { it.componentName },
                    fakeRepository.savedOrder,
                )
                assertEquals(UiEvent.FavoritesOrderChanged, awaitItem())
                assertEquals(UiEvent.ShowToast(R.string.favorites_order_reset), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
}
