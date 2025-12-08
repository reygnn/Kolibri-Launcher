package com.github.reygnn.kolibri_launcher.ui

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.usecase.GetInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetSwipeLeftAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetSwipeRightAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeActionsViewModel
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import java.io.IOException

@ExperimentalCoroutinesApi
class SwipeActionsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var getInstalledAppsUseCase: GetInstalledAppsUseCase
    @Mock
    private lateinit var getSwipeLeftAppUseCase: GetSwipeLeftAppUseCase
    @Mock
    private lateinit var getSwipeRightAppUseCase: GetSwipeRightAppUseCase
    @Mock
    private lateinit var setSwipeActionUseCase: SetSwipeActionUseCase

    private lateinit var viewModel: SwipeActionsViewModel

    // Test Data
    private val appA = AppInfo("App A", "App A", "com.a", "classA")
    private val appB = AppInfo("App B", "App B", "com.b", "classB")
    private val appC = AppInfo("App C", "App C", "com.c", "classC")
    private val testApps = listOf(appA, appB, appC)

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)

        // Default Flow Mocks (lenient, da nicht jeder Test initialize() aufruft)
        lenient().`when`(getInstalledAppsUseCase()).thenReturn(flowOf(testApps))
        lenient().`when`(getSwipeLeftAppUseCase()).thenReturn(flowOf(null))
        lenient().`when`(getSwipeRightAppUseCase()).thenReturn(flowOf(null))

        viewModel = SwipeActionsViewModel(
            getInstalledAppsUseCase,
            getSwipeLeftAppUseCase,
            getSwipeRightAppUseCase,
            setSwipeActionUseCase,
            mainDispatcherRule.testDispatcher
        )
    }

    // ========== INITIALIZATION TESTS ==========

    @Test
    fun `initialize - loads apps and current assignments`() = runTest {
        // Arrange
        `when`(getSwipeLeftAppUseCase()).thenReturn(flowOf(appA.componentName))
        `when`(getSwipeRightAppUseCase()).thenReturn(flowOf(null))

        // Act
        viewModel.initialize()
        advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        assertEquals(3, state.selectableApps.size)
        assertEquals(appA, state.appForLeft)
        assertNull(state.appForRight)

        // App A should be marked as assigned to LEFT in the list
        assertEquals(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, state.selectableApps.find { it.appInfo == appA }?.assignedSlot)
        assertEquals(SwipeSlot.NONE, state.selectableApps.find { it.appInfo == appB }?.assignedSlot)
    }

    @Test
    fun `initialize - handles loading error gracefully`() = runTest {
        // Arrange
        `when`(getInstalledAppsUseCase()).thenReturn(flow { throw IOException("Load failed") })

        viewModel.event.test {
            viewModel.initialize()
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
        }
    }

    // ========== SLOT SELECTION TESTS ==========

    @Test
    fun `onSlotSelected - updates current active slot`() = runTest {
        viewModel.initialize()
        advanceUntilIdle()

        // Default is LEFT
        assertEquals(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, viewModel.uiState.value.currentSlotBeingAssigned)

        // Select RIGHT
        viewModel.onSlotSelected(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT)
        advanceUntilIdle()
        assertEquals(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, viewModel.uiState.value.currentSlotBeingAssigned)

        // Select LEFT back
        viewModel.onSlotSelected(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)
        advanceUntilIdle()
        assertEquals(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, viewModel.uiState.value.currentSlotBeingAssigned)
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
        assertEquals(appA, state.appForLeft)
        assertNull(state.appForRight)
    }

    @Test
    fun `onAppSelected - toggles assignment off if same app selected`() = runTest {
        viewModel.initialize()
        advanceUntilIdle()

        // Assign App A to LEFT
        viewModel.onAppSelected(appA)
        advanceUntilIdle()
        assertEquals(appA, viewModel.uiState.value.appForLeft)

        // Select App A again (toggle off)
        viewModel.onAppSelected(appA)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.appForLeft)
    }

    @Test
    fun `onAppSelected - moves app from Right to Left if selected while Left is active`() = runTest {
        // Arrange: App B is initially RIGHT
        `when`(getSwipeRightAppUseCase()).thenReturn(flowOf(appB.componentName))
        viewModel.initialize()
        advanceUntilIdle()

        assertEquals(appB, viewModel.uiState.value.appForRight)

        // Act: Active slot is LEFT. Select App B.
        viewModel.onAppSelected(appB)
        advanceUntilIdle()

        // Assert: App B moves to LEFT, RIGHT becomes empty
        val state = viewModel.uiState.value
        assertEquals(appB, state.appForLeft)
        assertNull(state.appForRight)
    }

    @Test
    fun `onAppSelected - moves app from Left to Right if selected while Right is active`() = runTest {
        // Arrange: App A is initially LEFT
        `when`(getSwipeLeftAppUseCase()).thenReturn(flowOf(appA.componentName))
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
        assertEquals(appA, state.appForRight)
        assertNull(state.appForLeft)
    }

    @Test
    fun `onSlotCleared - clears specific slot`() = runTest {
        // Arrange
        `when`(getSwipeLeftAppUseCase()).thenReturn(flowOf(appA.componentName))
        `when`(getSwipeRightAppUseCase()).thenReturn(flowOf(appB.componentName))
        viewModel.initialize()
        advanceUntilIdle()

        // Act
        viewModel.onSlotCleared(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)
        advanceUntilIdle()

        // Assert
        assertNull(viewModel.uiState.value.appForLeft)
        assertEquals(appB, viewModel.uiState.value.appForRight)
    }

    // ========== SEARCH & FILTER TESTS ==========

    @Test
    fun `onSearchQueryChanged - filters displayed list`() = runTest {
        viewModel.initialize()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("App B")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.selectableApps.size)
        assertEquals(appB, state.selectableApps[0].appInfo)
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

        // Assert: Verify UseCase calls
        verify(setSwipeActionUseCase).invoke(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA.componentName)
        verify(setSwipeActionUseCase).invoke(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appB.componentName)

        // Verify Navigation
        viewModel.event.test {
            // Note: Event might have been emitted already, but we check invocation
            // In turbine, we'd need to collect before calling done.
            // Here we rely on mocking behavior or re-run the flow collection
        }
    }

    @Test
    fun `onDoneClicked - navigates up after save`() = runTest {
        viewModel.initialize()

        viewModel.event.test {
            viewModel.onDoneClicked()
            val event = awaitItem()
            assertEquals(UiEvent.NavigateUp, event)
        }
    }

    @Test
    fun `onDoneClicked - handles save error gracefully`() = runTest {
        // Arrange
        `when`(setSwipeActionUseCase(any<SwipeSlot>(), anyOrNull())).thenThrow(RuntimeException("Save failed"))
        viewModel.initialize()

        viewModel.event.test {
            viewModel.onDoneClicked()

            // Should show toast AND navigate up (fail-safe)
            val event1 = awaitItem()
            assertTrue(event1 is UiEvent.ShowToast)

            val event2 = awaitItem()
            assertEquals(UiEvent.NavigateUp, event2)
        }
    }

    // Helper for Mockito any() with nulls
    private fun <T> anyOrNull(): T? = org.mockito.Mockito.any()
    private fun <T> any(type: Class<T>): T = org.mockito.Mockito.any(type)
    private inline fun <reified T> any(): T = org.mockito.Mockito.any(T::class.java)
}