package com.github.reygnn.kolibri_launcher.core

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
    const val KEY_USAGE_PREFIX = "usage_"

    // Fragment Tags
    const val FRAGMENT_SETTINGS = "settings"

    // Bundle Arguments
    const val ARG_FAVORITES = "favorites"

    const val MAX_FAVORITES_ON_HOME = 72

    const val SETTINGS_DATASTORE_NAME = "kolibri_settings"

    // App Usage Tracking Constants
    /**
     * Zerfallskonstante für zeitgewichtete Nutzungsstatistik.
     * Bestimmt, wie schnell alte App-Starts an Relevanz verlieren.
     *
     * Mit LAMBDA = 0.000001:
     * - Nach 1 Tag: 91.7% Gewicht
     * - Nach 7 Tagen: 54.8% Gewicht
     * - Nach 30 Tagen: 10.5% Gewicht
     */
    const val USAGE_DECAY_LAMBDA = 0.000001

    /**
     * Maximale Anzahl gespeicherter Timestamps pro App.
     * Balanciert zwischen Datenpräzision und Storage-Effizienz.
     */
    const val MAX_TIMESTAMPS_PER_APP = 150

    /**
     * Maximales Alter eines Timestamps in Millisekunden (1 Jahr).
     * Ältere Daten werden automatisch bereinigt.
     */
    const val MAX_TIMESTAMP_AGE_MS = 365L * 24 * 60 * 60 * 1000

    /**
     * Timeout für Flow-Sharing via WhileSubscribed.
     * Flow bleibt 5 Sekunden nach letztem Collector aktiv.
     */
    const val FLOW_SHARING_TIMEOUT_MS = 5000L
}