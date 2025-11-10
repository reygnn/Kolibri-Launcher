package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.TimeBasedEvent

/**
 * Interface für das Repository, das Kalenderdaten abruft.
 * Das ViewModel wird gegen dieses Interface programmieren.
 */
interface CalendarRepository {

    /**
     * Ruft den nächsten anstehenden Kalendertermin ab.
     *
     * @return Ein [CalendarEvent]-Objekt, wenn ein Termin gefunden wurde,
     * oder 'null', wenn kein Termin ansteht oder die Berechtigung fehlt.
     */
    suspend fun getNextUpcomingEvent(): CalendarEvent?
    suspend fun getUpcomingEvents(maxCount: Int = 5): List<CalendarEvent>

    suspend fun getNextAlarm(): TimeBasedEvent?
    suspend fun getUpcomingTimeBasedEvents(maxCount: Int = 5): List<TimeBasedEvent>
}
