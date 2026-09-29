package com.github.reygnn.kolibri_launcher.ui.base

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.common.ui.base.BaseViewModel
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.launcher.core.testing.recordEmissions
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Testable subclass that exposes BaseViewModel's protected API.
 */
private class TestViewModel(
    dispatcher: CoroutineDispatcher
) : BaseViewModel<UiEvent>(dispatcher) {

    override val errorEvent = UiEvent.ShowToast(R.string.error_generic)

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

/**
 * Variant that does NOT override errorEvent — pins the default-null
 * path used by ViewModels whose event type has no toast variant
 * (e.g. OnboardingViewModel<OnboardingEvent>).
 */
private class TestViewModelWithoutErrorEvent(
    dispatcher: CoroutineDispatcher
) : BaseViewModel<UiEvent>(dispatcher) {
    fun testLaunchSafe(block: suspend CoroutineScope.() -> Unit) = launchSafe(block)
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
            assertThat(awaitItem()).isEqualTo(UiEvent.ShowAppDrawer)
        }
    }

    @Test
    fun `sendEvent emits multiple events in order`() = runTest {
        val vm = createViewModel()

        vm.event.test {
            vm.testSendEvent(UiEvent.ShowAppDrawer)
            vm.testSendEvent(UiEvent.OpenClock)
            vm.testSendEvent(UiEvent.OpenCalendar)

            assertThat(awaitItem()).isEqualTo(UiEvent.ShowAppDrawer)
            assertThat(awaitItem()).isEqualTo(UiEvent.OpenClock)
            assertThat(awaitItem()).isEqualTo(UiEvent.OpenCalendar)
        }
    }

    @Test
    fun `event emitted with no active collector is buffered for the next subscriber`() = runTest {
        val vm = createViewModel()

        // Send an event before anyone subscribes — models a nav/toast event
        // fired right after a suspending use-case while the STARTED-scoped
        // collector is torn down during a config change.
        vm.testSendEvent(UiEvent.ShowAppDrawer)

        // The Channel buffers it, so a late subscriber still receives it
        // instead of silently missing it (AUDIT-3 #7). A replay=0
        // MutableSharedFlow dropped it here.
        vm.event.test {
            assertThat(awaitItem()).isEqualTo(UiEvent.ShowAppDrawer)
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

        assertThat(executed).isTrue()
    }

    @Test
    fun `launchSafe runs on provided dispatcher`() = runTest {
        val vm = createViewModel()
        var threadName = ""

        vm.testLaunchSafe { threadName = Thread.currentThread().name }
        advanceUntilIdle()

        assertThat(threadName.isNotEmpty()).isTrue()
    }

    // ===========================================
    // LAUNCH SAFE - EXCEPTION HANDLING
    // ===========================================

    @Test
    fun `launchSafe catches RuntimeException`() = runTest {
        val vm = createViewModel()

        vm.testLaunchSafe { throw RuntimeException("Boom") }
        advanceUntilIdle()

        assertThat(vm.lastHandledError).isNotNull()
        assertThat(vm.lastHandledError).isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `launchSafe catches IllegalStateException`() = runTest {
        val vm = createViewModel()

        vm.testLaunchSafe { throw IllegalStateException("Bad state") }
        advanceUntilIdle()

        assertThat(vm.lastHandledError).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `launchSafe catches OutOfMemoryError`() = runTest {
        val vm = createViewModel()

        vm.testLaunchSafe { throw OutOfMemoryError("Heap full") }
        advanceUntilIdle()

        assertThat(vm.lastHandledError).isInstanceOf(OutOfMemoryError::class.java)
    }

    @Test
    fun `launchSafe catches StackOverflowError`() = runTest {
        val vm = createViewModel()

        vm.testLaunchSafe { throw StackOverflowError("Stack blown") }
        advanceUntilIdle()

        assertThat(vm.lastHandledError).isInstanceOf(StackOverflowError::class.java)
    }

    @Test
    fun `launchSafe re-throws CancellationException`() = runTest {
        val vm = createViewModel()

        vm.testLaunchSafe { throw CancellationException("Cancelled") }
        advanceUntilIdle()

        // CancellationException is NOT handled by handleError - it's re-thrown
        assertThat(vm.lastHandledError).isNull()
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

        assertThat(executed).isTrue()
    }

    @Test
    fun `launchSafe handles multiple sequential failures`() = runTest {
        val vm = createViewModel()

        repeat(10) {
            vm.testLaunchSafe { throw RuntimeException("Fail #$it") }
        }
        advanceUntilIdle()

        assertThat(vm.handleErrorCallCount).isEqualTo(10)

        // VM still works
        var executed = false
        vm.testLaunchSafe { executed = true }
        advanceUntilIdle()
        assertThat(executed).isTrue()
    }

    // ===========================================
    // LAUNCH SAFE - ERROR TOAST EMISSION
    // ===========================================

    @Test
    fun `launchSafe emits error toast on RuntimeException`() = runTest {
        val vm = createViewModel()

        val events = mutableListOf<UiEvent>()
        val job = recordEmissions(vm.event, into = events)

        vm.testLaunchSafe { throw RuntimeException("Boom") }
        advanceUntilIdle()

        assertThat(events.any { it is UiEvent.ShowToast && it.messageResId == R.string.error_generic }).isTrue()
        job.cancel()
    }

    @Test
    fun `launchSafe suppresses toast on OutOfMemoryError`() = runTest {
        val vm = createViewModel()

        val events = mutableListOf<UiEvent>()
        val job = recordEmissions(vm.event, into = events)

        vm.testLaunchSafe { throw OutOfMemoryError("Heap full") }
        advanceUntilIdle()

        // OOM should NOT produce a toast (user can't do anything)
        assertThat(events.any { it is UiEvent.ShowToast }).isFalse()
        job.cancel()
    }

    @Test
    fun `launchSafe suppresses toast on StackOverflowError`() = runTest {
        val vm = createViewModel()

        val events = mutableListOf<UiEvent>()
        val job = recordEmissions(vm.event, into = events)

        vm.testLaunchSafe { throw StackOverflowError("Stack blown") }
        advanceUntilIdle()

        assertThat(events.any { it is UiEvent.ShowToast }).isFalse()
        job.cancel()
    }

    @Test
    fun `launchSafe suppresses toast on CancellationException`() = runTest {
        val vm = createViewModel()

        val events = mutableListOf<UiEvent>()
        val job = recordEmissions(vm.event, into = events)

        vm.testLaunchSafe { throw CancellationException("Cancelled") }
        advanceUntilIdle()

        assertThat(events.any { it is UiEvent.ShowToast }).isFalse()
        job.cancel()
    }

    @Test
    fun `default null errorEvent emits no toast on RuntimeException`() = runTest {
        val vm = TestViewModelWithoutErrorEvent(mainDispatcherRule.testDispatcher)

        val events = mutableListOf<UiEvent>()
        val job = recordEmissions(vm.event, into = events)

        vm.testLaunchSafe { throw RuntimeException("Boom") }
        advanceUntilIdle()

        assertThat(events.any { it is UiEvent.ShowToast }).isFalse()
        job.cancel()
    }

    // ===========================================
    // EXECUTE SAFE
    // ===========================================

    @Test
    fun `executeSafe returns value on success`() {
        val vm = createViewModel()

        val result = vm.testExecuteSafe { 42 }

        assertThat(result).isEqualTo(42)
    }

    @Test
    fun `executeSafe returns string on success`() {
        val vm = createViewModel()

        val result = vm.testExecuteSafe { "hello" }

        assertThat(result).isEqualTo("hello")
    }

    @Test
    fun `executeSafe returns null on exception`() {
        val vm = createViewModel()

        val result = vm.testExecuteSafe<Int> { throw RuntimeException("Boom") }

        assertThat(result).isNull()
    }

    @Test
    fun `executeSafe returns null on Error`() {
        val vm = createViewModel()

        val result = vm.testExecuteSafe<Int> { throw OutOfMemoryError("OOM") }

        assertThat(result).isNull()
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

        assertThat(thrown).isTrue()
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

        assertThat(capturedError).isNotNull()
        assertThat(capturedError).isInstanceOf(IllegalArgumentException::class.java)
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

        assertThat(result).isNull()
    }

    @Test
    fun `executeSafe with null return value`() {
        val vm = createViewModel()

        val result = vm.testExecuteSafe<String?> { null }

        assertThat(result).isNull()
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

        assertThat(vm.handleErrorCallCount).isEqualTo(1)
    }

    @Test
    fun `handleError processes OutOfMemoryError`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.testHandleError(OutOfMemoryError("OOM"), "test-context")
        advanceUntilIdle()

        assertThat(vm.lastHandledError).isInstanceOf(OutOfMemoryError::class.java)
    }

    @Test
    fun `handleError processes StackOverflowError`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.testHandleError(StackOverflowError("Stack"), "test-context")
        advanceUntilIdle()

        assertThat(vm.lastHandledError).isInstanceOf(StackOverflowError::class.java)
    }

    @Test
    fun `handleError processes CancellationException without toast`() = runTest {
        val vm = createViewModel()

        val events = mutableListOf<UiEvent>()
        val job = recordEmissions(vm.event, into = events)

        vm.testHandleError(CancellationException("Cancelled"), "test-context")
        advanceUntilIdle()

        // CancellationException should be suppressed (no toast)
        assertThat(events.any { it is UiEvent.ShowToast }).isFalse()
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
        val job = recordEmissions(vm.event, into = events)

        repeat(100) {
            vm.testSendEvent(UiEvent.ShowAppDrawer)
        }
        advanceUntilIdle()

        assertThat(events.size).isEqualTo(100)
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

        assertThat(successCount).isEqualTo(10)
        assertThat(vm.handleErrorCallCount).isEqualTo(10)
    }

    @Test
    fun `executeSafe and launchSafe can be used together`() = runTest {
        val vm = createViewModel()

        val syncResult = vm.testExecuteSafe { "sync value" }
        assertThat(syncResult).isEqualTo("sync value")

        var asyncResult = ""
        vm.testLaunchSafe { asyncResult = "async value" }
        advanceUntilIdle()

        assertThat(asyncResult).isEqualTo("async value")
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

        assertThat(vm.handleErrorCallCount).isEqualTo(5)

        // VM still works
        var executed = false
        vm.testLaunchSafe { executed = true }
        advanceUntilIdle()
        assertThat(executed).isTrue()
    }
}