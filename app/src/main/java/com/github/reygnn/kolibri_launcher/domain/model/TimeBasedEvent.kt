package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Unified model für zeitbasierte Events (Kalender + Alarme).
 * Ermöglicht chronologische Sortierung und einheitliche UI-Darstellung.
 */
data class TimeBasedEvent(
    val triggerTimeMillis: Long,
    val title: String,
    val type: EventType
)

enum class EventType {
    ALARM,
    CALENDAR
}