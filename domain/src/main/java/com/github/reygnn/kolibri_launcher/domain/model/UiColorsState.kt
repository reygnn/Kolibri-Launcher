package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Domain colour state. Each value is an ARGB integer (the same Int format
 * `android.graphics.Color` produces). The defaults match Android's
 * `Color.WHITE` and `Color.BLACK` exactly so existing call sites don't shift.
 */
data class UiColorsState(
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val shadowColor: Int = 0xFF000000.toInt(),
    val chipBackgroundColor: Int = 0
)
