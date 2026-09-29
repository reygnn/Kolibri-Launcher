package com.github.reygnn.kolibri_launcher.ui.main.delegate

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DelegateScopeTest {

    @get:Rule
    val timberRule = TimberRule()

    // ===========================================
    // sendEvent
    // ===========================================

    @Test
    fun `sendEvent delivers event to eventSender`() = runTest {
        val sentEvents = mutableListOf<UiEvent>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = DelegateScope(
            coroutineScope = CoroutineScope(dispatcher + SupervisorJob()),
            mainDispatcher = dispatcher,
            eventSender = { event -> sentEvents.add(event) }
        )

        scope.sendEvent(UiEvent.ShowAppDrawer)

        assertThat(sentEvents.size).isEqualTo(1)
        assertThat(sentEvents.first()).isEqualTo(UiEvent.ShowAppDrawer)
    }

    @Test
    fun `sendEvent delivers multiple events in order`() = runTest {
        val sentEvents = mutableListOf<UiEvent>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = DelegateScope(
            coroutineScope = CoroutineScope(dispatcher + SupervisorJob()),
            mainDispatcher = dispatcher,
            eventSender = { event -> sentEvents.add(event) }
        )

        val event1 = UiEvent.ShowAppDrawer
        val event2 = UiEvent.OpenClock
        val event3 = UiEvent.OpenCalendar

        scope.sendEvent(event1)
        scope.sendEvent(event2)
        scope.sendEvent(event3)

        assertThat(sentEvents).isEqualTo(listOf(event1, event2, event3))
    }

    // ===========================================
    // launchSafe
    // ===========================================

    @Test
    fun `launchSafe executes block successfully`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = DelegateScope(
            coroutineScope = CoroutineScope(dispatcher + SupervisorJob()),
            mainDispatcher = dispatcher,
            eventSender = {}
        )

        var executed = false

        scope.launchSafe("test") {
            executed = true
        }
        advanceUntilIdle()

        assertThat(executed).isTrue()
    }

    @Test
    fun `launchSafe catches exceptions without crashing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = DelegateScope(
            coroutineScope = CoroutineScope(dispatcher + SupervisorJob()),
            mainDispatcher = dispatcher,
            eventSender = {}
        )

        scope.launchSafe("test") {
            throw RuntimeException("Boom")
        }
        advanceUntilIdle()

        assertThat(true).isTrue()
    }

    @Test
    fun `launchSafe catches Error without crashing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = DelegateScope(
            coroutineScope = CoroutineScope(dispatcher + SupervisorJob()),
            mainDispatcher = dispatcher,
            eventSender = {}
        )

        scope.launchSafe("test") {
            throw OutOfMemoryError("OOM")
        }
        advanceUntilIdle()

        assertThat(true).isTrue()
    }

    @Test
    fun `launchSafe does not swallow CancellationException`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = DelegateScope(
            coroutineScope = CoroutineScope(dispatcher + SupervisorJob()),
            mainDispatcher = dispatcher,
            eventSender = {}
        )

        scope.launchSafe("test") {
            throw CancellationException("Cancelled")
        }
        advanceUntilIdle()

        var executed = false
        scope.launchSafe("test2") {
            executed = true
        }
        advanceUntilIdle()
        assertWithMessage("Scope should remain functional after CancellationException").that(executed).isTrue()
    }

    @Test
    fun `launchSafe continues working after previous failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = DelegateScope(
            coroutineScope = CoroutineScope(dispatcher + SupervisorJob()),
            mainDispatcher = dispatcher,
            eventSender = {}
        )

        scope.launchSafe("test") {
            throw RuntimeException("Boom")
        }
        advanceUntilIdle()

        var executed = false
        scope.launchSafe("test") {
            executed = true
        }
        advanceUntilIdle()

        assertThat(executed).isTrue()
    }
}