package com.github.reygnn.kolibri_launcher.ui

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.favorites.FavoritesSortViewModel
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
        assertThat(viewModel.apps.value).isEqualTo(emptyList<AppInfo>())
    }

    @Test
    fun `setInitialApps populates the apps state`() = runTest(mainDispatcherRule.testDispatcher) {
        viewModel.setInitialApps(initialOrder)
        assertThat(viewModel.apps.value).isEqualTo(initialOrder)
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
            assertThat(viewModel.apps.value).isEqualTo(listOf(browser, camera, mail))
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

                assertThat(viewModel.apps.value).isEqualTo(newOrder)
                assertThat(fakeRepository.savedOrder).isEqualTo(listOf(browser.componentName, camera.componentName, mail.componentName))
                assertThat(fakeRepository.saveOrderCallCount).isEqualTo(1)
                assertThat(awaitItem()).isEqualTo(UiEvent.FavoritesOrderChanged)
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
                assertThat(awaitItem()).isEqualTo(UiEvent.ShowToast(R.string.error_saving_order))
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
                assertThat(awaitItem()).isEqualTo(UiEvent.ShowToast(R.string.error_saving_order))
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

                assertThat(viewModel.apps.value).isEqualTo(alphabeticalOrder)
                assertThat(fakeRepository.savedOrder).isEqualTo(alphabeticalOrder.map { it.componentName })
                assertThat(fakeRepository.saveOrderCallCount).isEqualTo(1)
                assertThat(awaitItem()).isEqualTo(UiEvent.FavoritesOrderChanged)
                assertThat(awaitItem()).isEqualTo(UiEvent.ShowToast(R.string.favorites_sorted_alphabetically))
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
                assertThat(viewModel.apps.value).isEqualTo(alphabeticalOrder)
                assertThat(awaitItem()).isEqualTo(UiEvent.ShowToast(R.string.error_saving_order))
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

            assertThat(viewModel.apps.value).isEqualTo(listOf(a, b, c))
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
                assertThat(awaitItem()).isEqualTo(UiEvent.FavoritesOrderChanged)

                viewModel.onResetToOriginal()
                advanceUntilIdle()

                assertThat(viewModel.apps.value).isEqualTo(initialOrder)
                assertThat(fakeRepository.savedOrder).isEqualTo(initialOrder.map { it.componentName })
                assertThat(awaitItem()).isEqualTo(UiEvent.FavoritesOrderChanged)
                assertThat(awaitItem()).isEqualTo(UiEvent.ShowToast(R.string.favorites_order_reset))
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ------------------------------------------------------------------
    // Save-over-empty gate (RC edge-case audit)
    // ------------------------------------------------------------------

    @Test
    fun `onSortAlphabetically does not persist when never initialized`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // No setInitialApps: the Fragment's fail-open parcelable-parse path
            // can hand the VM an empty list after process death. A Sort tap must
            // NOT write an empty order "[]" over the stored one.
            viewModel.onSortAlphabetically()
            advanceUntilIdle()

            assertThat(fakeRepository.saveOrderCallCount).isEqualTo(0)
        }

    @Test
    fun `onResetToOriginal does not persist when initialized with an empty list`() =
        runTest(mainDispatcherRule.testDispatcher) {
            viewModel.setInitialApps(emptyList())

            viewModel.onResetToOriginal()
            advanceUntilIdle()

            assertThat(fakeRepository.saveOrderCallCount).isEqualTo(0)
        }
}
