package com.github.reygnn.kolibri_launcher.ui.base

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Testable subclass that exposes BaseViewModel's protected API.
 */
private class TestViewModel(
    dispatcher: CoroutineDispatcher
) : BaseViewModel<UiEvent>(dispatcher) {

    /** Expose sendEvent for testing */
    suspend fun testSendEvent(event: UiEvent) = sendEvent(event)

    /** Expose launchSafe for testing */
    fun testLaunchSafe(block: suspend CoroutineScope.() -> Unit) = launchSafe(block)

    /** Expose executeSafe for testing */
    fun <T> testExecuteSafe(
        onError: ((Throwable) -> Unit)? = null,
        block: () -> T
    ): T? = executeSafe(onError, block)

    /** Expose handleError for testing */
    fun testHandleError(throwable: Throwable, context: String) = handleError(throwable, context)

    /** Track if handleError was called */
    var lastHandledError: Throwable? = null
        private set

    var handleErrorCallCount = 0
        private set

    override fun handleError(throwable: Throwable, context: String) {
        lastHandledError = throwable
        handleErrorCallCount++
        super.handleError(throwable, context)
    }

    /** Expose onCleared for testing */
    fun testOnCleared() = onCleared()
}

@OptIn(ExperimentalCoroutinesApi::class)
class BaseViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private fun createViewModel() = TestViewModel(mainDispatcherRule.testDispatcher)

    // ===========================================
    // EVENT EMISSION
    // ===========================================

    @Test
    fun `sendEvent emits event to SharedFlow`() = runTest {
        val vm = createViewModel()

        vm.event.test {
            vm.testSendEvent(UiEvent.ShowAppDrawer)
            assertEquals(UiEvent.ShowAppDrawer, awaitItem())
        }
    }

    @Test
    fun `sendEvent emits multiple events in order`() = runTest {
        val vm = createViewModel()

        vm.event.test {
            vm.testSendEvent(UiEvent.ShowAppDrawer)
            vm.testSendEvent(UiEvent.OpenClock)
            vm.testSendEvent(UiEvent.OpenCalendar)

            assertEquals(UiEvent.ShowAppDrawer, awaitItem())
            assertEquals(UiEvent.OpenClock, awaitItem())
            assertEquals(UiEvent.OpenCalendar, awaitItem())
        }
    }

    @Test
    fun `event SharedFlow has no replay - late subscribers miss events`() = runTest {
        val vm = createViewModel()

        // Send event before anyone subscribes
        vm.testSendEvent(UiEvent.ShowAppDrawer)

        // Late subscriber should NOT receive the event
        vm.event.test {
            expectNoEvents()
        }
    }

    // ===========================================
    // LAUNCH SAFE - SUCCESS
    // ===========================================

    @Test
    fun `launchSafe executes block successfully`() = runTest {
        val vm = createViewModel()
        var executed = false

        vm.testLaunchSafe { executed = true }
        advanceUntilIdle()

        assertTrue(executed)
    }

    @Test
    fun `launchSafe runs on provided dispatcher`() = runTest {
        val vm = createViewModel()
        var threadName = ""

        vm.testLaunchSafe { threadName = Thread.currentThread().name }
        advanceUntilIdle()

        assertTrue(threadName.isNotEmpty())
    }

    // ===========================================
    // LAUNCH SAFE - EXCEPTION HANDLING
    // ===========================================

    @Test
    fun `launchSafe catches RuntimeException`() = runTest {
        val vm = createViewModel()

        vm.testLaunchSafe { throw RuntimeException("Boom") }
        advanceUntilIdle()

        assertNotNull(vm.lastHandledError)
        assertTrue(vm.lastHandledError is RuntimeException)
    }

    @Test
    fun `launchSafe catches IllegalStateException`() = runTest {
        val vm = createViewModel()

        vm.testLaunchSafe { throw IllegalStateException("Bad state") }
        advanceUntilIdle()

        assertTrue(vm.lastHandledError is IllegalStateException)
    }

    @Test
    fun `launchSafe catches OutOfMemoryError`() = runTest {
        val vm = createViewModel()

        vm.testLaunchSafe { throw OutOfMemoryError("Heap full") }
        advanceUntilIdle()

        assertTrue(vm.lastHandledError is OutOfMemoryError)
    }

    @Test
    fun `launchSafe catches StackOverflowError`() = runTest {
        val vm = createViewModel()

        vm.testLaunchSafe { throw StackOverflowError("Stack blown") }
        advanceUntilIdle()

        assertTrue(vm.lastHandledError is StackOverflowError)
    }

    @Test
    fun `launchSafe re-throws CancellationException`() = runTest {
        val vm = createViewModel()

        vm.testLaunchSafe { throw CancellationException("Cancelled") }
        advanceUntilIdle()

        // CancellationException is NOT handled by handleError - it's re-thrown
        assertNull(vm.lastHandledError)
    }

    @Test
    fun `launchSafe continues working after exception`() = runTest {
        val vm = createViewModel()

        // First: fails
        vm.testLaunchSafe { throw RuntimeException("Boom") }
        advanceUntilIdle()

        // Second: should still work
        var executed = false
        vm.testLaunchSafe { executed = true }
        advanceUntilIdle()

        assertTrue(executed)
    }

    @Test
    fun `launchSafe handles multiple sequential failures`() = runTest {
        val vm = createViewModel()

        repeat(10) {
            vm.testLaunchSafe { throw RuntimeException("Fail #$it") }
        }
        advanceUntilIdle()

        assertEquals(10, vm.handleErrorCallCount)

        // VM still works
        var executed = false
        vm.testLaunchSafe { executed = true }
        advanceUntilIdle()
        assertTrue(executed)
    }

    // ===========================================
    // LAUNCH SAFE - ERROR TOAST EMISSION
    // ===========================================

    @Test
    fun `launchSafe emits error toast on RuntimeException`() = runTest {
        val vm = createViewModel()

        val events = mutableListOf<UiEvent>()
        val job = launch(UnconfinedTestDispatcher()) { vm.event.collect { events.add(it) } }

        vm.testLaunchSafe { throw RuntimeException("Boom") }
        advanceUntilIdle()

        assertTrue(events.any { it is UiEvent.ShowToast && it.messageResId == R.string.error_generic })
        job.cancel()
    }

    @Test
    fun `launchSafe suppresses toast on OutOfMemoryError`() = runTest {
        val vm = createViewModel()

        val events = mutableListOf<UiEvent>()
        val job = launch(UnconfinedTestDispatcher()) { vm.event.collect { events.add(it) } }

        vm.testLaunchSafe { throw OutOfMemoryError("Heap full") }
        advanceUntilIdle()

        // OOM should NOT produce a toast (user can't do anything)
        assertFalse(events.any { it is UiEvent.ShowToast })
        job.cancel()
    }

    @Test
    fun `launchSafe suppresses toast on StackOverflowError`() = runTest {
        val vm = createViewModel()

        val events = mutableListOf<UiEvent>()
        val job = launch(UnconfinedTestDispatcher()) { vm.event.collect { events.add(it) } }

        vm.testLaunchSafe { throw StackOverflowError("Stack blown") }
        advanceUntilIdle()

        assertFalse(events.any { it is UiEvent.ShowToast })
        job.cancel()
    }

    @Test
    fun `launchSafe suppresses toast on CancellationException`() = runTest {
        val vm = createViewModel()

        val events = mutableListOf<UiEvent>()
        val job = launch(UnconfinedTestDispatcher()) { vm.event.collect { events.add(it) } }

        vm.testLaunchSafe { throw CancellationException("Cancelled") }
        advanceUntilIdle()

        assertFalse(events.any { it is UiEvent.ShowToast })
        job.cancel()
    }

    // ===========================================
    // EXECUTE SAFE
    // ===========================================

    @Test
    fun `executeSafe returns value on success`() {
        val vm = createViewModel()

        val result = vm.testExecuteSafe { 42 }

        assertEquals(42, result)
    }

    @Test
    fun `executeSafe returns string on success`() {
        val vm = createViewModel()

        val result = vm.testExecuteSafe { "hello" }

        assertEquals("hello", result)
    }

    @Test
    fun `executeSafe returns null on exception`() {
        val vm = createViewModel()

        val result = vm.testExecuteSafe<Int> { throw RuntimeException("Boom") }

        assertNull(result)
    }

    @Test
    fun `executeSafe returns null on Error`() {
        val vm = createViewModel()

        val result = vm.testExecuteSafe<Int> { throw OutOfMemoryError("OOM") }

        assertNull(result)
    }

    @Test
    fun `executeSafe re-throws CancellationException`() {
        val vm = createViewModel()

        var thrown = false
        try {
            vm.testExecuteSafe<Int> { throw CancellationException("Cancelled") }
        } catch (e: CancellationException) {
            thrown = true
        }

        assertTrue(thrown)
    }

    @Test
    fun `executeSafe calls custom onError handler`() {
        val vm = createViewModel()
        var capturedError: Throwable? = null

        vm.testExecuteSafe<Int>(
            onError = { capturedError = it }
        ) {
            throw IllegalArgumentException("Bad arg")
        }

        assertNotNull(capturedError)
        assertTrue(capturedError is IllegalArgumentException)
    }

    @Test
    fun `executeSafe survives failing onError handler`() {
        val vm = createViewModel()

        // onError itself throws - executeSafe should still return null without crashing
        val result = vm.testExecuteSafe<Int>(
            onError = { throw RuntimeException("Handler also broken") }
        ) {
            throw IllegalStateException("Original error")
        }

        assertNull(result)
    }

    @Test
    fun `executeSafe with null return value`() {
        val vm = createViewModel()

        val result = vm.testExecuteSafe<String?> { null }

        assertNull(result)
    }

    // ===========================================
    // HANDLE ERROR - CATEGORIZATION
    // ===========================================

    @Test
    fun `handleError processes RuntimeException`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.testHandleError(RuntimeException("Test"), "test-context")
        advanceUntilIdle()

        assertEquals(1, vm.handleErrorCallCount)
    }

    @Test
    fun `handleError processes OutOfMemoryError`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.testHandleError(OutOfMemoryError("OOM"), "test-context")
        advanceUntilIdle()

        assertTrue(vm.lastHandledError is OutOfMemoryError)
    }

    @Test
    fun `handleError processes StackOverflowError`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.testHandleError(StackOverflowError("Stack"), "test-context")
        advanceUntilIdle()

        assertTrue(vm.lastHandledError is StackOverflowError)
    }

    @Test
    fun `handleError processes CancellationException without toast`() = runTest {
        val vm = createViewModel()

        val events = mutableListOf<UiEvent>()
        val job = launch(UnconfinedTestDispatcher()) { vm.event.collect { events.add(it) } }

        vm.testHandleError(CancellationException("Cancelled"), "test-context")
        advanceUntilIdle()

        // CancellationException should be suppressed (no toast)
        assertFalse(events.any { it is UiEvent.ShowToast })
        job.cancel()
    }

    // ===========================================
    // ON CLEARED
    // ===========================================

    @Test
    fun `onCleared does not crash`() {
        val vm = createViewModel()

        // Should not throw
        vm.testOnCleared()
    }

    @Test
    fun `onCleared can be called multiple times`() {
        val vm = createViewModel()

        vm.testOnCleared()
        vm.testOnCleared()
        // No crash = success
    }

    // ===========================================
    // STRESS TESTS
    // ===========================================

    @Test
    fun `rapid event emission does not crash`() = runTest {
        val vm = createViewModel()

        val events = mutableListOf<UiEvent>()
        val job = launch(UnconfinedTestDispatcher()) { vm.event.collect { events.add(it) } }

        repeat(100) {
            vm.testSendEvent(UiEvent.ShowAppDrawer)
        }
        advanceUntilIdle()

        assertEquals(100, events.size)
        job.cancel()
    }

    @Test
    fun `interleaved launchSafe success and failure`() = runTest {
        val vm = createViewModel()
        var successCount = 0

        repeat(20) { i ->
            vm.testLaunchSafe {
                if (i % 2 == 0) {
                    successCount++
                } else {
                    throw RuntimeException("Fail #$i")
                }
            }
        }
        advanceUntilIdle()

        assertEquals(10, successCount)
        assertEquals(10, vm.handleErrorCallCount)
    }

    @Test
    fun `executeSafe and launchSafe can be used together`() = runTest {
        val vm = createViewModel()

        val syncResult = vm.testExecuteSafe { "sync value" }
        assertEquals("sync value", syncResult)

        var asyncResult = ""
        vm.testLaunchSafe { asyncResult = "async value" }
        advanceUntilIdle()

        assertEquals("async value", asyncResult)
    }

    @Test
    fun `VM remains functional after mixed error types`() = runTest {
        val vm = createViewModel()

        vm.testLaunchSafe { throw RuntimeException("Runtime") }
        vm.testLaunchSafe { throw IllegalStateException("State") }
        vm.testLaunchSafe { throw OutOfMemoryError("OOM") }
        vm.testLaunchSafe { throw StackOverflowError("Stack") }
        vm.testLaunchSafe { throw NullPointerException("NPE") }
        advanceUntilIdle()

        assertEquals(5, vm.handleErrorCallCount)

        // VM still works
        var executed = false
        vm.testLaunchSafe { executed = true }
        advanceUntilIdle()
        assertTrue(executed)
    }
}