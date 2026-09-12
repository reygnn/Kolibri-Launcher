package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.launcher.core.timeinfo.TimeInfoSettings
import kotlinx.coroutines.flow.Flow

/**
 * User preferences (DataStore-backed): the monochrome-icons toggle plus the two
 * home-info flags. Implements the shared [TimeInfoSettings] port (HIE-INV-2) so
 * the shared ObserveTimeBasedEventsUseCase reads Nyx's toggles without importing
 * this product interface.
 */
interface PreferencesRepository : TimeInfoSettings {
    fun monochromeIcons(): Flow<Boolean>
    suspend fun setMonochromeIcons(enabled: Boolean)

    // TimeInfoSettings: showAlarmFlow, showCalendarEventFlow (read side).
    suspend fun setShowAlarm(enabled: Boolean)
    suspend fun setShowCalendarEvent(enabled: Boolean)
}
