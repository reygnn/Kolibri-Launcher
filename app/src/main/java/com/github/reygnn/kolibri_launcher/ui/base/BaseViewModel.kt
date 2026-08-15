package com.github.reygnn.kolibri_launcher.ui.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Base ViewModel with coroutine-boundary exception handling.
 *
 * Catches at real boundaries only (per CLAUDE.md Rule 11):
 * - `launchSafe` and `executeSafe` wrap caller-supplied blocks (EXTERNAL).
 * - `sendEvent` wraps `Channel.send` (suspend boundary).
 * - `coroutineExceptionHandler` is the last-resort backstop for coroutines
 *   that escape the explicit try-catch blocks.
 *
 * `CancellationException` is always re-thrown to preserve coroutine
 * control flow. Other `Throwable`s (including `Error`s like OOM) are
 * routed to `handleError`, which logs and emits a generic error toast
 * unless suppressed.
 */
abstract class BaseViewModel<E>(
    private val mainDispatcher: CoroutineDispatcher
) : ViewModel(), BaseViewModelInterface<E> {

    // Event channel for one-time UI events. A Channel — not a replay=0
    // MutableSharedFlow — so an event emitted while no collector is active
    // (e.g. a nav event fired right after a suspending use-case, during the
    // window a config change has torn down the STARTED-scoped collector) is
    // buffered and delivered to the next collector instead of being dropped.
    // Single-consumer by design: each ViewModel's event is collected by
    // exactly one lifecycle-scoped collector. Unlike replay=1 it does not
    // re-deliver past events to a collector that re-subscribes on rotation.
    private val _event = Channel<E>(Channel.BUFFERED)
    override val event: Flow<E> = _event.receiveAsFlow()

    /**
     * Sends an event to the UI layer. Failures during emission are
     * logged but do not propagate.
     */
    protected suspend fun sendEvent(event: E) {
        try {
            _event.send(event)
        } catch (e: CancellationException) {
            throw e  // Coroutine control flow - must re-throw
        } catch (e: Throwable) {
            Timber.e(e, "Error sending event: $event")
        }
    }

    /**
     * Last-resort handler for coroutine exceptions that escape the
     * explicit try-catch blocks in `launchSafe`.
     */
    private val coroutineExceptionHandler = CoroutineExceptionHandler { context, throwable ->
        handleError(throwable, "CoroutineExceptionHandler: $context")
    }

    /**
     * Safe coroutine launcher that catches all exceptions.
     * Uses multi-layer protection:
     * 1. Inner try-catch for explicit error handling
     * 2. CoroutineExceptionHandler as backup
     */
    protected fun launchSafe(block: suspend CoroutineScope.() -> Unit) {
        viewModelScope.launch(mainDispatcher + coroutineExceptionHandler) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e  // Coroutine control flow - must re-throw
            } catch (e: Throwable) {
                // Catches Exception and Error (OutOfMemoryError, etc.)
                handleError(e, "launchSafe")
            }
        }
    }

    /**
     * Executes a block of code safely, catching all exceptions.
     * Returns null if execution fails.
     *
     * @param onError Optional error handler
     * @param block Code to execute
     * @return Result of block execution, or null on error
     */
    protected fun <T> executeSafe(
        onError: ((Throwable) -> Unit)? = null,
        block: () -> T
    ): T? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e  // Coroutine control flow - must re-throw
        } catch (e: Throwable) {
            try {
                onError?.invoke(e) ?: handleError(e, "executeSafe")
            } catch (handlerError: Throwable) {
                // The caller-supplied onError callback itself threw —
                // log and swallow so executeSafe still returns null.
                // `onError`/`handleError` are non-suspend — no suspension point,
                // so no CancellationException can reach this catch.
                Timber.e(handlerError, "Error in custom error handler")
            }
            null
        }
    }

    /**
     * Logs the error and emits a generic error toast unless the error
     * type is one the user can't act on (cancellation, OOM, stack
     * overflow). Override `showErrorToastIfSupported` in child
     * ViewModels where `E` is not `UiEvent`.
     *
     * Handles `CancellationException` defensively even though the
     * in-class callers (launchSafe, executeSafe, coroutineExceptionHandler)
     * all filter it first: this is `protected open`, so subclasses and
     * tests may invoke it directly with any Throwable. Same rationale
     * for the matching branch in `shouldSuppressErrorToast`.
     */
    protected open fun handleError(throwable: Throwable, context: String) {
        when (throwable) {
            is OutOfMemoryError -> {
                Timber.e(throwable, "[$context] OUT OF MEMORY - Critical!")
                System.gc()
            }
            is StackOverflowError -> {
                Timber.e(throwable, "[$context] STACK OVERFLOW - Critical!")
            }
            is CancellationException -> {
                Timber.d("[$context] Coroutine cancelled (normal)")
            }
            else -> {
                Timber.e(throwable, "[$context] Error in ViewModel")
            }
        }

        if (!shouldSuppressErrorToast(throwable)) {
            showErrorToastIfSupported()
        }
    }

    /**
     * Determines if an error toast should be suppressed.
     * Some errors shouldn't result in user-visible toasts.
     */
    private fun shouldSuppressErrorToast(throwable: Throwable): Boolean {
        return when (throwable) {
            is CancellationException -> true  // Normal cancellation
            is OutOfMemoryError -> true       // User can't do anything about this
            is StackOverflowError -> true     // User can't do anything about this
            else -> false
        }
    }

    /**
     * Subclasses opt into a generic error toast by overriding this with
     * a concrete value. Default `null` = no toast — appropriate for
     * ViewModels whose event type has no toast variant.
     */
    protected open val errorEvent: E? = null

    /**
     * Emits [errorEvent] when [handleError] decides a toast should
     * surface. No-op when [errorEvent] is null.
     */
    protected open fun showErrorToastIfSupported() {
        val event = errorEvent ?: return
        viewModelScope.launch(coroutineExceptionHandler) {
            sendEvent(event)
        }
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("${this::class.simpleName} cleared")
    }
}