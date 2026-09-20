package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.launcher.core.timeinfo.TimeInfoSettings
import com.github.reygnn.nyx_launcher.home.model.IconStyle
import kotlinx.coroutines.flow.Flow

/**
 * User preferences (DataStore-backed): the icon-style mode plus the two
 * home-info flags. Implements the shared [TimeInfoSettings] port (HIE-INV-2) so
 * the shared ObserveTimeBasedEventsUseCase reads Nyx's toggles without importing
 * this product interface.
 */
interface PreferencesRepository : TimeInfoSettings {
    /** How app icons are rendered (colour / monochrome / grayscale). Defaults to [IconStyle.COLOR]. */
    fun iconStyle(): Flow<IconStyle>
    suspend fun setIconStyle(style: IconStyle)

    /**
     * When enabled, a drawer search that narrows to exactly one app launches it
     * immediately (DRAWER_FOLDERS_SPEC §10 D-3, mirrors kolibri's auto-launch).
     * Defaults to off.
     */
    fun searchAutoLaunch(): Flow<Boolean>
    suspend fun setSearchAutoLaunch(enabled: Boolean)

    /**
     * When enabled, the drawer's loose apps are ordered by time-weighted usage (most-used
     * first) instead of alphabetically (mirrors kolibri's TIME_WEIGHTED_USAGE). Toggled from
     * the drawer overflow menu. Defaults to off (alphabetical).
     */
    fun usageSortEnabled(): Flow<Boolean>
    suspend fun setUsageSortEnabled(enabled: Boolean)

    /**
     * When enabled, app icons show a small dot when their app has an active
     * (non-ongoing) notification (mirrors Pixel's notification dots). Requires the
     * user to grant notification access; the toggle only gates rendering. Off by default.
     */
    fun notificationDots(): Flow<Boolean>
    suspend fun setNotificationDots(enabled: Boolean)

    // TimeInfoSettings: showAlarmFlow, showCalendarEventFlow (read side).
    suspend fun setShowAlarm(enabled: Boolean)
    suspend fun setShowCalendarEvent(enabled: Boolean)
}
