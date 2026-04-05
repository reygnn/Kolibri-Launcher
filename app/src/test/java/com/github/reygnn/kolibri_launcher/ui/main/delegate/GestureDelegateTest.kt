package com.github.reygnn.kolibri_launcher.ui.main.delegate

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.usecase.HandleSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RequestLockUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RequestNotificationsUseCase
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GestureDelegateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private val sentEvents = mutableListOf<UiEvent>()

    private lateinit var requestLockUseCase: RequestLockUseCase
    private lateinit var requestNotificationsUseCase: RequestNotificationsUseCase
    private lateinit var handleSwipeActionUseCase: HandleSwipeActionUseCase

    @Before
    fun setUp() {
        sentEvents.clear()

        requestLockUseCase = mockk(relaxed = true)
        requestNotificationsUseCase = mockk(relaxed = true)
        handleSwipeActionUseCase = mockk(relaxed = true)
    }

    private fun createDelegateScope() = DelegateScope(
        coroutineScope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob()),
        mainDispatcher = mainDispatcherRule.testDispatcher,
        eventSender = { event -> sentEvents.add(event) }
    )

    private fun createDelegate() = GestureDelegate(
        requestLockUseCase = requestLockUseCase,
        requestNotificationsUseCase = requestNotificationsUseCase,
        handleSwipeActionUseCase = handleSwipeActionUseCase,
        scope = createDelegateScope()
    )

    // ===========================================
    // INITIAL STATE
    // ===========================================

    @Test
    fun `initial isLockingInProgress is false`() {
        val delegate = createDelegate()
        assertFalse(delegate.isLockingInProgress.value)
    }

    // ===========================================
    // FLING UP
    // ===========================================

    @Test
    fun `onFlingUp sends ShowAppDrawer event`() = runTest {
        val delegate = createDelegate()

        delegate.onFlingUp()
        advanceUntilIdle()

        assertEquals(1, sentEvents.size)
        assertEquals(UiEvent.ShowAppDrawer, sentEvents.first())
    }

    // ===========================================
    // FLING DOWN - NOTIFICATIONS
    // ===========================================

    @Test
    fun `onFlingDown does nothing on success`() = runTest {
        coEvery { requestNotificationsUseCase() } returns RequestNotificationsUseCase.Result.Success

        val delegate = createDelegate()

        delegate.onFlingDown()
        advanceUntilIdle()

        assertTrue(sentEvents.isEmpty())
    }

    @Test
    fun `onFlingDown shows accessibility dialog on ErrorAccessibility`() = runTest {
        coEvery { requestNotificationsUseCase() } returns RequestNotificationsUseCase.Result.ErrorAccessibility

        val delegate = createDelegate()

        delegate.onFlingDown()
        advanceUntilIdle()

        assertEquals(1, sentEvents.size)
        assertEquals(UiEvent.ShowAccessibilityDialog, sentEvents.first())
    }

    @Test
    fun `onFlingDown shows toast on ErrorDisabled first time only`() = runTest {
        coEvery { requestNotificationsUseCase() } returns RequestNotificationsUseCase.Result.ErrorDisabled

        val delegate = createDelegate()

        // First call: toast shown
        delegate.onFlingDown()
        advanceUntilIdle()

        assertEquals(1, sentEvents.size)
        assertTrue(sentEvents.first() is UiEvent.ShowToast)

        sentEvents.clear()

        // Second call: no toast
        delegate.onFlingDown()
        advanceUntilIdle()

        assertTrue(sentEvents.isEmpty())
    }

    @Test
    fun `onFlingDown shows generic error toast on ErrorGeneric`() = runTest {
        coEvery { requestNotificationsUseCase() } returns RequestNotificationsUseCase.Result.ErrorGeneric

        val delegate = createDelegate()

        delegate.onFlingDown()
        advanceUntilIdle()

        assertEquals(1, sentEvents.size)
        assertTrue(sentEvents.first() is UiEvent.ShowToast)
    }

    // ===========================================
    // SWIPE LEFT / RIGHT
    // ===========================================

    @Test
    fun `onSwipeFromRightToLeft launches app on LaunchApp result`() = runTest {
        val app: AppInfo = mockk()
        coEvery { handleSwipeActionUseCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT) } returns
                HandleSwipeActionUseCase.Result.LaunchApp(app)

        val delegate = createDelegate()

        delegate.onSwipeFromRightToLeft()
        advanceUntilIdle()

        assertEquals(1, sentEvents.size)
        val event = sentEvents.first()
        assertTrue(event is UiEvent.LaunchApp)
        assertEquals(app, (event as UiEvent.LaunchApp).app)
    }

    @Test
    fun `onSwipeFromRightToLeft does nothing on NoAction`() = runTest {
        coEvery { handleSwipeActionUseCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT) } returns
                HandleSwipeActionUseCase.Result.NoAction

        val delegate = createDelegate()

        delegate.onSwipeFromRightToLeft()
        advanceUntilIdle()

        assertTrue(sentEvents.isEmpty())
    }

    @Test
    fun `onSwipeFromRightToLeft does not crash on exception`() = runTest {
        coEvery { handleSwipeActionUseCase(any()) } throws RuntimeException("Boom")

        val delegate = createDelegate()

        delegate.onSwipeFromRightToLeft()
        advanceUntilIdle()

        assertTrue(sentEvents.isEmpty())
    }

    @Test
    fun `onSwipeFromLeftToRight launches app on LaunchApp result`() = runTest {
        val app: AppInfo = mockk()
        coEvery { handleSwipeActionUseCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT) } returns
                HandleSwipeActionUseCase.Result.LaunchApp(app)

        val delegate = createDelegate()

        delegate.onSwipeFromLeftToRight()
        advanceUntilIdle()

        assertEquals(1, sentEvents.size)
        val event = sentEvents.first()
        assertTrue(event is UiEvent.LaunchApp)
        assertEquals(app, (event as UiEvent.LaunchApp).app)
    }

    @Test
    fun `onSwipeFromLeftToRight does nothing on NoAction`() = runTest {
        coEvery { handleSwipeActionUseCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT) } returns
                HandleSwipeActionUseCase.Result.NoAction

        val delegate = createDelegate()

        delegate.onSwipeFromLeftToRight()
        advanceUntilIdle()

        assertTrue(sentEvents.isEmpty())
    }

    // ===========================================
    // LONG PRESS
    // ===========================================

    @Test
    fun `onLongPress sends ShowCustomizationOptions event`() = runTest {
        val delegate = createDelegate()

        delegate.onLongPress()
        advanceUntilIdle()

        assertEquals(1, sentEvents.size)
        assertEquals(UiEvent.ShowCustomizationOptions, sentEvents.first())
    }

    // ===========================================
    // DOUBLE TAP TO LOCK
    // ===========================================

    @Test
    fun `onDoubleTapToLock sets and resets isLockingInProgress on success`() = runTest {
        coEvery { requestLockUseCase() } returns RequestLockUseCase.Result.Success

        val delegate = createDelegate()

        delegate.onDoubleTapToLock()
        advanceUntilIdle()

        assertFalse(delegate.isLockingInProgress.value)
    }

    @Test
    fun `onDoubleTapToLock shows accessibility dialog on ErrorAccessibility`() = runTest {
        coEvery { requestLockUseCase() } returns RequestLockUseCase.Result.ErrorAccessibility

        val delegate = createDelegate()

        delegate.onDoubleTapToLock()
        advanceUntilIdle()

        assertEquals(1, sentEvents.size)
        assertEquals(UiEvent.ShowAccessibilityDialog, sentEvents.first())
    }

    @Test
    fun `onDoubleTapToLock shows toast on ErrorDisabled first time only`() = runTest {
        coEvery { requestLockUseCase() } returns RequestLockUseCase.Result.ErrorDisabled

        val delegate = createDelegate()

        // First call: toast
        delegate.onDoubleTapToLock()
        advanceUntilIdle()

        assertEquals(1, sentEvents.size)
        assertTrue(sentEvents.first() is UiEvent.ShowToast)

        sentEvents.clear()

        // Second call: no toast
        delegate.onDoubleTapToLock()
        advanceUntilIdle()

        assertTrue(sentEvents.isEmpty())
    }

    @Test
    fun `onDoubleTapToLock does nothing on ErrorGeneric`() = runTest {
        coEvery { requestLockUseCase() } returns RequestLockUseCase.Result.ErrorGeneric

        val delegate = createDelegate()

        delegate.onDoubleTapToLock()
        advanceUntilIdle()

        assertTrue(sentEvents.isEmpty())
    }

    // ===========================================
    // DOUBLE CLICK SHORTCUTS
    // ===========================================

    @Test
    fun `onTimeDoubleClick sends OpenClock event`() = runTest {
        val delegate = createDelegate()

        delegate.onTimeDoubleClick()
        advanceUntilIdle()

        assertEquals(1, sentEvents.size)
        assertEquals(UiEvent.OpenClock, sentEvents.first())
    }

    @Test
    fun `onDateDoubleClick sends OpenCalendar event`() = runTest {
        val delegate = createDelegate()

        delegate.onDateDoubleClick()
        advanceUntilIdle()

        assertEquals(1, sentEvents.size)
        assertEquals(UiEvent.OpenCalendar, sentEvents.first())
    }

    @Test
    fun `onBatteryDoubleClick sends OpenBatterySettings event`() = runTest {
        val delegate = createDelegate()

        delegate.onBatteryDoubleClick()
        advanceUntilIdle()

        assertEquals(1, sentEvents.size)
        assertEquals(UiEvent.OpenBatterySettings, sentEvents.first())
    }
}