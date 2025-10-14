package com.github.reygnn.kolibri_launcher

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * CRASH-SAFE Base Activity
 *
 * Both event flows run in CREATED lifecycle to ensure Fragment events are caught early.
 */
abstract class BaseActivity<VM : BaseViewModel> : AppCompatActivity() {

    internal abstract val viewModel: VM

    private var lastErrorToastTime = 0L
    private var lastUiEventToastTime = 0L
    private val TOAST_THROTTLE_MS = 2000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FIXED: Single lifecycle scope with CREATED state for both flows
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {

                // Job 1: Global error bus
                launch {
                    try {
                        ErrorEventBus.events.collect { event ->
                            try {
                                handleErrorEvent(event)
                            } catch (e: Exception) {
                                Timber.e(e, "Error handling error event")   // auch als Toast
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e  // Re-throw
                    } catch (e: Exception) {
                        Timber.e(e, "Error collecting from ErrorEventBus")   // auch als Toast
                    }
                }

                // Job 2: ViewModel events - in same CREATED block
                launch {
                    try {
                        viewModel.eventFlow.collect { event ->
                            try {
                                Timber.d("BaseActivity received event: $event") // DEBUG
                                handleUiEvent(event)
                            } catch (e: Exception) {
                                Timber.e(e, "Error handling UI event: $event")   // auch als Toast
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e  // Re-throw
                    } catch (e: Exception) {
                        Timber.e(e, "Error collecting from ViewModel eventFlow")   // auch als Toast
                    }
                }
            }
        }
    }

    protected open fun handleUiEvent(event: UiEvent) {
        if (BuildConfig.DEBUG) {
            Timber.d("handleUiEvent called with: $event")
        }

        when (event) {
            is UiEvent.ShowToast -> {
                val now = System.currentTimeMillis()
                if (now - lastUiEventToastTime < TOAST_THROTTLE_MS) {
                    if (BuildConfig.DEBUG) {
                        Timber.d("UiEvent toast throttled: ${getString(event.messageResId)}")
                    }
                    return
                }
                lastUiEventToastTime = now

                showToastSafe(getString(event.messageResId), Toast.LENGTH_LONG)
            }

            is UiEvent.ShowToastFromString -> {
                val now = System.currentTimeMillis()
                if (now - lastUiEventToastTime < TOAST_THROTTLE_MS) {
                    if (BuildConfig.DEBUG) {
                        Timber.d("UiEvent toast throttled: ${event.message}")
                    }
                    return
                }
                lastUiEventToastTime = now

                showToastSafe(event.message, Toast.LENGTH_LONG)
            }

            is UiEvent.NavigateUp -> {
                try {
                    finish()
                } catch (e: Exception) {
                    Timber.e(e, "Error finishing activity")   // auch als Toast
                }
            }

            else -> {
                if (BuildConfig.DEBUG) {
                    Timber.d("Event not handled in BaseActivity: $event")
                }
            }
        }
    }

    private fun handleErrorEvent(event: Event<ErrorData>) {
        event.getContentIfNotHandled()?.let { errorData ->
            if (errorData.tag == TimberWrapper.SILENT_LOG_TAG) {
                return@let
            }

            val now = System.currentTimeMillis()
            if (now - lastErrorToastTime < TOAST_THROTTLE_MS) {
                Timber.d("Error toast throttled: ${errorData.message}")
                return@let
            }
            lastErrorToastTime = now

            val message = "Dev Error: ${errorData.message}"
            showToastSafe(message, Toast.LENGTH_LONG)
        }
    }

    private fun showToastSafe(message: String, duration: Int) {
        try {
            Toast.makeText(this, message, duration).show()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error showing toast")   // DIESEN nicht als Toast ;)
        }
    }
}