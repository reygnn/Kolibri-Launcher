package com.github.reygnn.kolibri_launcher.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementierung des [CalendarRepository].
 * Verwendet Hilt für die Injektion des App-Kontexts.
 */
@Singleton
class CalendarManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) : CalendarRepository {

    companion object {
        private val QUERY_DURATION = DateUtils.HOUR_IN_MILLIS * 12
        private const val MAX_EVENTS_DEFAULT = 5
    }

    override suspend fun getNextUpcomingEvent(): CalendarEvent? {
        return getUpcomingEvents(maxCount = 1).firstOrNull()
    }

    override suspend fun getUpcomingEvents(maxCount: Int): List<CalendarEvent> {
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