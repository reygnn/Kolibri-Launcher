package com.github.reygnn.nyx_launcher.home.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [PreferencesRepository] test double. */
class FakePreferencesRepository(
    monochrome: Boolean = false,
    showAlarm: Boolean = false,
    showCalendarEvent: Boolean = false,
    searchAutoLaunch: Boolean = false,
    usageSort: Boolean = false,
    notificationDots: Boolean = false,
) : PreferencesRepository {
    private val monochromeState = MutableStateFlow(monochrome)
    private val showAlarmState = MutableStateFlow(showAlarm)
    private val showCalendarState = MutableStateFlow(showCalendarEvent)
    private val searchAutoLaunchState = MutableStateFlow(searchAutoLaunch)
    private val usageSortState = MutableStateFlow(usageSort)
    private val notificationDotsState = MutableStateFlow(notificationDots)

    override fun monochromeIcons(): Flow<Boolean> = monochromeState
    override suspend fun setMonochromeIcons(enabled: Boolean) { monochromeState.value = enabled }

    override fun searchAutoLaunch(): Flow<Boolean> = searchAutoLaunchState
    override suspend fun setSearchAutoLaunch(enabled: Boolean) { searchAutoLaunchState.value = enabled }

    override fun usageSortEnabled(): Flow<Boolean> = usageSortState
    override suspend fun setUsageSortEnabled(enabled: Boolean) { usageSortState.value = enabled }

    override fun notificationDots(): Flow<Boolean> = notificationDotsState
    override suspend fun setNotificationDots(enabled: Boolean) { notificationDotsState.value = enabled }

    override val showAlarmFlow: Flow<Boolean> = showAlarmState
    override suspend fun setShowAlarm(enabled: Boolean) { showAlarmState.value = enabled }

    override val showCalendarEventFlow: Flow<Boolean> = showCalendarState
    override suspend fun setShowCalendarEvent(enabled: Boolean) { showCalendarState.value = enabled }
}
