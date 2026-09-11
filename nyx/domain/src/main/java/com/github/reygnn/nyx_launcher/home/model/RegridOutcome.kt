package com.github.reygnn.nyx_launcher.home.model

/**
 * Pure-policy output of the regrid transition
 * ([com.github.reygnn.nyx_launcher.home.transition.HomeLayoutRegridder]): the
 * layout re-fitted onto a new device [GridSpec], or [Unchanged] when it already
 * matches. Mirrors [ReconcileOutcome]'s shape.
 */
sealed interface RegridOutcome {
    data class Changed(val layout: HomeLayout) : RegridOutcome
    data object Unchanged : RegridOutcome
}
