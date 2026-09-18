package com.github.reygnn.nyx_launcher.home.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.github.reygnn.launcher.core.TimberWrapper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Feeds the [NotificationPresenceStore] with the packages that currently have a
 * dot-worthy notification (see [NotificationDotPolicy]). Opt-in: the user must grant
 * notification access in system settings, otherwise this service is never bound.
 *
 * Privacy: only reads `packageName` + the ongoing/clearable flags of active
 * notifications; never their title, text, or count. On every change it recomputes the
 * whole set from [getActiveNotifications] (cheap — the active set is small), which
 * keeps posted/removed/updated handling trivially correct.
 */
@AndroidEntryPoint
class NyxNotificationListenerService : NotificationListenerService() {

    @Inject lateinit var store: NotificationPresenceStore

    override fun onListenerConnected() {
        super.onListenerConnected()
        refresh()
    }

    override fun onListenerDisconnected() {
        // Access revoked / service tearing down: drop the dots so nothing stale lingers.
        store.clear()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        // Catch kept: getActiveNotifications throws SecurityException when the listener
        // is not (yet) connected, and system callbacks are a real failure boundary. No
        // suspension point here, so no CancellationException concern.
        try {
            val active = activeNotifications?.map { it.toSummary() }.orEmpty()
            store.update(NotificationDotPolicy.dotPackages(active))
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to refresh notification dots")
        }
    }
}

private fun StatusBarNotification.toSummary() = NotificationSummary(
    packageName = packageName,
    isOngoing = isOngoing,
    isClearable = isClearable,
)
