package com.github.reygnn.kolibri_launcher.contract

import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEventType
import com.github.reygnn.kolibri_launcher.domain.repository.TimeBasedEventsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeTimeBasedEventsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract Tests für TimeBasedEventsRepository.
 *
 * Kombiniert Alarme und Kalendertermine zu einer chronologisch sortierten Liste.
 */
abstract class TimeBasedEventsRepositoryContractTest {

    abstract fun createRepository(): TimeBasedEventsRepository

    abstract fun setEvents(repo: TimeBasedEventsRepository, events: List<TimeBasedEvent>)

    // Test-Helfer
    private fun createEvent(
        title: String,
        triggerTimeMillis: Long,
        type: TimeBasedEventType = TimeBasedEventType.CALENDAR
    ) = TimeBasedEvent(
        title = title,
        triggerTimeMillis = triggerTimeMillis,
        type = type
    )

    // ===========================================
    // INITIAL STATE
    // ===========================================

    @Test
    fun `getUpcomingTimeBasedEvents - returns empty list initially`() = runTest {
        val repo = createRepository()

        val result = repo.getUpcomingTimeBasedEvents()

        assertTrue(result.isEmpty())
    }

    // ===========================================
    // GET EVENTS
    // ===========================================

    @Test
    fun `getUpcomingTimeBasedEvents - returns set events`() = runTest {
        val repo = createRepository()
        val events = listOf(
            createEvent("Event 1", 1000L),
            createEvent("Event 2", 2000L)
        )
        setEvents(repo, events)

        val result = repo.getUpcomingTimeBasedEvents()

        assertEquals(2, result.size)
    }

    @Test
    fun `getUpcomingTimeBasedEvents - returns events sorted by time`() = runTest {
        val repo = createRepository()
        val events = listOf(
            createEvent("Later", 3000L),
            createEvent("First", 1000L),
            createEvent("Middle", 2000L)
        )
        setEvents(repo, events)

        val result = repo.getUpcomingTimeBasedEvents()

        assertEquals("First", result[0].title)
        assertEquals("Middle", result[1].title)
        assertEquals("Later", result[2].title)
    }

    @Test
    fun `getUpcomingTimeBasedEvents - respects maxCount`() = runTest {
        val repo = createRepository()
        val events = listOf(
            createEvent("Event 1", 1000L),
            createEvent("Event 2", 2000L),
            createEvent("Event 3", 3000L),
            createEvent("Event 4", 4000L),
            createEvent("Event 5", 5000L)
        )
        setEvents(repo, events)

        val result = repo.getUpcomingTimeBasedEvents(maxCount = 3)

        assertEquals(3, result.size)
    }

    @Test
    fun `getUpcomingTimeBasedEvents - maxCount returns earliest events`() = runTest {
        val repo = createRepository()
        val events = listOf(
            createEvent("Late", 5000L),
            createEvent("Early", 1000L),
            createEvent("Middle", 3000L)
        )
        setEvents(repo, events)

        val result = repo.getUpcomingTimeBasedEvents(maxCount = 2)

        assertEquals(2, result.size)
        assertEquals("Early", result[0].title)
        assertEquals("Middle", result[1].title)
    }

    @Test
    fun `getUpcomingTimeBasedEvents - default maxCount is 5`() = runTest {
        val repo = createRepository()
        val events = (1..10).map { createEvent("Event $it", it * 1000L) }
        setEvents(repo, events)

        val result = repo.getUpcomingTimeBasedEvents()

        assertEquals(5, result.size)
    }

    @Test
    fun `getUpcomingTimeBasedEvents - returns fewer than maxCount if not enough events`() = runTest {
        val repo = createRepository()
        val events = listOf(
            createEvent("Only One", 1000L)
        )
        setEvents(repo, events)

        val result = repo.getUpcomingTimeBasedEvents(maxCount = 10)

        assertEquals(1, result.size)
    }

    @Test
    fun `getUpcomingTimeBasedEvents - handles mixed event types`() = runTest {
        val repo = createRepository()
        val events = listOf(
            createEvent("Alarm", 1000L, TimeBasedEventType.ALARM),
            createEvent("Meeting", 2000L, TimeBasedEventType.CALENDAR)
        )
        setEvents(repo, events)

        val result = repo.getUpcomingTimeBasedEvents()

        assertEquals(TimeBasedEventType.ALARM, result[0].type)
        assertEquals(TimeBasedEventType.CALENDAR, result[1].type)
    }

    // ===========================================
    // PURGE REPOSITORY
    // ===========================================

    @Test
    fun `purgeRepository - clears all events`() = runTest {
        val repo = createRepository()
        setEvents(repo, listOf(
            createEvent("Event 1", 1000L),
            createEvent("Event 2", 2000L)
        ))

        repo.purgeRepository()

        assertTrue(repo.getUpcomingTimeBasedEvents().isEmpty())
    }
}

/**
 * Verifiziert den Fake
 */
class FakeTimeBasedEventsRepositoryContractTest : TimeBasedEventsRepositoryContractTest() {

    override fun createRepository(): TimeBasedEventsRepository = FakeTimeBasedEventsRepository()

    override fun setEvents(repo: TimeBasedEventsRepository, events: List<TimeBasedEvent>) {
        (repo as FakeTimeBasedEventsRepository).setEvents(events)
    }
}