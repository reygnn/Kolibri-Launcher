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
    @ApplicationContext private val context: Context
) : CalendarRepository {

    companion object {
        // Wie weit in die Zukunft soll gesucht werden? 12 Stunden sind
        // ein guter Wert für eine "At-a-Glance"-Anzeige.
        private val QUERY_DURATION = DateUtils.HOUR_IN_MILLIS * 12
    }

    override suspend fun getNextUpcomingEvent(): CalendarEvent? {
        // 1. Berechtigungsprüfung: Absolut sicherstellen, dass wir die
        // Berechtigung haben. Wenn nicht, sofort 'null' zurückgeben.
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            TimberWrapper.silentError("Fehlende READ_CALENDAR Berechtigung. Kann keine Termine abfragen.")
            return null
        }

        // 2. Abfrage-Parameter definieren
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END
        )

        val now = System.currentTimeMillis()
        val endOfQueryRange = now + QUERY_DURATION

        // 3. URI für die 'Instances'-Tabelle erstellen.
        // WICHTIG: 'Instances' verwenden, nicht 'Events'. 'Instances'
        // löst auch wiederkehrende Termine korrekt auf.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .also {
                ContentUris.appendId(it, now)
                ContentUris.appendId(it, endOfQueryRange)
            }.build()

        // 4. Nur Termine, die nicht "ganztägig" sind
        val selection = "${CalendarContract.Instances.ALL_DAY} = 0"

        // 5. Nach dem Startdatum sortieren, um den *nächsten* Termin zu erhalten
        val sortOrder = "${CalendarContract.Instances.BEGIN} ASC"

        // 6. Die Abfrage sicher auf dem I/O-Thread ausführen
        return try {
            withContext(Dispatchers.IO) {
                context.contentResolver.query(
                    uri,
                    projection,
                    selection,
                    null,
                    sortOrder
                )?.use { cursor -> // 'use' stellt sicher, dass der Cursor geschlossen wird

                    if (cursor.moveToFirst()) {
                        // Spalten-Indizes sicher abrufen
                        val titleIdx =
                            cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                        val beginIdx =
                            cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                        val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)

                        // Das Event-Objekt erstellen und zurückgeben
                        CalendarEvent(
                            title = cursor.getString(titleIdx),
                            startTimeMillis = cursor.getLong(beginIdx),
                            endTimeMillis = cursor.getLong(endIdx)
                        )
                    } else {
                        // Kein Termin im Zeitfenster gefunden
                        null
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e // Coroutine-Abbrüche müssen weitergeleitet werden
        } catch (e: Throwable) {
            // Alle anderen Fehler (SecurityException, IllegalStateException, etc.) abfangen
            TimberWrapper.silentError(e, "Fehler beim Abfragen der Kalender-Instanzen")
            null // Bei Fehler 'null' zurückgeben
        }
    }
}