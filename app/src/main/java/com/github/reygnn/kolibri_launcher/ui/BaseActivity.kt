package com.github.reygnn.kolibri_launcher.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.reygnn.kolibri_launcher.ui.BaseViewModelInterface
import com.github.reygnn.kolibri_launcher.BuildConfig
import com.github.reygnn.kolibri_launcher.ErrorData
import com.github.reygnn.kolibri_launcher.ErrorEventBus
import com.github.reygnn.kolibri_launcher.Event
import com.github.reygnn.kolibri_launcher.TimberWrapper
import com.github.reygnn.kolibri_launcher.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ULTRA CRASH-SAFE Base Activity
 *
 * Multi-layer exception handling for maximum stability:
 * - Layer 1: Handler-level try-catch (catches errors in event handlers)
 * - Layer 2: Collector-level try-catch (catches errors in Flow collection)
 * - Layer 3: CoroutineExceptionHandler (catches uncaught coroutine exceptions)
 * - Layer 4: Global exception handler (in Application class)
 *
 * Event flows run in STARTED lifecycle to ensure toasts are only shown
 * when the Activity is visible to the user. Application-level errors
 * posted before any Activity exists are buffered via SharedFlow replay
 * and shown when the first Activity becomes visible.
 *
 * Exception handling strategy:
 * - CancellationException: Always re-thrown (coroutine control flow)
 * - Throwable: Caught to handle both Exception and Error types
 * - Prevents launcher crashes from OutOfMemoryError, StackOverflowError, etc.
 */
abstract class BaseActivity<E, VM> : AppCompatActivity()
        where VM : ViewModel, VM : BaseViewModelInterface<E> {

    internal abstract val viewModel: VM

    private var lastErrorToastTime = 0L
    private var lastUiEventToastTime = 0L
    private val TOAST_THROTTLE_MS = 2000L

    /**
     * Backup exception handler for coroutines.
     * This catches any exceptions that escape the try-catch blocks.
     */
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        try {
            Timber.Forest.e(throwable, "Uncaught coroutine exception in BaseActivity")
        } catch (e: Throwable) {
            // Even logging can fail - silent fallback
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch(coroutineExceptionHandler) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Job 1: Global error bus
                launch(coroutineExceptionHandler) {
                    try {
                        ErrorEventBus.events.collect { event ->
                            try {
                                handleErrorEvent(event)
                            } catch (e: CancellationException) {
                                throw e  // Coroutine control flow - must re-throw
                            } catch (e: Throwable) {
                                // Catches Exception and Error (OutOfMemoryError, etc.)
                                Timber.Forest.e(e, "Error handling error event")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e  // Re-throw
                    } catch (e: Throwable) {
                        // Catches errors in Flow collection itself
                        Timber.Forest.e(e, "Error collecting from ErrorEventBus")
                    }
                }

                // Job 2: ViewModel events
                launch(coroutineExceptionHandler) {
                    try {
                        viewModel.event.collect { event ->
                            try {
                                val wasHandled = if (event is UiEvent) {
                                    handleGenericUiEvent(event)
                                } else {
                                    false
                                }

                                if (!wasHandled) {
                                    handleSpecificEvent(event)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                Timber.Forest.e(e, "Error handling UI event: $event")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Timber.Forest.e(e, "Error collecting from ViewModel eventFlow")
                    }
                }
            }
        }
    }

    /**
     * Handles generic UI events that are common across all activities.
     * @return true if the event was handled, false if it should be passed to handleSpecificEvent
     */
    protected open fun handleGenericUiEvent(event: UiEvent): Boolean {
        if (BuildConfig.DEBUG) {
            Timber.Forest.d("handleUiEvent called with: $event")
        }

        return when (event) {
            is UiEvent.ShowToast -> {
                val now = System.currentTimeMillis()
                if (now - lastUiEventToastTime >= TOAST_THROTTLE_MS) {
                    lastUiEventToastTime = now
                    showToastSafe(getString(event.messageResId), Toast.LENGTH_LONG)
                } else {
                    if (BuildConfig.DEBUG) {
                        Timber.Forest.d("UiEvent toast throttled: ${getString(event.messageResId)}")
                    }
                }
                true
            }

            is UiEvent.ShowToastFromString -> {
                val now = System.currentTimeMillis()
                if (now - lastUiEventToastTime >= TOAST_THROTTLE_MS) {
                    lastUiEventToastTime = now
                    showToastSafe(event.message, Toast.LENGTH_LONG)
                } else {
                    if (BuildConfig.DEBUG) {
                        Timber.Forest.d("UiEvent toast throttled: ${event.message}")
                    }
                }
                true
            }

            is UiEvent.NavigateUp -> {
                try {
                    finish()
                } catch (e: Exception) {
                    Timber.Forest.e(e, "Error finishing activity")
                }
                true
            }

            else -> {
                // Event is not generic - will be handled by handleSpecificEvent
                if (BuildConfig.DEBUG) {
                    Timber.Forest.d("Event not handled in BaseActivity: $event")
                }
                false
            }
        }
    }

    /**
     * Override this to handle activity-specific events.
     * Called when an event is not handled by handleGenericUiEvent.
     */
    protected abstract fun handleSpecificEvent(event: E)

    /**
     * Handles error events from the global ErrorEventBus.
     * Displays developer error toasts in debug builds.
     */
    private fun handleErrorEvent(event: Event<ErrorData>) {
        event.getContentIfNotHandled()?.let { errorData ->
            // Skip silent errors
            if (errorData.tag == TimberWrapper.SILENT_LOG_TAG) {
                return@let
            }

            // Throttle error toasts
            val now = System.currentTimeMillis()
            if (now - lastErrorToastTime < TOAST_THROTTLE_MS) {
                Timber.Forest.d("Error toast throttled: ${errorData.message}")
                return@let
            }
            lastErrorToastTime = now

            val message = "Dev Error: ${errorData.message}"
            showToastSafe(message, Toast.LENGTH_LONG)
        }
    }

    /**
     * Shows a toast with additional error handling.
     * Even Toast.makeText can throw exceptions in rare cases.
     */
    private fun showToastSafe(message: String, duration: Int) {
        try {
            Toast.makeText(this, message, duration).show()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error showing toast")
        }
    }
}