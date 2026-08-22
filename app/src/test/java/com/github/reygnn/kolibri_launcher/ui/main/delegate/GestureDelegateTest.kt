package com.github.reygnn.kolibri_launcher.ui.main.delegate

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEventType
import com.github.reygnn.kolibri_launcher.domain.usecase.GetRecentAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HandleSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private lateinit var handleSwipeActionUseCase: HandleSwipeActionUseCase
    private lateinit var getRecentAppsUseCase: GetRecentAppsUseCase

    // Snapshot the double-tap gesture reads (mirrors ClockDelegate.timeBasedEvents).
    private var currentEvents: List<TimeBasedEvent> = emptyList()

    @Before
    fun setUp() {
        sentEvents.clear()

        handleSwipeActionUseCase = mockk(relaxed = true)
        getRecentAppsUseCase = mockk(relaxed = true)
        currentEvents = emptyList()
    }

    private fun createDelegateScope() = DelegateScope(
        coroutineScope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob()),
        mainDispatcher = mainDispatcherRule.testDispatcher,
        eventSender = { event -> sentEvents.add(event) }
    )

    private fun createDelegate() = GestureDelegate(
        getRecentAppsUseCase = getRecentAppsUseCase,
        currentTimeBasedEvents = { currentEvents },
        handleSwipeActionUseCase = handleSwipeActionUseCase,
        scope = createDelegateScope()
    )

    private fun alarm(title: String = "Alarm") =
        TimeBasedEvent(triggerTimeMillis = 0L, title = title, type = TimeBasedEventType.ALARM)

    @Test
    fun `onSwipeDown emits ShowRecentApps with the recent apps`() = runTest(mainDispatcherRule.testDispatcher) {
        val recent = listOf(AppInfo("A", "A", "pkg.a", "cls.a"))
        coEvery { getRecentAppsUseCase() } returns recent
        val delegate = createDelegate()

        delegate.onSwipeDown()
        advanceUntilIdle()

        assertEquals(1, sentEvents.size)
        assertEquals(UiEvent.ShowRecentApps(recent), sentEvents.first())
    }

    @Test
    fun `onDoubleTap with events emits ShowTimeBasedEventsDialog with the snapshot`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val events = listOf(alarm())
            currentEvents = events
            val delegate = createDelegate()

            delegate.onDoubleTap()
            advanceUntilIdle()

            assertEquals(listOf(UiEvent.ShowTimeBasedEventsDialog(events)), sentEvents)
        }

    @Test
    fun `onDoubleTap with no events is a silent no-op`() = runTest(mainDispatcherRule.testDispatcher) {
        currentEvents = emptyList()
        val delegate = createDelegate()

        delegate.onDoubleTap()
        advanceUntilIdle()

        assertTrue(sentEvents.isEmpty())
    }

    @Test
    fun `onDoubleTap fires on every tap while events exist`() = runTest(mainDispatcherRule.testDispatcher) {
        currentEvents = listOf(alarm())
        val delegate = createDelegate()

        delegate.onDoubleTap()
        advanceUntilIdle()
        delegate.onDoubleTap()
        advanceUntilIdle()

        assertEquals(2, sentEvents.size)
        assertTrue(sentEvents.all { it is UiEvent.ShowTimeBasedEventsDialog })
    }

    @Test
    fun `onDoubleTap reads the snapshot at tap-time, not construction-time`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Pins the documented freshness contract: the delegate is built once per
            // session but must reflect the CURRENT events (alarms fire / new ones
            // appear after construction). Build while empty, change the snapshot,
            // then tap — the dialog must carry the NEW list. A refactor that captured
            // the list at construction would emit the (empty) construction-time value
            // and fail here.
            currentEvents = emptyList()
            val delegate = createDelegate()

            val later = listOf(alarm("Later"))
            currentEvents = later

            delegate.onDoubleTap()
            advanceUntilIdle()

            assertEquals(listOf(UiEvent.ShowTimeBasedEventsDialog(later)), sentEvents)
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
