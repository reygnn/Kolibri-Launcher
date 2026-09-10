package com.github.reygnn.kolibri_launcher.ui.main

import com.github.reygnn.kolibri_launcher.domain.model.ChargeState
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent

data class HomeUiState(
    val timeString: String = "",
    val dateString: String = "",
    val batteryString: String = "",
    val chargeState: ChargeState = ChargeState.NONE,
    val timeBasedEvents: List<TimeBasedEvent> = emptyList()
)