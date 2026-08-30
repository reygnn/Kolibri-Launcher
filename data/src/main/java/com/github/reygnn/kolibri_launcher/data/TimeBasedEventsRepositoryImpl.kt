package com.github.reygnn.kolibri_launcher.data

import android.Manifest
import android.app.AlarmManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.CalendarEvent
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEventType
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.TimeBasedEventsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementierung des [com.github.reygnn.kolibri_launcher.domain.repository.TimeBasedEventsRepository].
 *
 * SAFETY FIRST:
 * Wir verwenden hier KEIN runCatching, damit CancellationExceptions (Coroutine Abbruch)
 * sauber durchgereicht werden und nicht als Crash reportet werden.
 */
@Singleton
class TimeBasedEventsRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) : TimeBasedEventsRepository {

    companion object {
        /**
         * Alarm look-ahead window: the next alarm only surfaces when it fires
         * within the next 12h. A far-off alarm (e.g. set for the weekend) is not
         * "upcoming" for an at-a-glance preview, so it is suppressed until it
         * enters the window. Calendar events use a day-based window instead (see
         * [getCalendarEvents]) — today + tomorrow — because "upcoming appointments"
         * is naturally a calendar-day notion, not a rolling-hours one.
         */
        private val ALARM_LOOKAHEAD = DateUtils.HOUR_IN_MILLIS * 12
        private const val MAX_EVENTS_DEFAULT = 5

        /**
         * Packages whose [AlarmManager.getNextAlarmClock] entries are NOT
         * user-facing clock alarms and must not light the alarm indicator.
         *
         * Some system apps schedule via [AlarmManager.setAlarmClock] — the API
         * reserved for user-visible alarms — for their own purposes, so those
         * entries surface through `getNextAlarmClock()` as phantom "alarms".
         * Two confirmed sources:
         * - `com.samsung.android.calendar`: Samsung Calendar's daily midnight
         *   rollover (`ACTION_MIDNIGHT_DATE_CHANGED_FOR_NOTIFICATION`) → a
         *   phantom "alarm" at 00:00 even when the user has set none.
         * - `com.android.providers.calendar`: the calendar provider schedules
         *   each event's REMINDER (`android.intent.action.EVENT_REMINDER`) as an
         *   alarm clock. Because `getNextAlarmClock()` returns only the single
         *   chronologically-next entry, an event reminder that fires before the
         *   user's real clock alarm would otherwise be shown as "the alarm" — at
         *   the reminder's time, not the alarm's (observed on an A17: a 17:00
         *   Samsung Clock alarm displayed as 13:50, the next event's reminder).
         *   The event itself already shows via the calendar path, so dropping the
         *   reminder loses nothing.
         *
         * A third-party launcher only has the
         * [android.app.PendingIntent.getCreatorPackage] of the alarm's
         * `showIntent` to tell them apart. Match by that package and drop it.
         *
         * Blocklist (fail-open) is deliberate: an unrecognised source is treated
         * as a real alarm, so a genuine user alarm is never hidden — a phantom is
         * a nuisance, a missed alarm is not. TRADE-OFF: since `getNextAlarmClock()`
         * yields only the next entry, while a blocklisted source is the next one
         * the real alarm behind it is not visible until the phantom passes (the
         * indicator shows nothing rather than a wrong time). Add further known
         * offenders here as they surface; the discriminator is the PendingIntent
         * creator package, the only cross-OEM signal available. Full rationale +
         * how to confirm a candidate: KNOWN_QUIRKS.md §1.
         */
        private val NON_ALARM_CLOCK_PACKAGES = setOf(
            "com.samsung.android.calendar",
            "com.android.providers.calendar"
        )
    }

    override suspend fun getUpcomingTimeBasedEvents(maxCount: Int): List<TimeBasedEvent> {
        val events = mutableListOf<TimeBasedEvent>()

        // 1. Einstellungen laden (Safely)
        val showAlarm = try {
            settingsRepository.showAlarmFlow.first()
        } catch (e: CancellationException) {
            throw e // Abbruch sofort weiterleiten!
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error reading showAlarm setting")
            true // Fallback
        }

        val showCalendarEvents = try {
            settingsRepository.showCalendarEventFlow.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error reading showCalendarEvent setting")
            false // Fallback
        }

        // 2. Alarm abrufen
        if (showAlarm) {
            try {
                // getNextAlarm() issues a synchronous AlarmManager Binder IPC; this
                // use case is collected on the Main dispatcher (ClockDelegate →
                // launchSafe(mainDispatcher)), so run the call off Main to match the
                // calendar path (getCalendarEvents) and avoid a Main-thread IPC /
                // StrictMode hit. getNextAlarm stays a plain (non-suspend) fun, so
                // its internal catch keeps its "no suspension point" property;
                // cancellation during withContext propagates via the arm below.
                withContext(Dispatchers.IO) { getNextAlarm() }?.let { events.add(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error adding alarm")
            }
        }

        // 3. Kalender abrufen
        if (showCalendarEvents) {
            try {
                // getCalendarEvents läuft auf Dispatchers.IO, ist also safe
                val calendarEvents = getCalendarEvents(maxCount)

                // Mapping auf TimeBasedEvent
                calendarEvents.forEach { calEvent ->
                    events.add(
                        TimeBasedEvent(
                            triggerTimeMillis = calEvent.startTimeMillis,
                            title = calEvent.title,
                            type = TimeBasedEventType.CALENDAR,
                            isAllDay = calEvent.isAllDay
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error adding calendar events")
            }
        }

        // 4. Sortieren & Limitieren (CPU bound, safe)
        return events.sortedBy { it.triggerTimeMillis }.take(maxCount)
    }

    private fun getNextAlarm(): TimeBasedEvent? {
        // System-Service Zugriff ist schnell, aber sicherheitshalber try-catch
        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

            if (alarmManager == null) {
                return null
            }

            val nextAlarm = alarmManager.nextAlarmClock ?: return null

            // Drop phantom "alarms" that OEM system apps schedule via
            // setAlarmClock() for internal purposes (see NON_ALARM_CLOCK_PACKAGES).
            // showIntent/creatorPackage may be null for legitimate alarms too, so a
            // null here means "unknown source" and is kept (fail-open).
            val creatorPackage = nextAlarm.showIntent?.creatorPackage
            if (creatorPackage != null && creatorPackage in NON_ALARM_CLOCK_PACKAGES) {
                return null
            }

            // Only surface the alarm when it fires within the look-ahead window.
            // getNextAlarmClock() returns the single next alarm regardless of how
            // far off it is; a far-future alarm is not "upcoming" for the preview.
            val now = System.currentTimeMillis()
            if (nextAlarm.triggerTime > now + ALARM_LOOKAHEAD) {
                return null
            }

            TimeBasedEvent(
                // Empty = "no title". The UI layer resolves a localized fallback by
                // type (an alarm has no per-instance title from AlarmClock); keeping
                // a display string out of :data honours the no-Android-resources rule.
                triggerTimeMillis = nextAlarm.triggerTime,
                title = "",
                type = TimeBasedEventType.ALARM
            )
        } catch (e: Exception) { // CancellationException ist hier unwahrscheinlich, aber Exception fängt alles außer Errors
            // No suspension point: getNextAlarm is a plain fun with only
            // synchronous system-service calls — cancellation cannot arise here
            // (AUDIT-12 whitelist review).
            TimberWrapper.silentError(e, "Error getting next alarm")
            null
        }
    }

    private suspend fun getCalendarEvents(maxCount: Int): List<CalendarEvent> {
        // Berechtigungsprüfung
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        return try {
            withContext(Dispatchers.IO) {
                val projection = arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY
                )

                // Window: today + tomorrow. Query the whole span from the start of
                // today so today's all-day events (whose instance begins at local
                // midnight, already in the past) are still returned; timed events
                // that already passed today are dropped in the per-row filter below.
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val tomorrow = today.plusDays(1)
                val now = System.currentTimeMillis()
                val rangeStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
                // Exclusive upper bound: start of the day after tomorrow.
                val rangeEnd = today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()

                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                    .also {
                        ContentUris.appendId(it, rangeStart)
                        ContentUris.appendId(it, rangeEnd)
                    }.build()

                // No ALL_DAY filter: all-day events are now included. Both kinds are
                // separated and filtered per-row below.
                val sortOrder = "${CalendarContract.Instances.BEGIN} ASC"

                context.contentResolver.query(
                    uri,
                    projection,
                    null,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val events = mutableListOf<CalendarEvent>()
                    val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                    val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                    val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                    val allDayIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)

                    while (cursor.moveToNext() && events.size < maxCount) {
                        // Einzelne Row-Fehler sollten nicht alles abbrechen
                        try {
                            val begin = cursor.getLong(beginIdx)
                            val end = cursor.getLong(endIdx)
                            val isAllDay = cursor.getInt(allDayIdx) == 1

                            // Per-row window filter:
                            // - all-day: keep only if its date is today or tomorrow.
                            //   The provider stores an all-day BEGIN as UTC midnight,
                            //   so its calendar date is read back in UTC (not the
                            //   local zone) to avoid an off-by-one day at the edges.
                            // - timed: keep while the event has not yet ended (end > now),
                            //   so an appointment that is currently in progress stays
                            //   visible until its end time — a late joiner still sees it.
                            //   Only an event that already ended today is dropped.
                            val keep = if (isAllDay) {
                                val date = Instant.ofEpochMilli(begin)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate()
                                date == today || date == tomorrow
                            } else {
                                end > now
                            }
                            if (!keep) continue

                            events.add(
                                CalendarEvent(
                                    // Empty = untitled → UI resolves a localized
                                    // fallback (no display strings in :data).
                                    title = cursor.getString(titleIdx).orEmpty(),
                                    startTimeMillis = begin,
                                    endTimeMillis = end,
                                    isAllDay = isAllDay
                                )
                            )
                        } catch (e: Exception) {
                            // No suspension point: the guarded body only reads
                            // synchronous cursor columns — cancellation cannot
                            // arise here (AUDIT-12 whitelist review).
                            // Ignoriere defekte Zeilen
                        }
                    }
                    events
                } ?: emptyList()
            }
        } catch (e: CancellationException) {
            throw e // WICHTIG: IO-Cancellation weiterleiten
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error querying calendar provider")
            emptyList()
        }
    }

    override suspend fun purgeRepository() {
        // No-op
    }
}