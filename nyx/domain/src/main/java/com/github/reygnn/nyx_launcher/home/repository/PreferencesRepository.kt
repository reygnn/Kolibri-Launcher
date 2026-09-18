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
     * When enabled, the home is locked to portrait (mirrors kolibri's rotation-lock);
     * when off, the launcher follows the sensor and the device grid re-fits to the new
     * orientation. Defaults to [DEFAULT_ROTATION_LOCKED].
     */
    fun rotationLocked(): Flow<Boolean>
    suspend fun setRotationLocked(enabled: Boolean)

    // TimeInfoSettings: showAlarmFlow, showCalendarEventFlow (read side).
    suspend fun setShowAlarm(enabled: Boolean)
    suspend fun setShowCalendarEvent(enabled: Boolean)

    companion object {
        /**
         * Rotation is locked to portrait BY DEFAULT in nyx — a deliberate deviation from
         * the shared `AppConstants.DEFAULT_ROTATION_LOCKED = false` (kolibri's default).
         * nyx's home grid re-packs on an orientation change (HomeLayoutRegridder keeps the
         * apps but NOT their positions), so allowing rotation by default would let a
         * portrait→landscape→portrait round-trip scramble a curated layout. Locked-by-
         * default keeps the arrangement stable; rotation is opt-in for users who want it.
         */
        const val DEFAULT_ROTATION_LOCKED = true
    }
}
