package com.github.reygnn.kolibri_launcher.data

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
}