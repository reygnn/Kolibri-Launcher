package com.github.reygnn.kolibri_launcher.core

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder

/**
 * Zentrale Konstanten für die gesamte Anwendung
 * Ersetzt Magic Numbers und hardcoded Values
 */
object AppConstants {

    // UI Constants
    const val SWIPE_THRESHOLD = 50
    const val SWIPE_VELOCITY_THRESHOLD = 50
    const val DOUBLE_CLICK_THRESHOLD = 300L
    const val LOCK_GESTURE_BLOCK_DURATION_MS = 1000L
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

    // Alpha-Wert für den gedrückten Zustand der Favoriten Buttons (0-255).
    // 180 = ~70% Sichtbarkeit. Subtiles Feedback ("Breathing"), kein harter Blitz.
    const val PRESSED_STATE_ALPHA = 180

    // Layout Defaults
    const val LAYOUT_SCALE_MIN = 0.0f
    const val LAYOUT_SCALE_MAX = 2.0f

    const val VERTICAL_PADDING_SCALE_MIN = 0.0f
    const val VERTICAL_PADDING_SCALE_MAX = 2.0f

    const val CONTENT_TOP_MARGIN_SCALE_MIN = 0.0f
    const val CONTENT_TOP_MARGIN_SCALE_MAX = 2.0f

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
    val DEFAULT_SORT_ORDER = SortOrder.TIME_WEIGHTED_USAGE

    const val KEY_NAME_PREFIX = "name_"
    const val KEY_USAGE_PREFIX = "usage_"

    // Fragment Tags
    const val FRAGMENT_SETTINGS = "settings"

    // Bundle Arguments
    const val ARG_FAVORITES = "favorites"

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

    const val BACKUP_VERSION = "1.0.0"
    const val MAX_BACKUP_SIZE_BYTES = 10 * 1024 * 1024L  // 10 MB
    const val MAX_PREVIEW_SIZE_BYTES = 1 * 1024 * 1024L  // 1 MB
    const val MAX_ARRAY_ELEMENTS = 512
    const val MAX_FALLBACK_FAVORITES_ON_HOME = 500  // muss weniger sein als MAX_ARRAY_ELEMENTS

    // File System Constants
    const val SCHEME_CONTENT = "content"
    const val SCHEME_FILE = "file"
    const val MODE_READ_ONLY = "r"

    /**
     * Preference Keys (Strings).
     * Diese müssen exakt mit den Schlüsseln in res/xml/preferences.xml übereinstimmen!
     */
    object PrefKeys {
        // Core
        const val SORT_ORDER = "app_drawer_sort_order"
        const val ONBOARDING_COMPLETED = "onboarding_completed"

        // Visuals
        const val TEXT_COLOR = "text_color"
        const val CHIP_BACKGROUND_COLOR = "chip_background_color"
        const val TEXT_SHADOW_ENABLED = "text_shadow_enabled"
        const val IS_FONT_BOLD = "is_font_bold"

        // Layout & Scaling
        const val LAYOUT_SCALE = "layout_scale"
        const val VERTICAL_PADDING_SCALE = "vertical_padding_scale"
        const val CONTENT_TOP_MARGIN_SCALE = "content_top_margin_scale"

        // Home Screen Features
        const val SHOW_CALENDAR_EVENT = "show_calendar_event"
        const val SHOW_ALARM = "show_alarm"

        // Quality of Life
        const val AUTO_SHOW_KEYBOARD = "auto_show_keyboard_drawer"
        const val AUTO_LAUNCH_APP = "auto_launch_app"

        // Power User
        const val SPLIT_MODE_THRESHOLD = "split_mode_threshold"

        // Gestures
        const val DOUBLE_TAP_TO_LOCK = "double_tap_to_lock_enabled"
        const val SWIPE_DOWN_TO_NOTIFICATIONS = "swipe_down_to_notifications_enabled"

        // Appearance Mode
        const val READABILITY_MODE = "text_readability_mode"

        // Keys, die nur für Klicks/Intents im Fragment genutzt werden (kein DataStore Value)
        const val SYSTEM_WALLPAPER = "system_wallpaper"
        const val EDIT_FAVORITES = "edit_favorites"
        const val SORT_FAVORITES = "sort_favorites"
        const val HIDDEN_APPS = "hidden_apps"
        const val CUSTOM_APP_NAMES = "custom_app_names"
        const val BACKUP_RESTORE = "backup_restore"
        const val USAGE_EXPORT = "usage_export"
        const val FACTORY_RESET = "factory_reset"
        const val APP_INFO = "app_info"
        const val ACCESSIBILITY = "accessibility"
        const val SET_DEFAULT_LAUNCHER = "set_default_launcher"
        const val SWIPE_ACTIONS = "swipe_actions"
        const val CRASH_REPORTS = "crash_reports"
        const val SECURE_WINDOW = "secure_window"
    }

    // Default Values für Settings
    const val DEFAULT_TEXT_COLOR = 0 // 0 = Transparent/Nicht gesetzt
    const val DEFAULT_CHIP_BG_COLOR = 0
    const val DEFAULT_TEXT_SHADOW_ENABLED = true

    const val DEFAULT_SHOW_CALENDAR = false
    const val DEFAULT_SHOW_ALARM = false
    const val DEFAULT_DOUBLE_TAP_TO_LOCK = false
    const val DEFAULT_SWIPE_DOWN_NOTIFICATIONS = false
    const val DEFAULT_AUTO_SHOW_KEYBOARD = false
    const val DEFAULT_AUTO_LAUNCH_APP = false
    const val DEFAULT_SECURE_WINDOW = false

    const val DEFAULT_SPLIT_MODE_THRESHOLD = 0

    // Readability Modes
    const val READABILITY_MODE_SMART_CONTRAST = "smart_contrast"
    const val READABILITY_MODE_STANDARD = "standard"
    const val DEFAULT_READABILITY_MODE = READABILITY_MODE_SMART_CONTRAST

    // Chip Styling Constants
    const val CHIP_MAX_WIDTH_FACTOR = 0.80 // 80% der Bildschirmbreite
    const val CHIP_TEXT_SIZE_SP = 12f
    const val CHIP_STROKE_WIDTH = 1f

    // Timing Constants
    const val SCROLL_VERIFICATION_DELAY_MS = 50L

    // Fallback Values für Ressourcen-Zugriffe (catch blocks)
    const val FALLBACK_DIMEN_PX = 16
    const val FALLBACK_BORDER_WIDTH_PX = 4
    const val FALLBACK_CORNER_RADIUS_PX = 16f

    // Border Styling
    const val BORDER_ALPHA = 51 // ca. 20%

    // Search Configuration
    const val SEARCH_DEBOUNCE_DELAY_MS = 150L

    // Action Keys
    const val ACTION_LAUNCH_SHORTCUT = "launch_shortcut"

    const val INITIAL_APP_LOAD_DELAY_MS = 100L

    // SavedStateHandle Keys
    const val KEY_SEARCH_QUERY = "KEY_SEARCH_QUERY"
    const val KEY_FALLBACK_TOAST_SHOWN = "key_fallback_toast_shown"
}