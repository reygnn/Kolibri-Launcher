package com.github.reygnn.kolibri_launcher.ui.main.delegate

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.timeinfo.TimeBasedEvent
import com.github.reygnn.launcher.core.timeinfo.TimeBasedEventType
import com.github.reygnn.kolibri_launcher.domain.usecase.GetRecentAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HandleSwipeActionUseCase
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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

        assertThat(sentEvents.size).isEqualTo(1)
        assertThat(sentEvents.first()).isEqualTo(UiEvent.ShowRecentApps(recent))
    }

    @Test
    fun `onDoubleTap with events emits ShowTimeBasedEventsDialog with the snapshot`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val events = listOf(alarm())
            currentEvents = events
            val delegate = createDelegate()

            delegate.onDoubleTap()
            advanceUntilIdle()

            assertThat(sentEvents).isEqualTo(listOf(UiEvent.ShowTimeBasedEventsDialog(events)))
        }

    @Test
    fun `onDoubleTap with no events is a silent no-op`() = runTest(mainDispatcherRule.testDispatcher) {
        currentEvents = emptyList()
        val delegate = createDelegate()

        delegate.onDoubleTap()
        advanceUntilIdle()

        assertThat(sentEvents.isEmpty()).isTrue()
    }

    @Test
    fun `onDoubleTap fires on every tap while events exist`() = runTest(mainDispatcherRule.testDispatcher) {
        currentEvents = listOf(alarm())
        val delegate = createDelegate()

        delegate.onDoubleTap()
        advanceUntilIdle()
        delegate.onDoubleTap()
        advanceUntilIdle()

        assertThat(sentEvents.size).isEqualTo(2)
        assertThat(sentEvents.all { it is UiEvent.ShowTimeBasedEventsDialog }).isTrue()
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

            assertThat(sentEvents).isEqualTo(listOf(UiEvent.ShowTimeBasedEventsDialog(later)))
        }

    // ===========================================
    // FLING UP
    // ===========================================

    @Test
    fun `onFlingUp sends ShowAppDrawer event`() = runTest {
        val delegate = createDelegate()

        delegate.onFlingUp()
        advanceUntilIdle()

        assertThat(sentEvents.size).isEqualTo(1)
        assertThat(sentEvents.first()).isEqualTo(UiEvent.ShowAppDrawer)
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

        assertThat(sentEvents.size).isEqualTo(1)
        val event = sentEvents.first()
        assertThat(event).isInstanceOf(UiEvent.LaunchApp::class.java)
        assertThat((event as UiEvent.LaunchApp).app).isEqualTo(app)
    }

    @Test
    fun `onSwipeFromRightToLeft does nothing on NoAction`() = runTest {
        coEvery { handleSwipeActionUseCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT) } returns
                HandleSwipeActionUseCase.Result.NoAction

        val delegate = createDelegate()

        delegate.onSwipeFromRightToLeft()
        advanceUntilIdle()

        assertThat(sentEvents.isEmpty()).isTrue()
    }

    @Test
    fun `onSwipeFromRightToLeft toasts on AppNotInstalled result`() = runTest {
        coEvery { handleSwipeActionUseCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT) } returns
                HandleSwipeActionUseCase.Result.AppNotInstalled(
                    SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT,
                    "com.gone/com.gone.Main",
                )

        val delegate = createDelegate()

        delegate.onSwipeFromRightToLeft()
        advanceUntilIdle()

        assertThat(sentEvents.size).isEqualTo(1)
        val event = sentEvents.first()
        assertThat(event).isInstanceOf(UiEvent.ShowToast::class.java)
        assertThat((event as UiEvent.ShowToast).messageResId).isEqualTo(R.string.swipe_app_not_installed)
    }

    @Test
    fun `onSwipeFromRightToLeft does not crash on exception`() = runTest {
        coEvery { handleSwipeActionUseCase(any()) } throws RuntimeException("Boom")

        val delegate = createDelegate()

        delegate.onSwipeFromRightToLeft()
        advanceUntilIdle()

        assertThat(sentEvents.isEmpty()).isTrue()
    }

    @Test
    fun `onSwipeFromLeftToRight launches app on LaunchApp result`() = runTest {
        val app: AppInfo = mockk()
        coEvery { handleSwipeActionUseCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT) } returns
                HandleSwipeActionUseCase.Result.LaunchApp(app)

        val delegate = createDelegate()

        delegate.onSwipeFromLeftToRight()
        advanceUntilIdle()

        assertThat(sentEvents.size).isEqualTo(1)
        val event = sentEvents.first()
        assertThat(event).isInstanceOf(UiEvent.LaunchApp::class.java)
        assertThat((event as UiEvent.LaunchApp).app).isEqualTo(app)
    }

    @Test
    fun `onSwipeFromLeftToRight does nothing on NoAction`() = runTest {
        coEvery { handleSwipeActionUseCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT) } returns
                HandleSwipeActionUseCase.Result.NoAction

        val delegate = createDelegate()

        delegate.onSwipeFromLeftToRight()
        advanceUntilIdle()

        assertThat(sentEvents.isEmpty()).isTrue()
    }

    @Test
    fun `onSwipeFromLeftToRight toasts on AppNotInstalled result`() = runTest {
        coEvery { handleSwipeActionUseCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT) } returns
                HandleSwipeActionUseCase.Result.AppNotInstalled(
                    SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT,
                    "com.gone/com.gone.Main",
                )

        val delegate = createDelegate()

        delegate.onSwipeFromLeftToRight()
        advanceUntilIdle()

        assertThat(sentEvents.size).isEqualTo(1)
        val event = sentEvents.first()
        assertThat(event).isInstanceOf(UiEvent.ShowToast::class.java)
        assertThat((event as UiEvent.ShowToast).messageResId).isEqualTo(R.string.swipe_app_not_installed)
    }

    // ===========================================
    // LONG PRESS
    // ===========================================

    @Test
    fun `onLongPress sends ShowCustomizationOptions event`() = runTest {
        val delegate = createDelegate()

        delegate.onLongPress()
        advanceUntilIdle()

        assertThat(sentEvents.size).isEqualTo(1)
        assertThat(sentEvents.first()).isEqualTo(UiEvent.ShowCustomizationOptions)
    }

    // ===========================================
    // DOUBLE CLICK SHORTCUTS
    // ===========================================

    @Test
    fun `onTimeDoubleClick sends OpenClock event`() = runTest {
        val delegate = createDelegate()

        delegate.onTimeDoubleClick()
        advanceUntilIdle()

        assertThat(sentEvents.size).isEqualTo(1)
        assertThat(sentEvents.first()).isEqualTo(UiEvent.OpenClock)
    }

    @Test
    fun `onDateDoubleClick sends OpenCalendar event`() = runTest {
        val delegate = createDelegate()

        delegate.onDateDoubleClick()
        advanceUntilIdle()

        assertThat(sentEvents.size).isEqualTo(1)
        assertThat(sentEvents.first()).isEqualTo(UiEvent.OpenCalendar)
    }

    @Test
    fun `onBatteryDoubleClick sends OpenBatterySettings event`() = runTest {
        val delegate = createDelegate()

        delegate.onBatteryDoubleClick()
        advanceUntilIdle()

        assertThat(sentEvents.size).isEqualTo(1)
        assertThat(sentEvents.first()).isEqualTo(UiEvent.OpenBatterySettings)
    }
}
