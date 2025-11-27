package com.github.reygnn.kolibri_launcher.ui.home

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * PURE LOGIC - Time Formatting Engine
 *
 * Kapselt die Logik für das Runden von Alarmzeiten und das Formatieren
 * von Zeitstempeln. Keine Android-Dependencies (außer java.*).
 */
class TimeEventFormatter {

    /**
     * Formatiert einen Alarm-Zeitstempel.
     *
     * LOGIK: Android Alarme feuern oft Millisekunden nach der vollen Minute.
     * Ein Alarm für 07:00 Uhr könnte intern 07:00:00.052 sein.
     * Manche APIs liefern auch "Trigger Time", die leicht in der Vergangenheit liegt.
     *
     * Um dem User "07:01" anzuzeigen, wenn er eigentlich "07:00" meint,
     * runden wir Sekunden/Millis > 0 auf die nächste Minute auf (Standard Android Verhalten),
     * ODER wir schneiden sie ab (je nach gewünschtem Verhalten).
     *
     * Deine ursprüngliche Implementierung hat AUFGERUNDET (+1 Minute),
     * wenn Sekunden > 0 waren. Ich habe diese Logik hier exakt übernommen.
     */
    fun formatAlarmTime(triggerTimeMillis: Long, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = triggerTimeMillis

        // Spezielle Alarm-Logik: Wenn Sekunden oder Millis existieren, addiere eine Minute.
        // Das ist oft nötig, weil "nächster Alarm" APIs manchmal die Zeit des *gerade klingelnden* Alarms liefern.
        if (calendar.get(Calendar.SECOND) > 0 || calendar.get(Calendar.MILLISECOND) > 0) {
            calendar.add(Calendar.MINUTE, 1)
        }

        // Sekunden für die Anzeige säubern
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        return formatTimeInternal(calendar.timeInMillis, is24Hour, locale)
    }

    /**
     * Formatiert Kalender-Events (keine Rundungs-Logik nötig).
     */
    fun formatCalendarTime(triggerTimeMillis: Long, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String {
        return formatTimeInternal(triggerTimeMillis, is24Hour, locale)
    }

    private fun formatTimeInternal(timeMillis: Long, is24Hour: Boolean, locale: Locale): String {
        val pattern = if (is24Hour) "HH:mm" else "h:mm a"
        val formatter = SimpleDateFormat(pattern, locale)
        return formatter.format(Date(timeMillis))
    }
}