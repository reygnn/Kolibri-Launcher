package com.github.reygnn.kolibri_launcher.crashreporting.health

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.reygnn.kolibri_launcher.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Out-of-band surface for a broken ACRA bootstrap: a local notification. This is
 * NOT routed through ACRA (that would be circular) and needs no server round-trip
 * — it fires purely on the on-device [CrashReportingHealthState].
 *
 * "Show once" is handled by the notification itself, not a persisted flag: a fixed
 * ID + `setOnlyAlertOnce` means re-posting on every cold start silently updates the
 * existing notification instead of re-alerting, and [clear] cancels it the moment
 * health recovers. Best-effort: if POST_NOTIFICATIONS is denied the post is a
 * no-op, but the Settings hint (same [CrashReportingHealthState]) always shows.
 */
class CrashReportingHealthNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val channelCreated = java.util.concurrent.atomic.AtomicBoolean(false)

    fun showBroken() {
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.crash_report_health_notif_title))
            .setContentText(context.getString(R.string.crash_report_health_notif_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.crash_report_health_notif_text)),
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — best-effort; the Settings hint covers it.
        }
    }

    fun clear() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        if (channelCreated.getAndSet(true)) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.crash_report_health_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "crash_reporting_health"
        const val NOTIFICATION_ID = 4711
    }
}
