package com.github.reygnn.kolibri_launcher.domain.usecase

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEventType
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeTimeBasedEventsRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
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

    // =========================================================================
    // distinctUntilChanged-Dedup pro Settings-Input (Regression 2b977bc0)
    //
    // The shared settings DataStore emits a fresh Preferences on ANY key write
    // (e.g. a slider drag firing many times a second), so showAlarmFlow /
    // showCalendarEventFlow re-emit their UNCHANGED values on unrelated changes.
    // Without the per-input distinctUntilChanged, each identical re-emission
    // makes flatMapLatest cancel+restart the inner flow and re-issue the
    // calendar/alarm provider IPC. FakeSettingsRepository can't reproduce this —
    // its MutableStateFlow conflates identical writes — so these two tests drive
    // flows that CAN re-emit duplicates (MutableSharedFlow via a MockK double).
    // =========================================================================

    @Test
    fun `invoke deduplicates identical setting re-emissions and queries repository once`() = runTest {
        // Arrange: settings flows that re-emit identical values, unlike StateFlow.
        val alarmFlow = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 8)
        val calendarFlow = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 8)
        val settings = mockk<SettingsRepository>(relaxed = true) {
            every { showAlarmFlow } returns alarmFlow
            every { showCalendarEventFlow } returns calendarFlow
        }
        timeBasedEventsRepository.setEvents(testEvents)
        val useCase = ObserveTimeBasedEventsUseCase(settings, timeBasedEventsRepository)

        alarmFlow.emit(true)
        calendarFlow.emit(false)

        // Act & Assert
        useCase().test {
            // Initial combine → one fetch.
            assertThat(awaitItem()).hasSize(2)

            // Re-emit the SAME values (unrelated store writes).
            alarmFlow.emit(true)
            alarmFlow.emit(true)
            calendarFlow.emit(false)
            advanceUntilIdle()

            // distinctUntilChanged collapses the duplicates: no restart, no re-fetch.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        // Pre-fix this was 3 (one IPC per identical re-emission).
        assertThat(timeBasedEventsRepository.getEventsCallCount).isEqualTo(1)
    }

    @Test
    fun `refresh re-queries repository even when settings values are unchanged`() = runTest {
        // Counter-check: the dedup is per settings input, NOT on the combined Pair —
        // deduping the Pair would swallow a refreshTrigger-only re-emission and
        // break refresh() when the settings happen to be unchanged.
        settingsRepository.showAlarm = true
        timeBasedEventsRepository.setEvents(testEvents)

        useCase().test {
            assertThat(awaitItem()).hasSize(2)

            // Settings unchanged — only the refresh trigger fires.
            useCase.refresh()
            assertThat(awaitItem()).hasSize(2)

            cancelAndIgnoreRemainingEvents()
        }

        // Must be 2: refresh is not swallowed by the per-input dedup.
        assertThat(timeBasedEventsRepository.getEventsCallCount).isEqualTo(2)
    }
}