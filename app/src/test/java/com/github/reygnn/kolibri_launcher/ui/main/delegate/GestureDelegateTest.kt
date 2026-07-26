package com.github.reygnn.kolibri_launcher.ui.main.delegate

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.usecase.GetDoubleTapClipboardSettingUseCase
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
    private lateinit var getDoubleTapClipboardSettingUseCase: GetDoubleTapClipboardSettingUseCase

    @Before
    fun setUp() {
        sentEvents.clear()

        handleSwipeActionUseCase = mockk(relaxed = true)
        getRecentAppsUseCase = mockk(relaxed = true)
        getDoubleTapClipboardSettingUseCase = mockk(relaxed = true)
    }

    private fun createDelegateScope() = DelegateScope(
        coroutineScope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob()),
        mainDispatcher = mainDispatcherRule.testDispatcher,
        eventSender = { event -> sentEvents.add(event) }
    )

    private fun createDelegate() = GestureDelegate(
        getRecentAppsUseCase = getRecentAppsUseCase,
        getDoubleTapClipboardSettingUseCase = getDoubleTapClipboardSettingUseCase,
        handleSwipeActionUseCase = handleSwipeActionUseCase,
        scope = createDelegateScope()
    )

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
    fun `onDoubleTap when enabled emits PerformClipboardAction`() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { getDoubleTapClipboardSettingUseCase() } returns true
        val delegate = createDelegate()

        delegate.onDoubleTap()
        advanceUntilIdle()

        assertEquals(listOf(UiEvent.PerformClipboardAction), sentEvents)
    }

    @Test
    fun `onDoubleTap when disabled never reads the clipboard`() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { getDoubleTapClipboardSettingUseCase() } returns false
        val delegate = createDelegate()

        delegate.onDoubleTap()
        advanceUntilIdle()

        // Points at the setting instead — crucially, no PerformClipboardAction.
        assertEquals(
            listOf(UiEvent.ShowToast(R.string.toast_enable_double_tap_clipboard)),
            sentEvents,
        )
    }

    @Test
    fun `onDoubleTap when disabled shows the hint only once`() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { getDoubleTapClipboardSettingUseCase() } returns false
        val delegate = createDelegate()

        delegate.onDoubleTap()
        advanceUntilIdle()
        delegate.onDoubleTap()
        advanceUntilIdle()

        assertEquals(1, sentEvents.size)
    }

    // ===========================================
    // GESTURE CONSUMPTION SNAPSHOT
    // ===========================================
    // HomeGestureLayout reads this synchronously to decide whether to suppress
    // the follow-on long-press and swipe. Reporting "consumed" while the
    // setting is off would silently eat the customization dialog for every
    // user, since the setting ships default-off.

    @Test
    fun `doubleTapConsumesGesture is primed at construction`() =
        runTest(mainDispatcherRule.testDispatcher) {
            coEvery { getDoubleTapClipboardSettingUseCase() } returns true
            val delegate = createDelegate()

            advanceUntilIdle()

            assertEquals(true, delegate.doubleTapConsumesGesture)
        }

    @Test
    fun `doubleTapConsumesGesture stays false while the setting is off`() =
        runTest(mainDispatcherRule.testDispatcher) {
            coEvery { getDoubleTapClipboardSettingUseCase() } returns false
            val delegate = createDelegate()
            advanceUntilIdle()

            delegate.onDoubleTap()
            advanceUntilIdle()

            assertEquals(false, delegate.doubleTapConsumesGesture)
        }

    @Test
    fun `doubleTapConsumesGesture follows a setting change on the next tap`() =
        runTest(mainDispatcherRule.testDispatcher) {
            coEvery { getDoubleTapClipboardSettingUseCase() } returns false
            val delegate = createDelegate()
            advanceUntilIdle()

            coEvery { getDoubleTapClipboardSettingUseCase() } returns true
            delegate.onDoubleTap()
            advanceUntilIdle()

            assertEquals(true, delegate.doubleTapConsumesGesture)
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
