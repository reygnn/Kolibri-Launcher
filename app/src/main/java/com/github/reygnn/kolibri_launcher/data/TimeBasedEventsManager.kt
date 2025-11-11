package com.github.reygnn.kolibri_launcher.data

import android.app.AlarmManager
import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.EventType
import com.github.reygnn.kolibri_launcher.domain.TimeBasedEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import java.util.concurrent.CancellationException as JavaCancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementierung des [TimeBasedEventsRepository].
 * Verwendet Hilt für die Injektion des App-Kontexts.
 */
@Singleton
class TimeBasedEventsManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) : TimeBasedEventsRepository {

    companion object {
        private val QUERY_DURATION = DateUtils.HOUR_IN_MILLIS * 12
        private const val MAX_EVENTS_DEFAULT = 5
    }

    override suspend fun getUpcomingTimeBasedEvents(maxCount: Int): List<TimeBasedEvent> {
        return runCatching {
            val events = mutableListOf<TimeBasedEvent>()

            // 1. Alarm hinzufügen (falls vorhanden)
            try {
                getNextAlarm()?.let { alarm ->
                    events.add(alarm)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error adding alarm to events")
                // Weiter mit Kalenderterminen
            }

            // 2. Kalendertermine hinzufügen
            try {
                val calendarEvents = getCalendarEvents(maxCount)
                calendarEvents.forEach { calEvent ->
                    events.add(
                        TimeBasedEvent(
                            triggerTimeMillis = calEvent.startTimeMillis,
                            title = calEvent.title,
                            type = EventType.CALENDAR
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error adding calendar events")
                // Weiter - vielleicht haben wir ja den Alarm
            }

            // 3. Chronologisch sortieren und limitieren
            events.sortedBy { it.triggerTimeMillis }.take(maxCount)

        }.getOrElse { e ->
            if (e is CancellationException || e is JavaCancellationException) throw e
            TimberWrapper.silentError(e, "Error getting time-based events")
            emptyList()
        }
    }

    /**
     * Private: Ruft den nächsten Alarm ab.
     */
    private fun getNextAlarm(): TimeBasedEvent? {
        return runCatching {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

            if (alarmManager == null) {
                TimberWrapper.silentError("AlarmManager service is null")
                return@runCatching null
            }

            val nextAlarm = alarmManager.nextAlarmClock

            if (nextAlarm == null) {
                // Kein Alarm gesetzt - das ist normal, kein Error
                return@runCatching null
            }

            TimeBasedEvent(
                triggerTimeMillis = nextAlarm.triggerTime,
                title = "Alarm", // System gibt uns keinen Alarm-Namen
                type = EventType.ALARM
            )

        }.getOrElse { e ->
            if (e is CancellationException || e is JavaCancellationException) throw e
            TimberWrapper.silentError(e, "Error getting next alarm")
            null
        }
    }

    /**
     * Private Helper: Lädt Kalendertermine für die nächsten 12 Stunden.
     */
    private suspend fun getCalendarEvents(maxCount: Int): List<CalendarEvent> {
        // Berechtigungsprüfung
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            TimberWrapper.silentError("Fehlende READ_CALENDAR Berechtigung")
            return emptyList()
        }

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

        return try {
            withContext(Dispatchers.IO) {
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
                        try {
                            events.add(
                                CalendarEvent(
                                    title = cursor.getString(titleIdx) ?: "Unbekannt",
                                    startTimeMillis = cursor.getLong(beginIdx),
                                    endTimeMillis = cursor.getLong(endIdx)
                                )
                            )
                        } catch (e: Throwable) {
                            TimberWrapper.silentError(e, "Fehler beim Parsen eines Events")
                            // Überspringe diesen Termin, mache mit dem nächsten weiter
                        }
                    }

                    events
                } ?: emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Fehler beim Abfragen der Kalender-Instanzen")
            emptyList()
        }
    }
}