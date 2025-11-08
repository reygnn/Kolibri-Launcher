package com.github.reygnn.kolibri_launcher.data

/**
 * Eine einfache Datenklasse, die einen einzelnen Kalendertermin repräsentiert.
 * Diese wird vom Repository an das ViewModel übergeben.
 */
data class CalendarEvent(
    val title: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long
)