package com.github.reygnn.kolibri_launcher.core

import com.github.reygnn.kolibri_launcher.domain.model.WallpaperSurfaceMode
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder

/**
 * Zentrale Konstanten für die gesamte Anwendung
 * Ersetzt Magic Numbers und hardcoded Values
 */
object AppConstants {

    // UI Constants
    const val DOUBLE_CLICK_THRESHOLD = 300L

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

    const val DEFAULT_ROTATION_LOCKED = false

    const val MAX_APP_TEXT_SCALE_RELATIVE_TO_TIME = 0.75f
    const val DEFAULT_LAYOUT_SCALE = 0.05f
    const val DEFAULT_VERTICAL_PADDING_FACTOR = 0.6f
    const val DEFAULT_FONT_BOLD = true
    const val DEFAULT_TOP_MARGIN = 0f
    const val FALLBACK_TEXT_SIZE_PX = 48f
    const val FALLBACK_VERTICAL_PADDING_PX = 16
    const val FALLBACK_FONT_BOLD = DEFAULT_FONT_BOLD
    val DEFAULT_SORT_ORDER = SortOrder.TIME_WEIGHTED_USAGE
    val DEFAULT_FAVORITES_ALIGNMENT = FavoritesAlignment.START
    val DEFAULT_WALLPAPER_SURFACE_MODE = WallpaperSurfaceMode.AUTO

    const val KEY_NAME_PREFIX = "name_"
    const val KEY_USAGE_PREFIX = "usage_"

    // Fragment Tags
    const val FRAGMENT_SETTINGS = "settings"

    // Bundle Arguments
    const val ARG_FAVORITES = "favorites"

    const val SETTINGS_DATASTORE_NAME = "kolibri_settings"

    /**
     * Separate DataStore file for ACRA crash-report consent state.
     *
     * Kept apart from [SETTINGS_DATASTORE_NAME] on purpose: the settings
     * DataStore is included in Android Auto Backup / device-transfer (so
     * user config travels to a new device), but the crash-report consent
     * must NOT travel — a restored install starts privacy-by-default and
     * re-asks. Auto-backup rules operate at file granularity, so the
     * consent needs its own file to be excludable. See
     * `res/xml/data_extraction_rules.xml` + `res/xml/backup_rules.xml`,
     * which include only the settings DataStore file by name.
     */
    const val CONSENT_DATASTORE_NAME = "acra_consent"

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

    // URLs
    const val URL_ABOUT_PAGE = "https://docs.kolibri-launcher.ch/about.html"

    // Backup & Restore Constants
    const val BACKUP_PREVIEW_TIMEOUT_MS = 2000L

    /**
     * Max time a cold consumer waits for `InstalledAppsRepository` to
     * deliver its first non-empty emission before giving up.
     *
     * The repository's StateFlow is `WhileSubscribed(FLOW_SHARING_TIMEOUT_MS)`
     * with `initialValue = emptyList()`. From a cold subscriber (no UI
     * has primed it yet — backup restore from onboarding, Hidden-Apps or
     * Swipe-Actions activity opened directly after a process death)
     * `.first()` returns the initial empty list and unsubscribes before
     * the upstream PackageManager query runs.
     *
     * The fix pattern at every consumer site is:
     *   ```
     *   withTimeoutOrNull(INSTALLED_APPS_PRIME_TIMEOUT_MS) {
     *       repo.getInstalledApps().first { it.isNotEmpty() }
     *   } ?: error("...")
     *   ```
     *
     * 10 s accommodates the upstream's own retry policy
     * (`MAX_APP_LOAD_RETRIES * APP_LOAD_RETRY_BASE_DELAY_MS`
     * cumulative ≈ 6 s worst case) plus PackageManager latency on slow
     * devices. If those retry constants change, this value should follow.
     *
     * Used by: `BackupDataAssembler.performImport`,
     * `HiddenAppsViewModel.initialize`, `SwipeActionsViewModel.initialize`.
     */
    const val INSTALLED_APPS_PRIME_TIMEOUT_MS = 10_000L
    const val MIME_TYPE_JSON = "application/json"
    const val MIME_TYPE_ZIP = "application/zip"
    const val BACKUP_FILE_PREFIX = "kolibri_backup_"
    const val BACKUP_FILE_EXTENSION = ".zip"
    const val USAGE_EXPORT_FILE_PREFIX = "kolibri_usage_"
    const val USAGE_EXPORT_FILE_EXTENSION = ".json"

    // Date Formats
    const val DATE_FORMAT_BACKUP_FILENAME = "yyyyMMdd_HHmmss"
    const val DATE_FORMAT_USAGE_EXPORT_FILENAME = "yyyy-MM-dd_HHmmss"
    const val DATE_FORMAT_DISPLAY = "dd.MM.yyyy HH:mm"

    // UI Limits
    const val MAX_MISSING_APPS_IN_SNACKBAR = 5

    const val BACKUP_VERSION = "1.0.0"
    const val MAX_BACKUP_SIZE_BYTES = 10 * 1024 * 1024L  // 10 MB
    const val MAX_PREVIEW_SIZE_BYTES = 1 * 1024 * 1024L  // 1 MB
    const val MAX_ARRAY_ELEMENTS = 512
    const val MAX_FAVORITES_ON_HOME = 500  // must stay below MAX_ARRAY_ELEMENTS

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
        const val FAVORITES_ALIGNMENT = "favorites_alignment"
        const val ROTATION_LOCKED = "rotation_locked"
        const val APP_DRAWER_MODE = "app_drawer_mode"

        // Home Screen Features
        const val SHOW_CALENDAR_EVENT = "show_calendar_event"
        const val SHOW_ALARM = "show_alarm"

        // Quality of Life
        const val AUTO_SHOW_KEYBOARD = "auto_show_keyboard_drawer"
        const val AUTO_LAUNCH_APP = "auto_launch_app"

        // Gestures
        const val SWIPE_DOWN_TO_NOTIFICATIONS = "swipe_down_to_notifications_enabled"

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

        // Developer commands (Settings → Entwickler-Befehle): test/diagnostic
        // shortcuts for ACRA crash-report verification. Visible in release
        // builds because the maintainer needs them after every refactor that
        // touches the crash pipeline (see TODO §14 for the libs.json/ACRA
        // diagnosis arc that motivated these shortcuts).
        const val RESET_ACRA_TIMER = "reset_acra_timer"
        const val THROW_TEST_EXCEPTION = "throw_test_exception"
    }

    // Default Values für Settings
    const val DEFAULT_TEXT_COLOR = 0 // 0 = Transparent/Nicht gesetzt
    const val DEFAULT_CHIP_BG_COLOR = 0
    const val DEFAULT_TEXT_SHADOW_ENABLED = true

    const val DEFAULT_SHOW_CALENDAR = false
    const val DEFAULT_SHOW_ALARM = false
    const val DEFAULT_SWIPE_DOWN_NOTIFICATIONS = false
    const val DEFAULT_AUTO_SHOW_KEYBOARD = false
    const val DEFAULT_AUTO_LAUNCH_APP = false
    const val DEFAULT_SECURE_WINDOW = false

    // Chip Styling Constants
    const val CHIP_MAX_WIDTH_FACTOR = 0.80 // 80% der Bildschirmbreite
    const val CHIP_TEXT_SIZE_SP = 12f
    const val CHIP_STROKE_WIDTH = 1f

    // Fallback Values für Ressourcen-Zugriffe (catch blocks)
    const val FALLBACK_DIMEN_PX = 16

    // Search Configuration
    const val SEARCH_DEBOUNCE_DELAY_MS = 150L

    // Action Keys
    const val ACTION_LAUNCH_SHORTCUT = "launch_shortcut"

    const val INITIAL_APP_LOAD_DELAY_MS = 100L

    // App-load retry policy (ObserveInstalledAppsUseCase). Total upstream attempts =
    // 1 + MAX_APP_LOAD_RETRIES. Backoff is linear: APP_LOAD_RETRY_BASE_DELAY_MS *
    // attempt-index — so retry 1 waits 1 s, retry 2 waits 2 s, retry 3 waits 3 s.
    const val MAX_APP_LOAD_RETRIES = 3
    const val APP_LOAD_RETRY_BASE_DELAY_MS = 1000L

    // SavedStateHandle Keys
    const val KEY_SEARCH_QUERY = "KEY_SEARCH_QUERY"
    const val KEY_FALLBACK_TOAST_SHOWN = "key_fallback_toast_shown"
}