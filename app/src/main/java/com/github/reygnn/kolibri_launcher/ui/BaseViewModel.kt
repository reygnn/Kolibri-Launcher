package com.github.reygnn.kolibri_launcher.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ULTRA CRASH-SAFE Base ViewModel
 *
 * Multi-layer exception handling:
 * - All operations catch Throwable (Exception + Error)
 * - CancellationException properly re-thrown
 * - CoroutineExceptionHandler as backup
 * - Safe event emission with fallback
 * - Protected error handling with triple-fallback logging
 *
 * This ensures ViewModels stay alive even under extreme conditions
 * like OutOfMemoryError, StackOverflowError, or corrupted state.
 */
abstract class BaseViewModel<E>(
    private val mainDispatcher: CoroutineDispatcher
) : ViewModel(), BaseViewModelInterface<E> {

    // Event channel for one-time UI events
    private val _event = MutableSharedFlow<E>()
    override val event: SharedFlow<E> = _event.asSharedFlow()

    /**
     * Sends an event to the UI layer with ultra-safe error handling.
     * Even if event emission fails, the ViewModel stays alive.
     */
    protected suspend fun sendEvent(event: E) {
        try {
            _event.emit(event)
        } catch (e: CancellationException) {
            throw e  // Coroutine control flow - must re-throw
        } catch (e: Throwable) {
            // Ultra paranoid: Catch everything
            try {
                Timber.Forest.e(e, "Error sending event: $event")
            } catch (loggingError: Throwable) {
                // Even logging can fail - silent fallback
            }
        }
    }

    /**
     * Primary coroutine exception handler.
     * This catches exceptions that escape the try-catch blocks.
     */
    private val coroutineExceptionHandler = CoroutineExceptionHandler { context, throwable ->
        try {
            handleError(throwable, "CoroutineExceptionHandler: $context")
        } catch (e: Throwable) {
            // Even error handler can fail - last resort logging
            try {
                Timber.Forest.e(e, "CRITICAL: Error in exception handler")
            } catch (ignored: Throwable) {
                // Absolute last resort - nothing we can do
            }
        }
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
            // Ultra paranoid: Catch everything
            try {
                onError?.invoke(e) ?: handleError(e, "executeSafe")
            } catch (handlerError: Throwable) {
                // Error handler itself failed
                try {
                    Timber.Forest.e(handlerError, "Error in custom error handler")
                } catch (ignored: Throwable) {
                    // Even logging can fail
                }
            }
            null
        }
    }

    /**
     * Handles errors with triple-fallback logging and safe event emission.
     * This method itself is protected against failures.
     *
     * @param throwable The error that occurred
     * @param context Context information about where the error occurred
     */

    /**
     * Handles errors with triple-fallback logging.
     * Override shouldShowErrorToast() in child ViewModels if E is not UiEvent.
     */
    protected open fun handleError(throwable: Throwable, context: String) {
        // Triple-fallback logging
        try {
            when (throwable) {
                is OutOfMemoryError -> {
                    Timber.Forest.e(throwable, "[$context] OUT OF MEMORY - Critical!")
                    try {
                        System.gc()
                    } catch (ignored: Throwable) { }
                }
                is StackOverflowError -> {
                    Timber.Forest.e(throwable, "[$context] STACK OVERFLOW - Critical!")
                }
                is CancellationException -> {
                    Timber.Forest.d("[$context] Coroutine cancelled (normal)")
                }
                else -> {
                    Timber.Forest.e(throwable, "[$context] Error in ViewModel")
                }
            }
        } catch (loggingError: Throwable) {
            try {
                Log.e("BaseViewModel", "[$context] Error: ${throwable.message}", throwable)
            } catch (ignored: Throwable) { }
        }

        // Show error toast if applicable
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
     * Override this in child ViewModels where E is NOT UiEvent.
     * Default implementation assumes E extends UiEvent.
     */
    protected open fun showErrorToastIfSupported() {
        viewModelScope.launch(coroutineExceptionHandler) {
            try {
                // This will fail at runtime if E is not UiEvent, but that's okay
                // Child ViewModels should override this method if needed
                @Suppress("UNCHECKED_CAST")
                sendEvent(UiEvent.ShowToast(R.string.error_generic) as E)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClassCastException) {
                // E is not UiEvent - that's okay, this ViewModel doesn't support error toasts
                Timber.Forest.d("This ViewModel does not support UiEvent error toasts")
            } catch (e: Throwable) {
                try {
                    Timber.Forest.e(e, "Failed to send error toast event")
                } catch (ignored: Throwable) { }
            }
        }
    }

    override fun onCleared() {
        try {
            super.onCleared()
            Timber.Forest.d("${this::class.simpleName} cleared")
        } catch (e: Throwable) {
            // Even onCleared can fail
            try {
                Log.d("BaseViewModel", "${this::class.simpleName} cleared with error", e)
            } catch (ignored: Throwable) {
                // Nothing we can do
            }
        }
    }
}