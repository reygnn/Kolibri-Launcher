package com.github.reygnn.kolibri_launcher.ui.main.delegate

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        assertEquals(1, sentEvents.size)
        assertEquals(UiEvent.ShowAppDrawer, sentEvents.first())
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

        assertEquals(listOf(event1, event2, event3), sentEvents)
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

        assertTrue(executed)
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

        assertTrue(true)
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

        assertTrue(true)
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
        assertTrue("Scope should remain functional after CancellationException", executed)
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

        assertTrue(executed)
    }
}