package com.github.reygnn.kolibri_launcher.ui.main

import com.github.reygnn.launcher.core.timeinfo.ChargeState
import com.github.reygnn.launcher.core.timeinfo.TimeBasedEvent

data class HomeUiState(
    val timeString: String = "",
    val dateString: String = "",
    val batteryString: String = "",
    val chargeState: ChargeState = ChargeState.NONE,
    val timeBasedEvents: List<TimeBasedEvent> = emptyList()
)