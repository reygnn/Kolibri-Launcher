package com.github.reygnn.kolibri_launcher.ui.main.delegate

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveDoubleTapClipboardSettingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetRecentAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HandleSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
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
    private lateinit var observeDoubleTapClipboardSettingUseCase: ObserveDoubleTapClipboardSettingUseCase

    @Before
    fun setUp() {
        sentEvents.clear()

        handleSwipeActionUseCase = mockk(relaxed = true)
        getRecentAppsUseCase = mockk(relaxed = true)
        observeDoubleTapClipboardSettingUseCase = mockk(relaxed = true)
        // Default: setting off. Tests that care override this.
        every { observeDoubleTapClipboardSettingUseCase() } returns flowOf(false)
    }

    private fun createDelegateScope() = DelegateScope(
        coroutineScope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob()),
        mainDispatcher = mainDispatcherRule.testDispatcher,
        eventSender = { event -> sentEvents.add(event) }
    )

    private fun createDelegate() = GestureDelegate(
        getRecentAppsUseCase = getRecentAppsUseCase,
        observeDoubleTapClipboardSettingUseCase = observeDoubleTapClipboardSettingUseCase,
        handleSwipeActionUseCase = handleSwipeActionUseCase,
        scope = createDelegateScope()
    )

    /** Builds a delegate whose clipboard setting reads back as [enabled]. */
    private fun delegateWithClipboard(enabled: Boolean): GestureDelegate {
        every { observeDoubleTapClipboardSettingUseCase() } returns flowOf(enabled)
        return createDelegate()
    }

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
        val delegate = delegateWithClipboard(enabled = true)

        delegate.onDoubleTap()
        advanceUntilIdle()

        assertEquals(listOf(UiEvent.PerformClipboardAction), sentEvents)
    }

    @Test
    fun `onDoubleTap when disabled points at the setting, never the clipboard`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val delegate = delegateWithClipboard(enabled = false)

            delegate.onDoubleTap()
            advanceUntilIdle()

            // The hint — crucially, no PerformClipboardAction.
            assertEquals(
                listOf(UiEvent.ShowToast(R.string.toast_enable_double_tap_clipboard)),
                sentEvents,
            )
        }

    @Test
    fun `onDoubleTap when disabled shows the hint only once`() = runTest(mainDispatcherRule.testDispatcher) {
        val delegate = delegateWithClipboard(enabled = false)

        delegate.onDoubleTap()
        advanceUntilIdle()
        delegate.onDoubleTap()
        advanceUntilIdle()

        assertEquals(1, sentEvents.size)
    }

    @Test
    fun `onDoubleTap when enabled fires on every tap`() = runTest(mainDispatcherRule.testDispatcher) {
        // The one-shot flag guards the hint, not the action. An enabled setting
        // must forward the clipboard on every tap, or the second gesture would
        // silently do nothing.
        val delegate = delegateWithClipboard(enabled = true)

        delegate.onDoubleTap()
        advanceUntilIdle()
        delegate.onDoubleTap()
        advanceUntilIdle()

        assertEquals(
            listOf(UiEvent.PerformClipboardAction, UiEvent.PerformClipboardAction),
            sentEvents,
        )
    }

    @Test
    fun `onDoubleTap enabled after the hint was shown fires the clipboard action`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // User taps while off (sees the hint), flips the setting on, taps
            // again. The setting is re-read freshly each time, and the one-shot
            // hint flag must not suppress the now-enabled action.
            every { observeDoubleTapClipboardSettingUseCase() } returns flowOf(false)
            val delegate = createDelegate()

            delegate.onDoubleTap()
            advanceUntilIdle()

            every { observeDoubleTapClipboardSettingUseCase() } returns flowOf(true)
            delegate.onDoubleTap()
            advanceUntilIdle()

            assertEquals(
                listOf(
                    UiEvent.ShowToast(R.string.toast_enable_double_tap_clipboard),
                    UiEvent.PerformClipboardAction,
                ),
                sentEvents,
            )
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
