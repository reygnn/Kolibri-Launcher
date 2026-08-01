package com.github.reygnn.kolibri_launcher.core

/**
 * A package-lifecycle event carried by [AppUpdateSignal] from the
 * PackageUpdateReceiver to the app-list layer.
 *
 * Pure Kotlin by design: the receiver maps the Android `Intent` to this type
 * at the `:data` edge, so the bus and its consumers stay Android-free. The
 * payload (which package changed) is what lets the reconcile act on a specific
 * target instead of diffing the whole installed-app list — see
 * `RECONCILE_SPEC.md`.
 *
 * [Removed] is only ever emitted for a genuine uninstall: the receiver filters
 * the replace half of an in-place update (`EXTRA_REPLACING`) before building
 * the event.
 */
sealed interface PackageEvent {
    val packageName: String

    /** A package became available (install, or the add half of a replace). */
    data class Added(override val packageName: String) : PackageEvent

    /** A package was genuinely uninstalled (never a replace). */
    data class Removed(override val packageName: String) : PackageEvent
}
