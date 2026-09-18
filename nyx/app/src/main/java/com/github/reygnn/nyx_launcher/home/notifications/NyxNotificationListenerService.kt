package com.github.reygnn.nyx_launcher.home.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
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
        // Catch kept: a system-callback boundary. getActiveNotifications can throw a
        // SecurityException on a connect/disconnect race or a transient binder failure.
        // reportToAcra (report in RELEASE, no DEBUG throw) tolerates the boundary rather
        // than crashing development on a system race. No suspension point here.
        try {
            val ranking = currentRanking
            val active = activeNotifications?.map { it.toSummary(ranking) }.orEmpty()
            store.update(NotificationDotPolicy.dotPackages(active))
        } catch (e: Throwable) {
            TimberWrapper.reportToAcra(e, "Failed to refresh notification dots")
        }
    }
}

private fun StatusBarNotification.toSummary(ranking: RankingMap?): NotificationSummary {
    // Badge eligibility honours the user's per-app/channel "notification dot" setting
    // (and silent/low-importance channels); default true if the ranking is unavailable.
    // Presence + flags only — never title/text/count.
    val canBadge = ranking?.let {
        val r = Ranking()
        if (it.getRanking(key, r)) r.canShowBadge() else true
    } ?: true
    return NotificationSummary(
        packageName = packageName,
        isOngoing = isOngoing,
        isClearable = isClearable,
        canShowBadge = canBadge,
    )
}
