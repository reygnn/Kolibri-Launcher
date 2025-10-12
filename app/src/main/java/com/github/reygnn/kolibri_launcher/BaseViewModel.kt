package com.github.reygnn.kolibri_launcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Base ViewModel with built-in error handling and event management.
 */
abstract class BaseViewModel : ViewModel() {

    // Event channel for one-time UI events
    private val _eventChannel = Channel<UiEvent>()
    val eventFlow = _eventChannel.receiveAsFlow()

    protected fun sendEvent(event: UiEvent) {
        viewModelScope.launch {
            try {
                _eventChannel.send(event)
            } catch (e: Exception) {
                Timber.e(e, "Error sending event")   // auch als Toast
            }
        }
    }

    // Coroutine exception handler
    private val coroutineExceptionHandler = CoroutineExceptionHandler { context, throwable ->
        handleError(throwable, context.toString())
    }

    /**
     * Safe coroutine launcher that catches all exceptions.
     */
    protected fun launchSafe(
        onError: ((Throwable) -> Unit)? = null,
        block: suspend CoroutineScope.() -> Unit
    ) {
        viewModelScope.launch(coroutineExceptionHandler) {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // WICHTIG: Re-throw, nicht catchen!
            } catch (e: Exception) {
                onError?.invoke(e) ?: handleError(e, "launchSafe")
            } catch (e: Throwable) {
                handleError(e, "launchSafe - Fatal")
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

    /**
     * Central error handling method.
     * Override this in specific ViewModels for custom error handling.
     */
    protected open fun handleError(throwable: Throwable, context: String) {
        when (throwable) {
            is OutOfMemoryError -> {
                Timber.e(throwable, "[$context] OUT OF MEMORY - Critical!")   // auch als Toast
            }

            is kotlinx.coroutines.CancellationException -> {
                Timber.d("[$context] Coroutine cancelled (normal)")
            }

            else -> {
                Timber.e(throwable, "[$context] Error in ViewModel")   // auch als Toast
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("${this::class.simpleName} cleared")
    }
}