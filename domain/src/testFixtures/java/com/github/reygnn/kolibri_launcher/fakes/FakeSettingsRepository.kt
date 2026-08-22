package com.github.reygnn.kolibri_launcher.fakes

// TIMESTAMP 2025-12-07 12:59

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.coerceInSafe
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperSurfaceMode
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * ============================================================================
 * FAKE SETTINGS REPOSITORY - SHARED BETWEEN UNIT TESTS AND ANDROID TESTS
 * ============================================================================
 *
 * WICHTIG: Diese Datei existiert in ZWEI Locations und muss synchron gehalten werden!
 *
 * Locations:
 *   - src/test/kotlin/.../fakes/FakeSettingsRepository.kt        (Unit Tests)
 *   - src/androidTest/kotlin/.../FakeSettingsRepository.kt       (Android Tests)
 *
 * WARUM DUPLIZIERUNG STATT SHARED SOURCE SET?
 *   - testFixtures: @Incubating API (potenziell instabil)
 *   - sharedTest srcDir: "duplicate content roots" Fehler in Android Studio
 *   - Eigenes Modul: Overkill für wenige Fakes
 *   → Pragmatische Lösung: Manuelle Synchronisation
 *
 * REGELN:
 *   1. Änderungen IMMER in BEIDEN Dateien durchführen!
 *   2. Bei Interface-Änderungen bricht der Build in beiden Test-Arten
 *      → Compiler zeigt dir automatisch was fehlt
 *   3. Diese Klasse darf KEINE Android-spezifischen APIs verwenden
 *      (kein Context, SharedPreferences, Resources, etc.)
 *   4. Nur Kotlin/Coroutines APIs: MutableStateFlow, Flow, flowOf
 *
 * HELPER-METHODEN FÜR TESTS:
 *   - Direkte Property-Setter (z.B. shadow = true) für einfache Test-Setups
 *
 * SIEHE AUCH:
 *   - Andere Fakes folgen demselben Pattern
 *   - BaseAndroidTest.kt für Injection-Setup
 *   - TestRepositoryModule.kt für Hilt-Konfiguration (nur androidTest)
 *
 * ============================================================================
 */

class FakeSettingsRepository : SettingsRepository {

    // Visual Settings Defaults
    private val shadowFlow = MutableStateFlow(AppConstants.DEFAULT_TEXT_SHADOW_ENABLED)
    private val colorFlow = MutableStateFlow(AppConstants.DEFAULT_TEXT_COLOR)
    private val chipBgColorFlow = MutableStateFlow(AppConstants.DEFAULT_CHIP_BG_COLOR)
    private val layoutScaleFlow = MutableStateFlow(AppConstants.DEFAULT_LAYOUT_SCALE)
    private val wallpaperScrimAlphaFlow = MutableStateFlow(AppConstants.DEFAULT_WALLPAPER_SCRIM_ALPHA)
    private val verticalPaddingFlow = MutableStateFlow(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)
    private val isFontBoldFlow = MutableStateFlow(AppConstants.DEFAULT_FONT_BOLD)
    private val contentTopMarginFlow = MutableStateFlow(AppConstants.DEFAULT_TOP_MARGIN)

    // Feature Toggles Defaults
    private val calendarFlow = MutableStateFlow(AppConstants.DEFAULT_SHOW_CALENDAR)
    private val alarmFlow = MutableStateFlow(AppConstants.DEFAULT_SHOW_ALARM)
    private val autoShowKeyboardFlowState = MutableStateFlow(AppConstants.DEFAULT_AUTO_SHOW_KEYBOARD)
    private val autoLaunchAppFlowState = MutableStateFlow(AppConstants.DEFAULT_AUTO_LAUNCH_APP)

    private val sortOrderState = MutableStateFlow(SortOrder.TIME_WEIGHTED_USAGE) // Enum Default ist okay
    override val sortOrderFlow: Flow<SortOrder> = sortOrderState

    private val doubleTapClipboardState =
        MutableStateFlow(AppConstants.DEFAULT_DOUBLE_TAP_CLIPBOARD)
    override val doubleTapClipboardEnabledFlow: Flow<Boolean> = doubleTapClipboardState

    var doubleTapClipboard: Boolean
        get() = doubleTapClipboardState.value
        set(value) { doubleTapClipboardState.value = value }

    private val onboardingCompletedState = MutableStateFlow(false) // Onboarding ist per Default immer false (neu)
    override val onboardingCompletedFlow: Flow<Boolean> = onboardingCompletedState

    // --- Properties & Setters ---

    var shadow: Boolean
        get() = shadowFlow.value
        set(value) { shadowFlow.value = value }

    var color: Int
        get() = colorFlow.value
        set(value) { colorFlow.value = value }

    var chipBgColor: Int
        get() = chipBgColorFlow.value
        set(value) { chipBgColorFlow.value = value }

    var layoutScale: Float
        get() = layoutScaleFlow.value
        set(value) { layoutScaleFlow.value = value }

    var wallpaperScrimAlpha: Float
        get() = wallpaperScrimAlphaFlow.value
        set(value) { wallpaperScrimAlphaFlow.value = value }

    var verticalPadding: Float
        get() = verticalPaddingFlow.value
        set(value) { verticalPaddingFlow.value = value }

    var isFontBold: Boolean
        get() = isFontBoldFlow.value
        set(value) { isFontBoldFlow.value = value }

    var contentTopMargin: Float
        get() = contentTopMarginFlow.value
        set(value) { contentTopMarginFlow.value = value }

    var showCalendar: Boolean
        get() = calendarFlow.value
        set(value) { calendarFlow.value = value }

    var showAlarm: Boolean
        get() = alarmFlow.value
        set(value) { alarmFlow.value = value }

    var autoShowKeyboard: Boolean
        get() = autoShowKeyboardFlowState.value
        set(value) { autoShowKeyboardFlowState.value = value }

    var autoLaunchApp: Boolean
        get() = autoLaunchAppFlowState.value
        set(value) { autoLaunchAppFlowState.value = value }

    var currentSortOrder: SortOrder
        get() = sortOrderState.value
        private set(value) { sortOrderState.value = value }

    var onboardingCompleted: Boolean
        get() = onboardingCompletedState.value
        private set(value) { onboardingCompletedState.value = value }

    // --- Interface Implementation Flow Exports ---

    override val textShadowEnabledFlow: Flow<Boolean> = shadowFlow
    override val textColorFlow: Flow<Int> = colorFlow
    override val chipBackgroundColorFlow: Flow<Int> = chipBgColorFlow
    override val layoutScaleStateFlow: Flow<Float> = layoutScaleFlow
    override val wallpaperScrimAlphaStateFlow: Flow<Float> = wallpaperScrimAlphaFlow
    override val verticalPaddingStateFlow: Flow<Float> = verticalPaddingFlow
    override val isFontBoldStateFlow: Flow<Boolean> = isFontBoldFlow
    override val contentTopMarginScaleFlow: Flow<Float> = contentTopMarginFlow
    override val showCalendarEventFlow: Flow<Boolean> = calendarFlow
    override val showAlarmFlow: Flow<Boolean> = alarmFlow
    override val autoShowKeyboardFlow: Flow<Boolean> = autoShowKeyboardFlowState
    override val autoLaunchAppFlow: Flow<Boolean> = autoLaunchAppFlowState


    // --- Interface Implementation Methods ---

    override suspend fun setTextShadowEnabled(isEnabled: Boolean) {
        shadow = isEnabled
    }

    override suspend fun setTextColor(color: Int) {
        this.color = color
    }

    override suspend fun setChipBackgroundColor(color: Int) {
        this.chipBgColor = color
    }

    override suspend fun setLayoutScale(scale: Float) {
        layoutScale = scale
    }

    override suspend fun setWallpaperScrimAlpha(alpha: Float) {
        wallpaperScrimAlpha = alpha
    }

    override suspend fun setVerticalPadding(scale: Float) {
        verticalPadding = scale
    }

    override suspend fun setFontBold(isBold: Boolean) {
        isFontBold = isBold
    }

    override suspend fun setContentTopMarginScale(scale: Float) {
        contentTopMargin = scale
    }

    override suspend fun setShowCalendarEvent(isEnabled: Boolean) {
        showCalendar = isEnabled
    }

    override suspend fun setShowAlarm(isEnabled: Boolean) {
        showAlarm = isEnabled
    }

    override suspend fun setAutoShowKeyboard(isEnabled: Boolean) {
        autoShowKeyboard = isEnabled
    }

    override suspend fun setAutoLaunchApp(isEnabled: Boolean) {
        autoLaunchApp = isEnabled
    }

    override suspend fun setDoubleTapClipboard(isEnabled: Boolean) {
        doubleTapClipboard = isEnabled
    }

    override suspend fun setSortOrder(sortOrder: SortOrder) {
        sortOrderState.value = sortOrder
    }

    override suspend fun setOnboardingCompleted() {
        onboardingCompletedState.value = true
    }

    /**
     * Resets the repository to default values defined in AppConstants.
     */
    override suspend fun purgeRepository() {
        val wasOnboardingCompleted = onboardingCompletedState.value

        color = AppConstants.DEFAULT_TEXT_COLOR
        shadow = AppConstants.DEFAULT_TEXT_SHADOW_ENABLED
        chipBgColor = AppConstants.DEFAULT_CHIP_BG_COLOR

        layoutScale = AppConstants.DEFAULT_LAYOUT_SCALE
        wallpaperScrimAlpha = AppConstants.DEFAULT_WALLPAPER_SCRIM_ALPHA
        verticalPadding = AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR
        isFontBold = AppConstants.DEFAULT_FONT_BOLD
        contentTopMargin = AppConstants.DEFAULT_TOP_MARGIN

        showCalendar = AppConstants.DEFAULT_SHOW_CALENDAR
        showAlarm = AppConstants.DEFAULT_SHOW_ALARM
        autoShowKeyboard = AppConstants.DEFAULT_AUTO_SHOW_KEYBOARD
        autoLaunchApp = AppConstants.DEFAULT_AUTO_LAUNCH_APP

        sortOrderState.value = AppConstants.DEFAULT_SORT_ORDER
        doubleTapClipboard = AppConstants.DEFAULT_DOUBLE_TAP_CLIPBOARD

        favoritesAlignmentState.value = AppConstants.DEFAULT_FAVORITES_ALIGNMENT
        wallpaperSurfaceModeState.value = AppConstants.DEFAULT_WALLPAPER_SURFACE_MODE

        onboardingCompletedState.value = wasOnboardingCompleted
    }

    // HELPER

    fun setSortOrderForTest(sortOrder: SortOrder) {
        sortOrderState.value = sortOrder
    }

    fun setOnboardingCompletedForTest(completed: Boolean) {
        onboardingCompletedState.value = completed
    }

    // rotationLocked — neu mit WallpaperRepositoryImpl/BackupRepositoryImpl
    private val rotationLockedState = MutableStateFlow(false)

    var rotationLocked: Boolean
        get() = rotationLockedState.value
        set(value) { rotationLockedState.value = value }

    override val rotationLockedFlow: Flow<Boolean> = rotationLockedState

    override suspend fun setRotationLocked(isEnabled: Boolean) {
        rotationLocked = isEnabled
    }

    // favoritesAlignment
    private val favoritesAlignmentState =
        MutableStateFlow(AppConstants.DEFAULT_FAVORITES_ALIGNMENT)

    var favoritesAlignment: FavoritesAlignment
        get() = favoritesAlignmentState.value
        set(value) { favoritesAlignmentState.value = value }

    override val favoritesAlignmentFlow: Flow<FavoritesAlignment> = favoritesAlignmentState

    override suspend fun setFavoritesAlignment(alignment: FavoritesAlignment) {
        favoritesAlignment = alignment
    }

    // wallpaperSurfaceMode
    private val wallpaperSurfaceModeState =
        MutableStateFlow(AppConstants.DEFAULT_WALLPAPER_SURFACE_MODE)

    var wallpaperSurfaceMode: WallpaperSurfaceMode
        get() = wallpaperSurfaceModeState.value
        set(value) { wallpaperSurfaceModeState.value = value }

    override val wallpaperSurfaceModeFlow: Flow<WallpaperSurfaceMode> = wallpaperSurfaceModeState

    override suspend fun setWallpaperSurfaceMode(mode: WallpaperSurfaceMode) {
        wallpaperSurfaceMode = mode
    }
}