package com.github.reygnn.nyx_launcher.home.usecase

import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.LayoutEditResult
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The IO shell every home-edit use-case shares (AUDIT-1 A1-14): hop to
 * [dispatcher], run [transition] against the current layout inside the
 * repository's atomic read-modify-write (A1-03), and persist only when the
 * result carries a new layout (`layout != null` ⇔ a real change). Returns the
 * transition's result unchanged.
 *
 * [default] is returned only if [transition] never runs — which it always does
 * (`update` invokes it exactly once) — so it is just the initial value.
 */
suspend inline fun <R : LayoutEditResult> HomeLayoutRepository.runLayoutEdit(
    dispatcher: CoroutineDispatcher,
    default: R,
    crossinline transition: (HomeLayout) -> R,
): R = withContext(dispatcher) {
    var result = default
    update { current ->
        result = transition(current)
        result.layout
    }
    result
}
