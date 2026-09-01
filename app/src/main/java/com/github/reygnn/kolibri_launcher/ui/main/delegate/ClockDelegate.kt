/*
 * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.github.reygnn.kolibri_launcher.ui.main.delegate

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveTimeBasedEventsUseCase
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Delegate responsible for clock, date, battery, and time-based events.
 *
 * Exposes individual StateFlows that the ViewModel combines into HomeUiState.
 */
class ClockDelegate(
    private val context: Context,
    private val observeTimeBasedEventsUseCase: ObserveTimeBasedEventsUseCase,
    private val scope: DelegateScope
) {

    companion object {
        private const val DEFAULT_TIME = "--:--"
        private const val DEFAULT_DATE = "---"
        private const val DEFAULT_BATTERY = "---%"
    }

    // --- Exposed State ---

    private val _timeString = MutableStateFlow(DEFAULT_TIME)
    val timeString: StateFlow<String> = _timeString.asStateFlow()

    private val _dateString = MutableStateFlow(DEFAULT_DATE)
    val dateString: StateFlow<String> = _dateString.asStateFlow()

    private val _batteryString = MutableStateFlow(DEFAULT_BATTERY)
    val batteryString: StateFlow<String> = _batteryString.asStateFlow()

    // Charging flag driven by the battery Intent's EXTRA_STATUS. Consumed by the
    // home-screen charging indicator (a monochrome bolt icon), separate from the
    // percentage text so the string plumbing stays untouched.
    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    private val _timeBasedEvents = MutableStateFlow<List<TimeBasedEvent>>(emptyList())
    val timeBasedEvents: StateFlow<List<TimeBasedEvent>> = _timeBasedEvents.asStateFlow()

    // --- Init ---

    fun start() {
        // SYNC: Zeit sofort beim Kaltstart
        updateTimeAndDate()

        // SYNC: Batterie sofort
        getInitialBatteryState()

        // ASYNC: Minuten-Ticks
        scope.launchSafe("Error observing system time changes") {
            observeSystemTimeChanges().collect {
                updateTimeAndDate()
            }
        }

        // ASYNC: Kalender-Events
        scope.launchSafe("Error observing time-based events") {
            observeTimeBasedEventsUseCase().collect { events ->
                _timeBasedEvents.value = events
            }
        }
    }

    // --- Public API ---

    fun refreshTimeNow() {
        updateTimeAndDate()
    }

    fun refreshAll() {
        updateTimeAndDate()
        getInitialBatteryState()
        observeTimeBasedEventsUseCase.refresh()
    }

    fun updateBatteryLevelFromIntent(intent: Intent?) {
        try {
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                updateBatteryLevel(level, scale)

                // FULL only occurs while plugged in (once unplugged the status turns
                // DISCHARGING/NOT_CHARGING), so treating it as charging keeps the bolt
                // visible at 100 % on the charger instead of blinking off.
                val status = intent.getIntExtra(
                    BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN
                )
                _isCharging.value = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            } else {
                _batteryString.value = DEFAULT_BATTERY
                _isCharging.value = false
            }
        } catch (e: Throwable) {
            // Non-suspend method reading Intent extras — no suspension point,
            // so no CancellationException can reach this catch.
            TimberWrapper.silentError(e, "Failed to update battery level from intent")
            _batteryString.value = DEFAULT_BATTERY
            _isCharging.value = false
        }
    }

    fun updateBatteryLevel(level: Int, scale: Int) {
        // The `scale > 0` guard rules out divide-by-zero; the rest is
        // pure integer math on Long. CANT_THROW.
        if (level != -1 && scale != -1 && scale > 0) {
            val batteryPercent = (level.toLong() * 100 / scale).toInt()
            _batteryString.value = "${batteryPercent}%"
        } else {
            _batteryString.value = DEFAULT_BATTERY
        }
    }

    // --- Internal ---

    // Cached time/date formatters — rebuilt only when the locale or the 12/24-hour
    // setting changes, not on every minute tick. Formatter construction parses the
    // pattern + builds symbols, so allocating per ACTION_TIME_TICK was pure (if
    // small) waste on the Main thread. DateTimeFormatter is IMMUTABLE and
    // thread-safe (unlike SimpleDateFormat, whose format() is not), so sharing one
    // instance is safe regardless of which thread calls it — the caching no longer
    // rests on the "updateTimeAndDate only runs on Main" invariant. The zone is
    // resolved per format call (ZoneId.systemDefault()), so a device time-zone
    // change is reflected on the next tick without a formatter rebuild.
    private class ClockFormatters(
        val locale: Locale,
        val is24Hour: Boolean,
        val time: DateTimeFormatter,
        val date: DateTimeFormatter,
    )

    private var clockFormatters: ClockFormatters? = null

    private fun formatters(is24Hour: Boolean): ClockFormatters {
        val locale = Locale.getDefault()
        clockFormatters?.let { if (it.locale == locale && it.is24Hour == is24Hour) return it }
        return ClockFormatters(
            locale = locale,
            is24Hour = is24Hour,
            time = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale),
            date = DateTimeFormatter.ofPattern("E, d MMM", locale),
        ).also { clockFormatters = it }
    }

    private fun updateTimeAndDate() {
        val now = System.currentTimeMillis()
        val fmt = formatters(DateFormat.is24HourFormat(context))
        val zoned = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())

        val newTime = fmt.time.format(zoned)
        val newDate = fmt.date.format(zoned)

        // Smart update: only emit when the formatted value actually changed.
        _timeString.update { current -> if (current == newTime) current else newTime }
        _dateString.update { current -> if (current == newDate) current else newDate }
    }

    private fun getInitialBatteryState() {
        try {
            val batteryIntent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                Context.RECEIVER_NOT_EXPORTED
            )
            updateBatteryLevelFromIntent(batteryIntent)
        } catch (e: Throwable) {
            // Non-suspend method calling registerReceiver — no suspension point,
            // so no CancellationException can reach this catch.
            TimberWrapper.silentError(e, "Failed to register battery receiver")
            _batteryString.value = DEFAULT_BATTERY
        }
    }

    private fun observeSystemTimeChanges() = callbackFlow {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }

        context.registerReceiver(receiver, filter)
        trySend(Unit)

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }
}