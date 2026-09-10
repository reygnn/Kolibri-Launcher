package com.github.reygnn.nyx_launcher.home.model

/**
 * Result of enumerating installed apps. A failed load must stay distinguishable
 * from "genuinely zero apps" — collapsing an error to an empty list would let a
 * transient PackageManager hiccup empty the home screen during reconcile
 * (RECONCILE_HOME_LAYOUT_SPEC §7-D1, RHL-INV-1). Mirrors the big Kolibri's
 * `AppLoadResult` posture.
 */
sealed interface AppLoadResult {
    data class Loaded(val apps: List<LauncherApp>) : AppLoadResult
    data class Error(val reason: Reason) : AppLoadResult

    enum class Reason { ENUMERATION_FAILED }
}
