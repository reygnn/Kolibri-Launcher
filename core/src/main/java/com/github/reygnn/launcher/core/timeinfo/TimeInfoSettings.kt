package com.github.reygnn.launcher.core.timeinfo

import kotlinx.coroutines.flow.Flow

/**
 * Narrow read-port for the two settings flags the home-info subsystem needs
 * (HIE-INV-2): whether to show the next alarm and the next calendar event. It
 * deliberately does NOT expose a product's full settings store — each app adapts
 * its own store onto this port (Kolibri's SettingsRepository implements it
 * directly; Nyx binds a small adapter over the two keys). The key strings live
 * product-neutrally in core AppConstants (SHOW_ALARM, SHOW_CALENDAR_EVENT).
 */
interface TimeInfoSettings {
    val showAlarmFlow: Flow<Boolean>
    val showCalendarEventFlow: Flow<Boolean>
}
