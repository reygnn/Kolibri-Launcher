package com.github.reygnn.kolibri_launcher.ui.main.delegate

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.usecase.HandleSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RequestLockUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RequestNotificationsUseCase
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import com.github.reygnn.kolibri_launcher.core.AppConstants
import kotlinx.coroutines.test.advanceTimeBy
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

    @Test
    fun `initial showLockOverlay is false`() {
        val delegate = createDelegate()
        assertFalse(delegate.showLockOverlay.value)
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
    // DOUBLE TAP TO LOCK — onDoubleTapToLock
    // ===========================================
    //
    // `onDoubleTapToLock` is the View-side preparer: it sets the
    // gating flags and emits the lockPaintTrigger so HomeFragment
    // can run its OneShotPreDrawListener. The actual lock request
    // and its state-management live in `executeLockAfterOverlayPaint`,
    // tested in the next section. The two are deliberately split to
    // sync the lock IPC with the overlay's first frame — see the
    // KDoc on `onDoubleTapToLock`.

    @Test
    fun `onDoubleTapToLock sets isLockingInProgress before the trigger emits`() = runTest {
        val delegate = createDelegate()

        delegate.onDoubleTapToLock()
        assertTrue(
            "isLockingInProgress must be set before any subsequent gesture is checked",
            delegate.isLockingInProgress.value,
        )
    }

    @Test
    fun `onDoubleTapToLock sets showLockOverlay before the trigger emits`() = runTest {
        val delegate = createDelegate()

        delegate.onDoubleTapToLock()
        assertTrue(
            "showLockOverlay must be set before the trigger collector reads it",
            delegate.showLockOverlay.value,
        )
    }

    @Test
    fun `onDoubleTapToLock emits lockPaintTrigger`() = runTest {
        val delegate = createDelegate()

        delegate.lockPaintTrigger.test {
            delegate.onDoubleTapToLock()
            awaitItem()
        }
    }

    /**
     * Sequencing assertion. If a future refactor accidentally re-
     * merges `onDoubleTapToLock` and `executeLockAfterOverlayPaint`
     * (calling the use case directly from the View-side preparer),
     * the Pre-Draw frame-pipeline guarantee is silently lost — the
     * lock IPC would race the overlay's frame again. Without this
     * test all the per-flag and per-event tests still pass. Pin the
     * separation explicitly: `onDoubleTapToLock` MUST NOT call the
     * use case.
     */
    @Test
    fun `onDoubleTapToLock does NOT invoke the use case directly`() = runTest {
        val delegate = createDelegate()

        delegate.onDoubleTapToLock()
        advanceUntilIdle()

        coVerify(exactly = 0) { requestLockUseCase() }
    }

    // ===========================================
    // DOUBLE TAP TO LOCK — executeLockAfterOverlayPaint
    // ===========================================
    //
    // The use-case half: invokes RequestLockUseCase, runs the
    // gesture-block delay, the watchdog, and the per-error-branch
    // resets. Each test calls onDoubleTapToLock first to put the
    // gating flags into the state the production sequence
    // establishes before the Pre-Draw listener fires.

    @Test
    fun `executeLockAfterOverlayPaint resets isLockingInProgress on success`() = runTest {
        coEvery { requestLockUseCase() } returns RequestLockUseCase.Result.Success

        val delegate = createDelegate()

        delegate.onDoubleTapToLock()
        delegate.executeLockAfterOverlayPaint()
        advanceUntilIdle()

        assertFalse(delegate.isLockingInProgress.value)
    }

    /**
     * Pins the lock-block delay duration in virtual time. Without this,
     * a regression that sets the delay to `0` (or removes it entirely)
     * would still pass the `resets isLockingInProgress` test above —
     * that test only checks the *final* state after `advanceUntilIdle()`.
     * The convention is documented in `TESTING_CONVENTIONS.kt` →
     * "TIME-BASED ASSERTIONS"; this test is one of the per-site
     * applications.
     */
    @Test
    fun `executeLockAfterOverlayPaint holds isLockingInProgress for LOCK_GESTURE_BLOCK_DURATION_MS — TIME-PIN`() =
        runTest {
            coEvery { requestLockUseCase() } returns RequestLockUseCase.Result.Success

            val delegate = createDelegate()

            // onDoubleTapToLock sets isLockingInProgress true. The
            // UnconfinedTestDispatcher runs the launched coroutine
            // synchronously up to the first suspending point.
            delegate.onDoubleTapToLock()
            assertTrue(
                "Precondition: isLockingInProgress set by onDoubleTapToLock",
                delegate.isLockingInProgress.value,
            )

            // Now run executeLockAfterOverlayPaint which holds the
            // flag through the gesture-block delay.
            delegate.executeLockAfterOverlayPaint()

            // Just before the boundary the gate must still be closed.
            advanceTimeBy(AppConstants.LOCK_GESTURE_BLOCK_DURATION_MS - 1)
            assertTrue(
                "Gate must still be closed at duration - 1 ms",
                delegate.isLockingInProgress.value,
            )

            // Crossing the boundary releases the gate.
            advanceTimeBy(2)
            assertFalse(
                "Gate must be released after duration ms have elapsed",
                delegate.isLockingInProgress.value,
            )
        }

    /**
     * The overlay must NOT be reset when [_isLockingInProgress] is
     * reset after the gesture-block delay. The overlay's lifetime
     * deliberately extends past the gesture-block one — see
     * `onDoubleTapToLock`'s KDoc, "Two flags for two concerns".
     */
    @Test
    fun `executeLockAfterOverlayPaint keeps showLockOverlay set after gesture-block delay`() =
        runTest {
            coEvery { requestLockUseCase() } returns RequestLockUseCase.Result.Success

            val delegate = createDelegate()

            delegate.onDoubleTapToLock()
            delegate.executeLockAfterOverlayPaint()

            // Cross the gesture-block boundary; isLockingInProgress
            // releases here, but the overlay must persist.
            advanceTimeBy(AppConstants.LOCK_GESTURE_BLOCK_DURATION_MS + 1)
            assertFalse(
                "isLockingInProgress released after block delay",
                delegate.isLockingInProgress.value,
            )
            assertTrue(
                "showLockOverlay must persist past the block delay",
                delegate.showLockOverlay.value,
            )
        }

    /**
     * Pins the watchdog duration in virtual time. The watchdog only
     * matters on the abnormal path where `HomeFragment.onPause` never
     * fires — it bounds the worst case so the user doesn't sit on a
     * black screen until the next foreground change. Without this
     * test a regression that drops the watchdog (or shortens it to
     * zero) would still pass `keeps showLockOverlay set after
     * gesture-block delay` because that test only crosses the
     * gesture-block boundary, not the watchdog one.
     */
    @Test
    fun `executeLockAfterOverlayPaint dismisses showLockOverlay after watchdog — TIME-PIN`() =
        runTest {
            coEvery { requestLockUseCase() } returns RequestLockUseCase.Result.Success

            val delegate = createDelegate()

            delegate.onDoubleTapToLock()
            delegate.executeLockAfterOverlayPaint()

            // Skip past the gesture-block delay; we're now in the
            // watchdog window.
            advanceTimeBy(AppConstants.LOCK_GESTURE_BLOCK_DURATION_MS)

            // Just before the watchdog boundary the overlay must still
            // be on screen.
            advanceTimeBy(AppConstants.LOCK_OVERLAY_WATCHDOG_DURATION_MS - 1)
            assertTrue(
                "Watchdog must not fire at duration - 1 ms",
                delegate.showLockOverlay.value,
            )

            // Crossing the boundary dismisses the overlay.
            advanceTimeBy(2)
            assertFalse(
                "Watchdog must dismiss the overlay after duration ms have elapsed",
                delegate.showLockOverlay.value,
            )
        }

    @Test
    fun `executeLockAfterOverlayPaint resets showLockOverlay synchronously on ErrorAccessibility`() =
        runTest {
            coEvery { requestLockUseCase() } returns RequestLockUseCase.Result.ErrorAccessibility

            val delegate = createDelegate()

            delegate.onDoubleTapToLock()
            delegate.executeLockAfterOverlayPaint()
            advanceUntilIdle()

            assertFalse(delegate.showLockOverlay.value)
        }

    @Test
    fun `executeLockAfterOverlayPaint resets showLockOverlay synchronously on ErrorDisabled`() =
        runTest {
            coEvery { requestLockUseCase() } returns RequestLockUseCase.Result.ErrorDisabled

            val delegate = createDelegate()

            delegate.onDoubleTapToLock()
            delegate.executeLockAfterOverlayPaint()
            advanceUntilIdle()

            assertFalse(delegate.showLockOverlay.value)
        }

    @Test
    fun `executeLockAfterOverlayPaint resets showLockOverlay synchronously on ErrorGeneric`() =
        runTest {
            coEvery { requestLockUseCase() } returns RequestLockUseCase.Result.ErrorGeneric

            val delegate = createDelegate()

            delegate.onDoubleTapToLock()
            delegate.executeLockAfterOverlayPaint()
            advanceUntilIdle()

            assertFalse(delegate.showLockOverlay.value)
        }

    @Test
    fun `dismissLockOverlay sets showLockOverlay to false`() = runTest {
        val delegate = createDelegate()

        delegate.onDoubleTapToLock()
        assertTrue(
            "Precondition: overlay must be set before dismissal",
            delegate.showLockOverlay.value,
        )

        delegate.dismissLockOverlay()
        assertFalse(delegate.showLockOverlay.value)
    }

    @Test
    fun `executeLockAfterOverlayPaint shows accessibility dialog on ErrorAccessibility`() =
        runTest {
            coEvery { requestLockUseCase() } returns RequestLockUseCase.Result.ErrorAccessibility

            val delegate = createDelegate()

            delegate.onDoubleTapToLock()
            delegate.executeLockAfterOverlayPaint()
            advanceUntilIdle()

            assertEquals(1, sentEvents.size)
            assertEquals(UiEvent.ShowAccessibilityDialog, sentEvents.first())
        }

    @Test
    fun `executeLockAfterOverlayPaint shows toast on ErrorDisabled first time only`() = runTest {
        coEvery { requestLockUseCase() } returns RequestLockUseCase.Result.ErrorDisabled

        val delegate = createDelegate()

        // First call: toast
        delegate.onDoubleTapToLock()
        delegate.executeLockAfterOverlayPaint()
        advanceUntilIdle()

        assertEquals(1, sentEvents.size)
        assertTrue(sentEvents.first() is UiEvent.ShowToast)

        sentEvents.clear()

        // Second call: no toast
        delegate.onDoubleTapToLock()
        delegate.executeLockAfterOverlayPaint()
        advanceUntilIdle()

        assertTrue(sentEvents.isEmpty())
    }

    @Test
    fun `executeLockAfterOverlayPaint does nothing on ErrorGeneric`() = runTest {
        coEvery { requestLockUseCase() } returns RequestLockUseCase.Result.ErrorGeneric

        val delegate = createDelegate()

        delegate.onDoubleTapToLock()
        delegate.executeLockAfterOverlayPaint()
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