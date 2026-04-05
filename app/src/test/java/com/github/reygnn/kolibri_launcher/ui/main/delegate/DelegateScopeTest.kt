package com.github.reygnn.kolibri_launcher.ui.main.delegate

import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import kotlinx.coroutines.CancellationException
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
class DelegateScopeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sentEvents = mutableListOf<UiEvent>()

    private lateinit var delegateScope: DelegateScope

    @Before
    fun setUp() {
        sentEvents.clear()
        delegateScope = DelegateScope(
            coroutineScope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob()),
            mainDispatcher = mainDispatcherRule.testDispatcher,
            eventSender = { event -> sentEvents.add(event) }
        )
    }

    // ===========================================
    // sendEvent
    // ===========================================

    @Test
    fun `sendEvent delivers event to eventSender`() = runTest {
        val event = UiEvent.ShowAppDrawer

        delegateScope.sendEvent(event)

        assertEquals(1, sentEvents.size)
        assertEquals(event, sentEvents.first())
    }

    @Test
    fun `sendEvent delivers multiple events in order`() = runTest {
        val event1 = UiEvent.ShowAppDrawer
        val event2 = UiEvent.OpenClock
        val event3 = UiEvent.OpenCalendar

        delegateScope.sendEvent(event1)
        delegateScope.sendEvent(event2)
        delegateScope.sendEvent(event3)

        assertEquals(listOf(event1, event2, event3), sentEvents)
    }

    // ===========================================
    // launchSafe
    // ===========================================

    @Test
    fun `launchSafe executes block successfully`() = runTest {
        var executed = false

        delegateScope.launchSafe("test") {
            executed = true
        }
        advanceUntilIdle()

        assertTrue(executed)
    }

    @Test
    fun `launchSafe catches exceptions without crashing`() = runTest {
        delegateScope.launchSafe("test") {
            throw RuntimeException("Boom")
        }
        advanceUntilIdle()

        assertTrue(true)
    }

    @Test
    fun `launchSafe catches Error without crashing`() = runTest {
        delegateScope.launchSafe("test") {
            throw OutOfMemoryError("OOM")
        }
        advanceUntilIdle()

        assertTrue(true)
    }

    @Test
    fun `launchSafe does not swallow CancellationException`() = runTest {
        delegateScope.launchSafe("test") {
            throw CancellationException("Cancelled")
        }
        advanceUntilIdle()

        var executed = false
        delegateScope.launchSafe("test2") {
            executed = true
        }
        advanceUntilIdle()
        assertTrue("Scope should remain functional after CancellationException", executed)
    }

    @Test
    fun `launchSafe continues working after previous failure`() = runTest {
        delegateScope.launchSafe("test") {
            throw RuntimeException("Boom")
        }
        advanceUntilIdle()

        var executed = false
        delegateScope.launchSafe("test") {
            executed = true
        }
        advanceUntilIdle()

        assertTrue(executed)
    }
}