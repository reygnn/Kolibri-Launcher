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
    const val LANDSCAPE_SPLIT_SCROLL_WEIGHT = 40F
    const val LANDSCAPE_SPLIT_GESTURE_WEIGHT = (100F - LANDSCAPE_SPLIT_SCROLL_WEIGHT)
    const val PORTRAIT_SPLIT_SCROLL_WEIGHT = 55F
    const val PORTRAIT_SPLIT_GESTURE_WEIGHT = (100F - PORTRAIT_SPLIT_SCROLL_WEIGHT)


    // Text Shadow Constants (verwendet in HomeFragment)
    const val SHADOW_RADIUS_TIME = 4f      // Für grosse Zeit-Anzeige
    const val SHADOW_RADIUS_DATE = 3f      // Für Datum
    const val SHADOW_RADIUS_BATTERY = 3f   // Für Batterie-Anzeige
    const val SHADOW_RADIUS_APPS = 3f      // Für App-Buttons
    const val SHADOW_DX = 2f               // X-Offset für Zeit und Apps
    const val SHADOW_DY = 2f               // Y-Offset für Zeit und Apps
    const val SHADOW_DX_SMALL = 1f         // X-Offset für Datum und Batterie
    const val SHADOW_DY_SMALL = 1f         // Y-Offset für Datum und Batterie

    // Layout Defaults
    const val LAYOUT_SCALE_MIN = 0.0f
    const val LAYOUT_SCALE_MAX = 2.0f

    const val VERTICAL_PADDING_SCALE_MIN = 0.0f
    const val VERTICAL_PADDING_SCALE_MAX = 2.0f

    const val CONTENT_TOP_MARGIN_SCALE_MIN = 0.0f
    const val CONTENT_TOP_MARGIN_SCALE_MAX = 1.0f

    const val SPLIT_MODE_THRESHOLD_MIN = 0
    const val SPLIT_MODE_THRESHOLD_MAX = 512


    const val MAX_APP_TEXT_SCALE_RELATIVE_TO_TIME = 0.75f
    const val DEFAULT_LAYOUT_SCALE = 0.05f
    const val DEFAULT_VERTICAL_PADDING_FACTOR = 0.6f
    const val DEFAULT_FONT_BOLD = true
    const val DEFAULT_TOP_MARGIN = 0f
    const val FALLBACK_TEXT_SIZE_PX = 48f
    const val FALLBACK_VERTICAL_PADDING_PX = 16
    const val FALLBACK_FONT_BOLD = DEFAULT_FONT_BOLD

    const val KEY_NAME_PREFIX = "name_"
    const val KEY_USAGE_PREFIX = "usage_"

    // Fragment Tags
    const val FRAGMENT_SETTINGS = "settings"

    // Bundle Arguments
    const val ARG_FAVORITES = "favorites"

    const val MAX_FALLBACK_FAVORITES_ON_HOME = 256

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

    // Split Mode Logic
    const val SPLIT_MODE_TINKERING_LIMIT = 200 // Grenze für "Bastler"-Beschreibung

    // URLs
    const val URL_ABOUT_PAGE = "https://docs.kolibri-launcher.ch/about.html"

    // Backup & Restore Constants
    const val BACKUP_PREVIEW_TIMEOUT_MS = 2000L
    const val MIME_TYPE_JSON = "application/json"
    const val BACKUP_FILE_PREFIX = "kolibri_backup_"
    const val BACKUP_FILE_EXTENSION = ".json"

    // Date Formats
    const val DATE_FORMAT_BACKUP_FILENAME = "yyyyMMdd_HHmmss"
    const val DATE_FORMAT_DISPLAY = "dd.MM.yyyy HH:mm"

    // UI Limits
    const val MAX_MISSING_APPS_IN_SNACKBAR = 5

    /**
     * Keys für Preferences (müssen mit res/xml/preferences.xml übereinstimmen)
     */
    object PrefKeys {
        const val SHOW_CALENDAR_EVENT = "show_calendar_event"
        const val SHOW_ALARM = "show_alarm"
        const val AUTO_SHOW_KEYBOARD = "auto_show_keyboard_drawer"
        const val AUTO_LAUNCH_APP = "auto_launch_app"
        const val SPLIT_MODE_THRESHOLD = "split_mode_threshold"

        const val SYSTEM_WALLPAPER = "system_wallpaper"
        const val EDIT_FAVORITES = "edit_favorites"
        const val SORT_FAVORITES = "sort_favorites"
        const val HIDDEN_APPS = "hidden_apps"
        const val CUSTOM_APP_NAMES = "custom_app_names"

        const val BACKUP_RESTORE = "backup_restore"
        const val FACTORY_RESET = "factory_reset"

        const val APP_INFO = "app_info"
        const val ACCESSIBILITY = "accessibility"
        const val SET_DEFAULT_LAUNCHER = "set_default_launcher"

        const val DOUBLE_TAP_TO_LOCK = "double_tap_to_lock_enabled"
        const val SWIPE_DOWN_NOTIFICATIONS = "swipe_down_to_notifications_enabled"
        const val SWIPE_ACTIONS = "swipe_actions"
        const val CRASH_REPORTS = "crash_reports"
    }
}