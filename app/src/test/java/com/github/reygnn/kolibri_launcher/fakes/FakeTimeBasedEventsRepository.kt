package com.github.reygnn.kolibri_launcher.fakes

import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import com.github.reygnn.kolibri_launcher.domain.repository.TimeBasedEventsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeTimeBasedEventsRepository @Inject constructor() : TimeBasedEventsRepository, Purgeable {
    private var events = emptyList<TimeBasedEvent>()

    // Tracking für Tests
    var throwOnGetEvents: Throwable? = null
    var getEventsCallCount = 0
        private set
    var lastMaxCount: Int? = null
        private set

    /**
     * Gibt die gespeicherten Events zurück, sortiert und limitiert,
     * genau wie die echte Implementierung es tun würde.
     */
    override suspend fun getUpcomingTimeBasedEvents(maxCount: Int): List<TimeBasedEvent> {
        getEventsCallCount++
        lastMaxCount = maxCount
        throwOnGetEvents?.let { throw it }
        return events.sortedBy { it.triggerTimeMillis }.take(maxCount)
    }

    /**
     * Setzt den Zustand des Fakes für den nächsten Test zurück.
     */
    override suspend fun purgeRepository() {
        events = emptyList()
        getEventsCallCount = 0
        lastMaxCount = null
        throwOnGetEvents = null
    }

    /**
     * Eine Helferfunktion, um den Zustand des Fakes vorzubereiten.
     */
    fun setEvents(events: List<TimeBasedEvent>) {
        this.events = events
    }
}
