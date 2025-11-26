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
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEventType
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.TimeBasedEventsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
class TimeBasedEventsManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsManager: SettingsRepository
) : TimeBasedEventsRepository {

    companion object {
        private val QUERY_DURATION = DateUtils.HOUR_IN_MILLIS * 12
        private const val MAX_EVENTS_DEFAULT = 5
    }

    override suspend fun getUpcomingTimeBasedEvents(maxCount: Int): List<TimeBasedEvent> {
        val events = mutableListOf<TimeBasedEvent>()

        // 1. Einstellungen laden (Safely)
        val showAlarm = try {
            settingsManager.showAlarmFlow.first()
        } catch (e: CancellationException) {
            throw e // Abbruch sofort weiterleiten!
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error reading showAlarm setting")
            true // Fallback
        }

        val showCalendarEvents = try {
            settingsManager.showCalendarEventFlow.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error reading showCalendarEvent setting")
            false // Fallback
        }

        // 2. Alarm abrufen
        if (showAlarm) {
            try {
                getNextAlarm()?.let { events.add(it) }
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
                            type = TimeBasedEventType.CALENDAR
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

            TimeBasedEvent(
                triggerTimeMillis = nextAlarm.triggerTime,
                title = "Alarm",
                type = TimeBasedEventType.ALARM
            )
        } catch (e: Exception) { // CancellationException ist hier unwahrscheinlich, aber Exception fängt alles außer Errors
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
                    CalendarContract.Instances.END
                )

                val now = System.currentTimeMillis()
                val endOfQueryRange = now + QUERY_DURATION

                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                    .also {
                        ContentUris.appendId(it, now)
                        ContentUris.appendId(it, endOfQueryRange)
                    }.build()

                val selection = "${CalendarContract.Instances.ALL_DAY} = 0"
                val sortOrder = "${CalendarContract.Instances.BEGIN} ASC"

                context.contentResolver.query(
                    uri,
                    projection,
                    selection,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val events = mutableListOf<CalendarEvent>()
                    val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                    val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                    val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)

                    while (cursor.moveToNext() && events.size < maxCount) {
                        // Einzelne Row-Fehler sollten nicht alles abbrechen
                        try {
                            events.add(
                                CalendarEvent(
                                    title = cursor.getString(titleIdx) ?: "Event",
                                    startTimeMillis = cursor.getLong(beginIdx),
                                    endTimeMillis = cursor.getLong(endIdx)
                                )
                            )
                        } catch (e: Exception) {
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