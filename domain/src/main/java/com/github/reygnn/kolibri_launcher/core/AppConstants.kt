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

    // Text Shadow Constants — still used by the AppDrawer labels (AppDrawerAdapter),
    // which paint over a solid surface rather than the wallpaper. The home screen
    // uses the outline below instead.
    const val SHADOW_RADIUS_APPS = 3f      // Für App-Buttons
    const val SHADOW_DX = 2f               // X-Offset für Zeit und Apps
    const val SHADOW_DY = 2f               // Y-Offset für Zeit und Apps

    // Text outline width (dp) for the home clock / date / battery / favorites.
    // A thin hard stroke around the glyphs, painted via TextOutline instead of a
    // drop shadow: background-independent (never dims the wallpaper) and wins
    // contrast locally against black and white pixels at once. Half of it shows
    // outside the glyph edge, so keep it small. Multiply by display density for px.
    const val TEXT_OUTLINE_WIDTH_DP = 1.5f

    // Alpha-Wert für den gedrückten Zustand der Favoriten Buttons (0-255).
    // 180 = ~70% Sichtbarkeit. Subtiles Feedback ("Breathing"), kein harter Blitz.
    const val PRESSED_STATE_ALPHA = 180

    // Layout Defaults
    const val LAYOUT_SCALE_MIN = 0.0f
    const val LAYOUT_SCALE_MAX = 2.0f

    // User-controlled home wallpaper scrim (opt-in dim for extreme wallpapers).
    const val WALLPAPER_SCRIM_ALPHA_MIN = 0.0f
    const val WALLPAPER_SCRIM_ALPHA_MAX = 0.5f


    const val VERTICAL_PADDING_SCALE_MIN = 0.0f
    const val VERTICAL_PADDING_SCALE_MAX = 2.0f

    const val CONTENT_TOP_MARGIN_SCALE_MIN = 0.0f
    const val CONTENT_TOP_MARGIN_SCALE_MAX = 2.0f

    const val DEFAULT_ROTATION_LOCKED = false

    const val MAX_APP_TEXT_SCALE_RELATIVE_TO_TIME = 0.75f
    const val DEFAULT_LAYOUT_SCALE = 0.05f
    // Opt-in: scrim off by default keeps already-dark wallpapers untouched.
    const val DEFAULT_WALLPAPER_SCRIM_ALPHA = 0.0f
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
     * which back up the whole `datastore/` directory and exclude the consent
     * file (`acra_consent.preferences_pb`) by name.
     */
    const val CONSENT_DATASTORE_NAME = "acra_consent"

    /**
     * Separate DataStore file for the time-weighted app-usage timestamps
     * (the [KEY_USAGE_PREFIX] keys).
     *
     * Kept apart from [SETTINGS_DATASTORE_NAME] on purpose (AUDIT-19 F1): a
     * usage timestamp is written on EVERY app launch, and Preferences DataStore
     * re-serialises the WHOLE backing file per edit and re-emits it to every
     * collector of that store. Co-locating the highest-frequency writer with
     * all other user state therefore rewrote the full settings blob and woke
     * every settings / favorites / custom-names collector on each launch. Its
     * own file confines that churn to a small file with a single collector.
     *
     * No migration from the old settings-store keys (same choice as the
     * consent split): on the update that ships this, usage history is not
     * carried over — the store starts empty and the [SortOrder.TIME_WEIGHTED_USAGE]
     * default sort rebuilds as apps are launched. The old usage keys are left
     * untouched in the settings store; they are dead weight for existing
     * installs but no longer written, so they no longer drive the per-launch
     * churn this split removes.
     *
     * Included in Auto Backup / device-transfer once populated: the backup
     * rules include the whole `datastore/` directory and exclude only the
     * consent file, so this file travels with a restore like any other store.
     */
    const val USAGE_DATASTORE_NAME = "kolibri_usage"

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

    /**
     * Debounce window for external app-list reload triggers (DEBOUNCE_SPEC).
     * A burst of package broadcasts during a system update / batch install
     * collapses to one reload after this quiet period. The initial/priming
     * load is NOT subject to it. Trade-off: a single install/uninstall updates
     * the drawer at most this much later.
     */
    const val APP_RELOAD_DEBOUNCE_MS = 250L

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
     *       repo.getInstalledApps()
     *           .filterIsInstance<AppLoad.Loaded>()
     *           .first { it.apps.isNotEmpty() }
     *   } ?: error("...")
     *   ```
     *
     * 10 s accommodates PackageManager latency on slow devices (a cold
     * enumeration + per-app loadLabel). A persistent load failure surfaces as
     * AppLoad.Failed, which never satisfies the predicate, so this timeout is the
     * bound on that case too.
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
    // One shared pattern for BOTH export filenames (backup + usage) so the two
    // can never drift apart again — they used to differ (yyyyMMdd vs yyyy-MM-dd).
    const val DATE_FORMAT_EXPORT_FILENAME = "yyyy-MM-dd_HHmmss"
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
        const val WALLPAPER_SCRIM_ALPHA = "wallpaper_scrim_alpha"
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
        const val DOUBLE_TAP_CLIPBOARD = "double_tap_clipboard_enabled"

        // Keys, die nur für Klicks/Intents im Fragment genutzt werden (kein DataStore Value)
        const val SYSTEM_WALLPAPER = "system_wallpaper"
        const val EDIT_FAVORITES = "edit_favorites"
        const val SORT_FAVORITES = "sort_favorites"
        const val HIDDEN_APPS = "hidden_apps"
        const val CUSTOM_APP_NAMES = "custom_app_names"
        const val BACKUP_RESTORE = "backup_restore"
        const val USAGE_EXPORT = "usage_export"
        const val CLEANUP_STORAGE = "cleanup_storage"
        const val FACTORY_RESET = "factory_reset"
        const val APP_INFO = "app_info"
        const val SET_DEFAULT_LAUNCHER = "set_default_launcher"
        const val SWIPE_ACTIONS = "swipe_actions"
        const val CRASH_REPORTS = "crash_reports"

        // Developer commands (Settings → Entwickler-Befehle): test/diagnostic
        // shortcuts for ACRA crash-report verification. Visible in release
        // builds because the maintainer needs them after every refactor that
        // touches the crash pipeline (see TODO §14 for the libs.json/ACRA
        // diagnosis arc that motivated these shortcuts).
        const val PIPELINE_STATUS = "pipeline_status"
        const val THROW_TEST_EXCEPTION = "throw_test_exception"
    }

    // Default Values für Settings
    const val DEFAULT_TEXT_COLOR = 0 // 0 = Transparent/Nicht gesetzt
    const val DEFAULT_CHIP_BG_COLOR = 0
    const val DEFAULT_TEXT_SHADOW_ENABLED = true

    const val DEFAULT_SHOW_CALENDAR = false
    const val DEFAULT_SHOW_ALARM = false

    /**
     * Double-tap-to-clipboard-action is OFF by default: the gesture reads the
     * clipboard and can forward its content to a search provider, so it is
     * opt-in rather than something an update silently switches on.
     */
    const val DEFAULT_DOUBLE_TAP_CLIPBOARD = false
    const val DEFAULT_AUTO_SHOW_KEYBOARD = false
    const val DEFAULT_AUTO_LAUNCH_APP = false

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

    // (The app-load retry policy was removed with INSTALLED_APPS_LOAD_SPEC Commit 1:
    // the .retry sat on a stateIn StateFlow that never propagates upstream
    // exceptions, so it never fired; the loader now yields AppLoad.Failed and
    // keep-last-good covers the transient window.)

    // SavedStateHandle Keys
    const val KEY_SEARCH_QUERY = "KEY_SEARCH_QUERY"
    const val KEY_FALLBACK_TOAST_SHOWN = "key_fallback_toast_shown"
}