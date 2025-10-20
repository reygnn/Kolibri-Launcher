package com.github.reygnn.kolibri_launcher

/**
 * Configuration flag for test mode.
 * Injected via Hilt to avoid mutable static state.
 */
data class TestMode(val isEnabled: Boolean)