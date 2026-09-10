package com.github.reygnn.kolibri_launcher.ui.util

/**
 * Configuration flag for test mode.
 * Injected via Hilt to avoid mutable static state.
 */
data class TestMode(val isEnabled: Boolean)