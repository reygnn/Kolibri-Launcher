package com.github.reygnn.launcher.core.timeinfo

import com.github.reygnn.launcher.core.TimberWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

class ObserveTimeBasedEventsUseCase @Inject constructor(
    private val settings: TimeInfoSettings,
    private val timeBasedEventsRepository: TimeBasedEventsRepository
) {
    private val refreshTrigger = MutableSharedFlow<Unit>(replay = 1)

    init {
        refreshTrigger.tryEmit(Unit) // Initial trigger
    }

    /**
     * Triggert einen manuellen Reload der Events.
     * Wird z.B. aus refreshDynamicUiData() aufgerufen.
     */
    fun refresh() {
        refreshTrigger.tryEmit(Unit)
    }

    /**
     * Gibt einen Flow von Kalender-Events zurück, der automatisch aktualisiert wird,
     * wenn sich die Einstellungen (showAlarm/showCalendar) ändern.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(maxCount: Int = 5): Flow<List<TimeBasedEvent>> {
        // 1. Kombiniere die Einstellungs-Flows (Logik aus 'observeEventSettings')
        // distinctUntilChanged per settings input: the shared settings store emits a
        // fresh Preferences on ANY key write (e.g. a slider drag firing many times a
        // second), so showAlarmFlow/showCalendarEventFlow re-emit their unchanged
        // values on unrelated changes. Without deduping, each identical re-emission
        // makes flatMapLatest cancel+restart the inner flow and re-issue the
        // calendar/alarm provider IPC. Dedupe each input individually — deduping the
        // combined Pair would swallow refreshTrigger-only re-emissions.
        return combine(
            settings.showAlarmFlow.distinctUntilChanged(),
            settings.showCalendarEventFlow.distinctUntilChanged(),
            refreshTrigger
        ) { showAlarm, showCalendar, _ ->
            // 2. Erzeuge ein Paar (Pair) aus den Ergebnissen
            Pair(showAlarm, showCalendar)
        }
            // 3. Nutze flatMapLatest, um bei Einstellungsänderung den Flow neu zu starten
            .flatMapLatest { (showAlarm, showCalendar) ->
                flow {
                    try {
                        // 4. Die Logik aus 'updateCalendarEvent' ist jetzt hier
                        if (!showAlarm && !showCalendar) {
                            emit(emptyList())
                        } else {
                            // Das Repo kümmert sich um die Details (welche aktiv sind)
                            val events = timeBasedEventsRepository.getUpcomingTimeBasedEvents(maxCount)
                            emit(events)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Failed to update time-based events")
                        emit(emptyList()) // Im Fehlerfall leere Liste senden
                    }
                }
            }
    }
}
