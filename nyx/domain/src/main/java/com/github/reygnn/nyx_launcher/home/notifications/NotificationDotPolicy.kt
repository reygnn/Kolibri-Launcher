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
    /**
     * The platform ranking's `canShowBadge()` — false when the user disabled the
     * notification dot for this app/channel, or the channel is silent/low-importance.
     * Honouring it is what makes the dots match the user's per-app dot settings (and
     * the Pixel Launcher).
     */
    val canShowBadge: Boolean,
)

/**
 * Pure decision for which packages get a notification dot (family Rule 10). Mirrors
 * the Pixel Launcher's default: a dot marks a real, actionable, badge-eligible
 * notification, so ongoing / non-clearable ones (foreground-service, "USB charging",
 * media transport controls, …) and ones the user/channel disabled the dot for
 * ([NotificationSummary.canShowBadge] false) are excluded. Presence only — the result
 * is a set of package names.
 */
object NotificationDotPolicy {

    /** Package names that should show a dot, given the currently [active] notifications. */
    fun dotPackages(active: List<NotificationSummary>): Set<String> =
        active.asSequence()
            .filter { !it.isOngoing && it.isClearable && it.canShowBadge }
            .map { it.packageName }
            .toSet()
}
