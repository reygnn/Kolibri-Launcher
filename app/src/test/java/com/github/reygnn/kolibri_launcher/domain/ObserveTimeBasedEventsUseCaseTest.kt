package com.github.reygnn.kolibri_launcher.domain.usecase

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEventType
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeTimeBasedEventsRepository
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveTimeBasedEventsUseCaseTest {

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var timeBasedEventsRepository: FakeTimeBasedEventsRepository
    private lateinit var useCase: ObserveTimeBasedEventsUseCase

    private val testEvents = listOf(
        TimeBasedEvent(
            triggerTimeMillis = System.currentTimeMillis() + 3600000,
            title = "Alarm",
            type = TimeBasedEventType.ALARM
        ),
        TimeBasedEvent(
            triggerTimeMillis = System.currentTimeMillis() + 7200000,
            title = "Meeting",
            type = TimeBasedEventType.CALENDAR
        )
    )

    @Before
    fun setup() {
        settingsRepository = FakeSettingsRepository()
        timeBasedEventsRepository = FakeTimeBasedEventsRepository()
        useCase = ObserveTimeBasedEventsUseCase(settingsRepository, timeBasedEventsRepository)
    }

    // =========================================================================
    // Beide Einstellungen aus
    // =========================================================================

    @Test
    fun `invoke emits empty list when both settings disabled`() = runTest {
        // Arrange
        settingsRepository.showAlarm = false
        settingsRepository.showCalendar = false

        // Act & Assert
        useCase().test {
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `invoke does not call repository when both settings disabled`() = runTest {
        // Arrange
        settingsRepository.showAlarm = false
        settingsRepository.showCalendar = false

        // Act
        useCase().test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        // Assert
        assertThat(timeBasedEventsRepository.getEventsCallCount).isEqualTo(0)
    }

    // =========================================================================
    // Nur Alarm aktiviert
    // =========================================================================

    @Test
    fun `invoke fetches events when only alarm enabled`() = runTest {
        // Arrange
        settingsRepository.showAlarm = true
        settingsRepository.showCalendar = false
        timeBasedEventsRepository.setEvents(testEvents)

        // Act & Assert
        useCase().test {
            val result = awaitItem()
            assertThat(result).hasSize(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Nur Kalender aktiviert
    // =========================================================================

    @Test
    fun `invoke fetches events when only calendar enabled`() = runTest {
        // Arrange
        settingsRepository.showAlarm = false
        settingsRepository.showCalendar = true
        timeBasedEventsRepository.setEvents(testEvents)

        // Act & Assert
        useCase().test {
            val result = awaitItem()
            assertThat(result).hasSize(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Beide aktiviert
    // =========================================================================

    @Test
    fun `invoke fetches events when both enabled`() = runTest {
        // Arrange
        settingsRepository.showAlarm = true
        settingsRepository.showCalendar = true
        timeBasedEventsRepository.setEvents(testEvents)

        // Act & Assert
        useCase().test {
            val result = awaitItem()
            assertThat(result).hasSize(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // maxCount Parameter
    // =========================================================================

    @Test
    fun `invoke passes maxCount to repository`() = runTest {
        // Arrange
        settingsRepository.showAlarm = true

        // Act
        useCase(maxCount = 10).test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        // Assert
        assertThat(timeBasedEventsRepository.lastMaxCount).isEqualTo(10)
    }

    @Test
    fun `invoke uses default maxCount of 5`() = runTest {
        // Arrange
        settingsRepository.showAlarm = true

        // Act
        useCase().test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        // Assert
        assertThat(timeBasedEventsRepository.lastMaxCount).isEqualTo(5)
    }

    // =========================================================================
    // Reaktivität bei Einstellungsänderungen
    // =========================================================================

    @Test
    fun `invoke emits new events when settings change`() = runTest {
        // Arrange
        settingsRepository.showAlarm = false
        settingsRepository.showCalendar = false
        timeBasedEventsRepository.setEvents(testEvents)

        // Act & Assert
        useCase().test {
            // Initial: beide aus → leer
            assertThat(awaitItem()).isEmpty()

            // Alarm aktivieren → Events
            settingsRepository.showAlarm = true
            assertThat(awaitItem()).hasSize(2)

            // Alarm deaktivieren → leer
            settingsRepository.showAlarm = false
            assertThat(awaitItem()).isEmpty()

            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Refresh
    // =========================================================================

    @Test
    fun `refresh triggers new emission`() = runTest {
        // Arrange
        settingsRepository.showAlarm = true
        val initialEvents = listOf(
            TimeBasedEvent(
                triggerTimeMillis = System.currentTimeMillis(),
                title = "Alarm 1",
                type = TimeBasedEventType.ALARM
            )
        )
        val updatedEvents = listOf(
            TimeBasedEvent(
                triggerTimeMillis = System.currentTimeMillis(),
                title = "Alarm 1",
                type = TimeBasedEventType.ALARM
            ),
            TimeBasedEvent(
                triggerTimeMillis = System.currentTimeMillis() + 1000,
                title = "Alarm 2",
                type = TimeBasedEventType.ALARM
            )
        )
        timeBasedEventsRepository.setEvents(initialEvents)

        // Act & Assert
        useCase().test {
            assertThat(awaitItem()).hasSize(1)

            // Aktualisiere Events und triggere Refresh
            timeBasedEventsRepository.setEvents(updatedEvents)
            useCase.refresh()

            assertThat(awaitItem()).hasSize(2)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // =========================================================================
    // Fehlerbehandlung
    // =========================================================================

    @Test
    fun `invoke emits empty list on error`() = runTest {
        // Arrange
        settingsRepository.showAlarm = true
        timeBasedEventsRepository.throwOnGetEvents = RuntimeException("Permission denied")

        // Act & Assert
        useCase().test {
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }
}