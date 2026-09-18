package com.github.reygnn.nyx_launcher.home.notifications

/**
 * Plain, Android-free summary of one active status-bar notification — the only
 * bits the dot feature needs. The listener service maps each
 * `StatusBarNotification` to this so the decision logic stays JVM-testable and
 * carries NO content (title/text/count) — privacy: presence only.
 */
data class NotificationSummary(
    val packageName: String,
    val isOngoing: Boolean,
    val isClearable: Boolean,
)

/**
 * Pure decision for which packages get a notification dot (family Rule 10). Mirrors
 * the Pixel Launcher's default: a dot marks a real, actionable notification, so
 * ongoing / non-clearable ones (foreground-service, "USB charging", media transport
 * controls, …) are excluded. Presence only — the result is a set of package names.
 */
object NotificationDotPolicy {

    /** Package names that should show a dot, given the currently [active] notifications. */
    fun dotPackages(active: List<NotificationSummary>): Set<String> =
        active.asSequence()
            .filter { !it.isOngoing && it.isClearable }
            .map { it.packageName }
            .toSet()
}
