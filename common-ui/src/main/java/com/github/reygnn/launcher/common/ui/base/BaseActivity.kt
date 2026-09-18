package com.github.reygnn.launcher.common.ui.base

import android.os.Bundle
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.reygnn.launcher.common.ui.ErrorData
import com.github.reygnn.launcher.common.ui.ErrorEventBus
import com.github.reygnn.launcher.common.ui.Event
import com.github.reygnn.launcher.common.ui.showToastSafe
import com.github.reygnn.launcher.core.TimberWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch

/**
 * Product-neutral crash-safe base Activity, shared by both launchers (:common-ui).
 *
 * This is the Activity-level sibling of [BaseViewModel]: it installs a
 * last-resort [CoroutineExceptionHandler] and collects the two one-shot streams
 * every launcher screen cares about, each behind its own try/catch, so a
 * throwable in a UI coroutine is reported instead of crashing the launcher.
 *
 * Depends only on the shared :core logging façade ([TimberWrapper]) and the
 * shared :common-ui toast/event helpers — no app-specific types. In particular it
 * does NOT hardcode any concrete `UiEvent`: ViewModel events are delegated to the
 * open [handleEvent] hook, so an app whose event type has no cases (`Nothing`)
 * needs no override at all. (Kolibri keeps its own richer `UiEvent`-aware base for
 * now; this is the neutral one nyx adopts.)
 *
 * Catches at real boundaries only (Kolibri CLAUDE.md Rule 11): the collectors and
 * their handlers wrap external work; every broad `catch (Throwable)` sits behind a
 * `catch (CancellationException) { throw e }` arm so normal coroutine cancellation
 * (STARTED-scope teardown on stop/rotate) always propagates.
 *
 * `E` = the ViewModel's one-shot event type, `VM` = the ViewModel type
 * (must be a [BaseViewModelInterface] so its [event] flow is available).
 */
abstract class BaseActivity<E, VM> : AppCompatActivity()
        where VM : ViewModel, VM : BaseViewModelInterface<E> {

    protected abstract val viewModel: VM

    private var lastErrorToastTime = 0L

    /**
     * Last-resort backstop for coroutines that escape the explicit try/catch
     * blocks below. Exposed `protected` so subclasses can attach it to their own
     * `lifecycleScope.launch { … }` UI collectors (which otherwise run with no
     * handler and would crash the launcher on a throwable).
     */
    protected val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        TimberWrapper.reportToAcra(throwable, "Uncaught coroutine exception in BaseActivity")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch(coroutineExceptionHandler) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Job 1: global error bus → developer error toasts (DEBUG only).
                launch(coroutineExceptionHandler) {
                    try {
                        ErrorEventBus.events.collect { event ->
                            try {
                                handleErrorEvent(event)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                TimberWrapper.reportToAcra(e, "Error handling error event")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.reportToAcra(e, "Error collecting from ErrorEventBus")
                    }
                }

                // Job 2: ViewModel one-shot events → subclass hook.
                launch(coroutineExceptionHandler) {
                    try {
                        viewModel.event.collect { event ->
                            try {
                                handleEvent(event)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                TimberWrapper.reportToAcra(e, "Error handling UI event: $event")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.reportToAcra(e, "Error collecting from ViewModel event flow")
                    }
                }
            }
        }
    }

    /**
     * Handles a ViewModel one-shot event. Default no-op so an app whose event type
     * is `Nothing` (no cases) needs no override; apps with a real event type
     * override this to route them (toast, navigation, …).
     */
    protected open fun handleEvent(event: E) {}

    /**
     * Shows a developer error toast for an entry posted to the global
     * [ErrorEventBus] — DEBUG builds only, throttled, and skipped for
     * `silentError`-tagged entries (they already throw in DEBUG, so a toast is
     * redundant; the two-tag asymmetry mirrors Kolibri's BaseActivity).
     */
    private fun handleErrorEvent(event: Event<ErrorData>) {
        // Short-circuit before consuming the event so a release build does not mark it
        // handled (getContentIfNotHandled is one-shot).
        if (!TimberWrapper.isDebugBuild) return
        val errorData = event.getContentIfNotHandled() ?: return
        val now = System.currentTimeMillis()
        // Pure decision (DEBUG gate + tag suppression + throttle) is extracted so it can
        // be unit-tested without a live Activity — the collector wiring still needs
        // Robolectric, but the load-bearing gate/throttle logic is pinned in JVM tests.
        if (!shouldShowDevToast(TimberWrapper.isDebugBuild, errorData.tag, now, lastErrorToastTime)) return
        lastErrorToastTime = now
        showToastSafe("Dev Error: ${errorData.message}", Toast.LENGTH_LONG)
    }

    companion object {
        @VisibleForTesting
        internal const val TOAST_THROTTLE_MS = 2000L

        /**
         * Whether an [ErrorEventBus] entry's tag suppresses the DEBUG dev-toast.
         * ONLY `SILENT_ERROR` (silentError/silentDeath) suppresses — those already
         * throw in DEBUG, so a toast is redundant; `ACRA_REPORT` and any
         * other/absent tag do NOT suppress. Pure predicate so both branches are
         * pinnable.
         */
        @VisibleForTesting
        internal fun isDevToastSuppressed(tag: String?): Boolean =
            tag == TimberWrapper.SILENT_LOG_TAG

        /**
         * Whether a dev error toast should surface now: DEBUG builds only, not a
         * suppressed ([isDevToastSuppressed]) tag, and at least [TOAST_THROTTLE_MS]
         * since [lastToastMs]. Pure so the gate + throttle are JVM-testable.
         */
        @VisibleForTesting
        internal fun shouldShowDevToast(
            isDebugBuild: Boolean,
            tag: String?,
            nowMs: Long,
            lastToastMs: Long,
        ): Boolean =
            isDebugBuild &&
                !isDevToastSuppressed(tag) &&
                nowMs - lastToastMs >= TOAST_THROTTLE_MS
    }
}
