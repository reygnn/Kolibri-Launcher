package com.github.reygnn.kolibri_launcher

/**
 * Zentrale Konstanten für die gesamte Anwendung
 * Ersetzt Magic Numbers und hardcoded Values
 */
object AppConstants {

    // UI Constants
    const val SWIPE_THRESHOLD = 50
    const val SWIPE_VELOCITY_THRESHOLD = 50
    const val DOUBLE_CLICK_THRESHOLD = 300L


    // Text Shadow Constants (verwendet in HomeFragment)
    const val SHADOW_RADIUS_TIME = 4f      // Für große Zeit-Anzeige
    const val SHADOW_RADIUS_DATE = 3f      // Für Datum
    const val SHADOW_RADIUS_BATTERY = 3f   // Für Batterie-Anzeige
    const val SHADOW_RADIUS_APPS = 3f      // Für App-Buttons
    const val SHADOW_DX = 2f               // X-Offset für Zeit und Apps
    const val SHADOW_DY = 2f               // Y-Offset für Zeit und Apps
    const val SHADOW_DX_SMALL = 1f         // X-Offset für Datum und Batterie
    const val SHADOW_DY_SMALL = 1f         // Y-Offset für Datum und Batterie

    const val KEY_NAME_PREFIX = "name_"

    // Fragment Tags
    const val FRAGMENT_SETTINGS = "settings"

    // Bundle Arguments
    const val ARG_FAVORITES = "favorites"

    const val MAX_FAVORITES_ON_HOME = 8

    const val SETTINGS_DATASTORE_NAME = "kolibri_settings"
}