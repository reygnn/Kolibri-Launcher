package com.github.reygnn.kolibri_launcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Base ViewModel with built-in error handling and event management.
 */
abstract class BaseViewModel<E>(
    private val mainDispatcher: CoroutineDispatcher
) : ViewModel(), BaseViewModelInterface<E> {

    // Event channel for one-time UI events
    private val _event = MutableSharedFlow<E>()
    override val event: SharedFlow<E> = _event.asSharedFlow()

    protected suspend fun sendEvent(event: E) {
        try {
            _event.emit(event)
        } catch (e: Exception) {
            Timber.e(e, "Error sending event")
        }
    }

    // Coroutine exception handler
    private val coroutineExceptionHandler = CoroutineExceptionHandler { context, throwable ->
        handleError(throwable, context.toString())
    }

    /**
     * Safe coroutine launcher that catches all exceptions.
     */
    protected fun launchSafe(block: suspend CoroutineScope.() -> Unit) {
        viewModelScope.launch(mainDispatcher + coroutineExceptionHandler) {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                handleError(e, "launchSafe")
            }
        }
    }

    /**
     * Executes a block of code safely, catching all exceptions.
     */
    protected fun <T> executeSafe(
        onError: ((Throwable) -> Unit)? = null,
        block: () -> T
    ): T? {
        return try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onError?.invoke(e) ?: handleError(e, "executeSafe")
            null
        } catch (e: Throwable) {
            handleError(e, "executeSafe - Fatal")
            null
        }
    }

    protected open fun handleError(throwable: Throwable, context: String) {
        when (throwable) {
            is OutOfMemoryError -> {
                Timber.e(throwable, "[$context] OUT OF MEMORY - Critical!")
            }
            is kotlinx.coroutines.CancellationException -> {
                Timber.d("[$context] Coroutine cancelled (normal)")
            }
            else -> {
                Timber.e(throwable, "[$context] Error in ViewModel")

                viewModelScope.launch {
                    @Suppress("UNCHECKED_CAST")
                    sendEvent(UiEvent.ShowToast(R.string.error_generic) as E)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("${this::class.simpleName} cleared")
    }
}