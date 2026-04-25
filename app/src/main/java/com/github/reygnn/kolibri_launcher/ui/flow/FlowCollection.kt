package com.github.reygnn.kolibri_launcher.ui.flow

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Collects [flow] tied to this Fragment's view lifecycle, restarting on STARTED.
 *
 * Replaces the recurring shape:
 *
 * ```
 * viewLifecycleOwner.lifecycleScope.launch(coroutineContext) {
 *     try {
 *         repeatOnLifecycle(Lifecycle.State.STARTED) {
 *             try {
 *                 flow.collect { value -> ... }
 *             } catch (e: CancellationException) { throw e }
 *               catch (e: Throwable) { TimberWrapper.silentError(e, "$tag (collect)") }
 *         }
 *     } catch (e: CancellationException) { throw e }
 *       catch (e: Throwable) { TimberWrapper.silentError(e, "$tag (lifecycle)") }
 * }
 * ```
 *
 * Scope of this helper:
 *  - Pure boilerplate reduction. No behavior change vs. the inlined form.
 *  - Per-call view-state guards (e.g. `if (_binding == null) return@collect`)
 *    remain caller-side — they vary per fragment and per call site.
 *  - The fragment-level [kotlinx.coroutines.CoroutineExceptionHandler] (where
 *    present) is passed via [coroutineContext], same as before. There is no
 *    default — every call site states its context explicitly, since in
 *    practice every site needs `Dispatchers.Main + fragmentExceptionHandler`
 *    and a misleading [kotlin.coroutines.EmptyCoroutineContext] default would
 *    only invite confusion.
 *
 * Not covered (intentionally — different shapes, not pure boilerplate):
 *  - Multiple sub-launches inside a single repeatOnLifecycle.
 *  - Collect bodies that nest their own coroutines (e.g. debounced search).
 *
 * **Transitional state:** call-site bodies currently retain their own
 * `try/catch` wrappers, so the inner catch in this helper does not fire
 * today — collect-body exceptions are caught one frame inward. The two-layer
 * design exists for the planned try/catch audit (see HomeFragment file
 * header, priority 2): once the body wrappers are removed, this inner catch
 * becomes the active defense line for collect-body exceptions. **Do not
 * remove the inner catch as "dead code" before the audit lands.**
 *
 * Not unit-tested directly: Fragment + viewLifecycleOwner extensions require
 * Robolectric or instrumented tests, which this project deliberately avoids.
 * Correctness of this helper is verified by reading its body against the
 * inlined form it replaces.
 *
 * @param flow source flow to collect from while STARTED.
 * @param errorTag identifier used for log messages on both catch layers; should
 *   uniquely identify the observer (e.g. `"favorites"`, `"wallpaper state"`).
 * @param coroutineContext extra context for the outer launch — typically
 *   `Dispatchers.Main + fragmentExceptionHandler` to match the existing
 *   pattern. No default; pass it explicitly at every call site.
 * @param collector body invoked for each emitted value.
 * @return the [Job] of the outer launch (matches `lifecycleScope.launch`).
 */
fun <T> Fragment.collectOnStarted(
    flow: Flow<T>,
    errorTag: String,
    coroutineContext: CoroutineContext,
    collector: suspend (T) -> Unit,
): Job = viewLifecycleOwner.lifecycleScope.launch(coroutineContext) {
    try {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                flow.collect { value -> collector(value) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "$errorTag (collect)")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        TimberWrapper.silentError(e, "$errorTag (lifecycle)")
    }
}
