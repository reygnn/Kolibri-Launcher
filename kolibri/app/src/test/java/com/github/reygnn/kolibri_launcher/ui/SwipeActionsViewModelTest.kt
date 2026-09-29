package com.github.reygnn.kolibri_launcher.ui

import app.cash.turbine.test
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.kolibri_launcher.domain.usecase.GetInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetSwipeActionComponentUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeActionsViewModel
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@ExperimentalCoroutinesApi
class SwipeActionsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    @get:Rule
    val timberRule = TimberRule()

    // relaxed = true erlaubt es, nicht-gestubbte Aufrufe ohne Fehler durchgehen zu lassen.
    // Das entspricht dem alten Mockito-`lenient()`-Verhalten und macht den Test robuster
    // gegenüber zukünftigen ViewModel-Änderungen, die zusätzliche Methoden aufrufen.
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase = mockk(relaxed = true)
    private val getSwipeActionComponentUseCase: GetSwipeActionComponentUseCase = mockk(relaxed = true)
    private val setSwipeActionUseCase: SetSwipeActionUseCase = mockk(relaxed = true)

    private lateinit var viewModel: SwipeActionsViewModel

    // Test Data
    private val appA = AppInfo("App A", "App A", "com.a", "classA")
    private val appB = AppInfo("App B", "App B", "com.b", "classB")
    private val appC = AppInfo("App C", "App C", "com.c", "classC")
    private val testApps = listOf(appA, appB, appC)

    @Before
    fun setup() {
        // Default stubs (each test may override them)
        every { getInstalledAppsUseCase.unsortedInstalledAppsFlow } returns flowOf(testApps)
        coEvery { getSwipeActionComponentUseCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT) } returns null
        coEvery { getSwipeActionComponentUseCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT) } returns null

        viewModel = SwipeActionsViewModel(
            getInstalledAppsUseCase,
            getSwipeActionComponentUseCase,
            setSwipeActionUseCase,
            mainDispatcherRule.testDispatcher
        )
    }

    // ========== INITIALIZATION TESTS ==========

    @Test
    fun `initialize - loads apps and current assignments`() = runTest {
        // Arrange
        coEvery { getSwipeActionComponentUseCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT) } returns appA.componentName
        coEvery { getSwipeActionComponentUseCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT) } returns null

        // Act
        viewModel.initialize()
        advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        assertThat(state.selectableApps.size).isEqualTo(3)
        assertThat(state.appForLeft).isEqualTo(appA)
        assertThat(state.appForRight).isNull()

        // App A should be marked as assigned to LEFT in the list
        assertThat(state.selectableApps.find { it.appInfo == appA }?.assignedSlot).isEqualTo(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)
        assertThat(state.selectableApps.find { it.appInfo == appB }?.assignedSlot).isEqualTo(SwipeSlot.NONE)
    }

    @Test
    fun `initialize - re-invocation after config change preserves uncommitted assignment`() = runTest {
        // setup() defaults both persisted slots to null.
        viewModel.initialize()
        advanceUntilIdle()

        // User assigns App A to the (default) LEFT slot — in-memory only;
        // persistence happens in onDoneClicked, which is never called here.
        viewModel.onAppSelected(appA)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.appForLeft).isEqualTo(appA)

        // Config change (rotation): the Activity re-creates and calls initialize()
        // again on the RETAINED ViewModel. Without the isInitialized guard this
        // would overwrite the slot with the persisted null and drop the assignment.
        viewModel.initialize()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.appForLeft).isEqualTo(appA)
    }

    @Test
    fun `initialize - handles loading error gracefully`() = runTest {
        // Arrange
        every { getInstalledAppsUseCase.unsortedInstalledAppsFlow } returns flow { throw IOException("Load failed") }

        viewModel.event.test {
            viewModel.initialize()
            advanceUntilIdle()

            val event = awaitItem()
            assertThat(event).isInstanceOf(UiEvent.ShowToast::class.java)
        }
    }

    // ========== SLOT SELECTION TESTS ==========

    @Test
    fun `onSlotSelected - updates current active slot`() = runTest {
        viewModel.initialize()
        advanceUntilIdle()

        // Default is LEFT
        assertThat(viewModel.uiState.value.currentSlotBeingAssigned).isEqualTo(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)

        // Select RIGHT
        viewModel.onSlotSelected(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.currentSlotBeingAssigned).isEqualTo(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT)

        // Select LEFT back
        viewModel.onSlotSelected(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.currentSlotBeingAssigned).isEqualTo(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)
    }

    // ========== APP ASSIGNMENT LOGIC ==========

    @Test
    fun `onAppSelected - assigns app to active slot`() = runTest {
        viewModel.initialize()
        advanceUntilIdle()

        // Active slot is LEFT. Select App A.
        viewModel.onAppSelected(appA)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.appForLeft).isEqualTo(appA)
        assertThat(state.appForRight).isNull()
    }

    @Test
    fun `onAppSelected - toggles assignment off if same app selected`() = runTest {
        viewModel.initialize()
        advanceUntilIdle()

        // Assign App A to LEFT
        viewModel.onAppSelected(appA)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.appForLeft).isEqualTo(appA)

        // Select App A again (toggle off)
        viewModel.onAppSelected(appA)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.appForLeft).isNull()
    }

    @Test
    fun `onAppSelected - moves app from Right to Left if selected while Left is active`() = runTest {
        // Arrange: App B is initially RIGHT
        coEvery { getSwipeActionComponentUseCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT) } returns appB.componentName
        viewModel.initialize()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.appForRight).isEqualTo(appB)

        // Act: Active slot is LEFT. Select App B.
        viewModel.onAppSelected(appB)
        advanceUntilIdle()

        // Assert: App B moves to LEFT, RIGHT becomes empty
        val state = viewModel.uiState.value
        assertThat(state.appForLeft).isEqualTo(appB)
        assertThat(state.appForRight).isNull()
    }

    @Test
    fun `onAppSelected - moves app from Left to Right if selected while Right is active`() = runTest {
        // Arrange: App A is initially LEFT
        coEvery { getSwipeActionComponentUseCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT) } returns appA.componentName
        viewModel.initialize()
        advanceUntilIdle()

        // Switch active slot to RIGHT
        viewModel.onSlotSelected(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT)
        advanceUntilIdle()

        // Act: Select App A
        viewModel.onAppSelected(appA)
        advanceUntilIdle()

        // Assert: App A moves to RIGHT, LEFT becomes empty
        val state = viewModel.uiState.value
        assertThat(state.appForRight).isEqualTo(appA)
        assertThat(state.appForLeft).isNull()
    }

    @Test
    fun `onSlotCleared - clears specific slot`() = runTest {
        // Arrange
        coEvery { getSwipeActionComponentUseCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT) } returns appA.componentName
        coEvery { getSwipeActionComponentUseCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT) } returns appB.componentName
        viewModel.initialize()
        advanceUntilIdle()

        // Act
        viewModel.onSlotCleared(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)
        advanceUntilIdle()

        // Assert
        assertThat(viewModel.uiState.value.appForLeft).isNull()
        assertThat(viewModel.uiState.value.appForRight).isEqualTo(appB)
    }

    // ========== SEARCH & FILTER TESTS ==========

    @Test
    fun `onSearchQueryChanged - filters displayed list`() = runTest {
        viewModel.initialize()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("App B")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.selectableApps.size).isEqualTo(1)
        assertThat(state.selectableApps[0].appInfo).isEqualTo(appB)
    }

    // ========== SAVING TESTS ==========

    @Test
    fun `onDoneClicked - saves both slots correctly`() = runTest {
        // Arrange: Set up state
        viewModel.initialize()
        advanceUntilIdle()

        // Set Left = A, Right = B
        viewModel.onSlotSelected(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)
        viewModel.onAppSelected(appA)
        viewModel.onSlotSelected(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT)
        viewModel.onAppSelected(appB)
        advanceUntilIdle()

        // Act
        viewModel.onDoneClicked()
        advanceUntilIdle()

        // Assert: Verify UseCase calls (suspend → coVerify)
        coVerify { setSwipeActionUseCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA.componentName) }
        coVerify { setSwipeActionUseCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appB.componentName) }
    }

    @Test
    fun `onDoneClicked - navigates up after save`() = runTest {
        viewModel.initialize()
        advanceUntilIdle() // load must complete so the save-gate (F2) opens

        viewModel.event.test {
            viewModel.onDoneClicked()
            val event = awaitItem()
            assertThat(event).isEqualTo(UiEvent.NavigateUp)
        }
    }

    @Test
    fun `onDoneClicked - handles save error gracefully`() = runTest {
        // Arrange
        coEvery { setSwipeActionUseCase(any<SwipeSlot>(), any()) } throws RuntimeException("Save failed")
        viewModel.initialize()
        advanceUntilIdle() // load must complete so onDoneClicked reaches the save

        viewModel.event.test {
            viewModel.onDoneClicked()

            // Should show toast AND navigate up (fail-safe)
            val event1 = awaitItem()
            assertThat(event1).isInstanceOf(UiEvent.ShowToast::class.java)

            val event2 = awaitItem()
            assertThat(event2).isEqualTo(UiEvent.NavigateUp)
        }
    }

    @Test
    fun `onDoneClicked - before load completes does NOT overwrite stored actions`() = runTest {
        // AUDIT-17 F2: swipeLeft/Right default to null and are only populated at the
        // end of initialize(). A Done tap in that pre-load window (isLoaded == false)
        // must NOT persist null/null over the user's stored swipe actions. Here we
        // never let the load complete (no initialize) — the save-gate stays closed.
        viewModel.event.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()

            // No write happened...
            coVerify(exactly = 0) { setSwipeActionUseCase(any<SwipeSlot>(), any()) }
            // ...but the tap is still not a dead end.
            assertThat(awaitItem()).isEqualTo(UiEvent.NavigateUp)
        }
    }
}
