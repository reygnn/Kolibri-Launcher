package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.TimeBasedEvent

/**
 * Interface für das Repository, das Kalenderdaten abruft.
 * Das ViewModel wird gegen dieses Interface programmieren.
 */
interface TimeBasedEventsRepository {
    /**
     * Kombiniert Alarme und Kalendertermine zu einer chronologisch sortierten Liste.
     *
     * @param maxCount Maximale Anzahl der zurückzugebenden Events
     * @return Liste von TimeBasedEvent, chronologisch sortiert
     */
    suspend fun getUpcomingTimeBasedEvents(maxCount: Int = 5): List<TimeBasedEvent>
}
