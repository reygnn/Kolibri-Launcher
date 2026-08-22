package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.model.WallpaperSurfaceMode
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import kotlinx.coroutines.flow.Flow

// Das ist der Vertrag. Er sagt nur, WAS getan werden kann, nicht WIE.
interface SettingsRepository : Purgeable {
    val sortOrderFlow: Flow<SortOrder>

    suspend fun setSortOrder(sortOrder: SortOrder)

    val onboardingCompletedFlow: Flow<Boolean>
    suspend fun setOnboardingCompleted()

    val textShadowEnabledFlow: Flow<Boolean>
    suspend fun setTextShadowEnabled(isEnabled: Boolean)

    val textColorFlow: Flow<Int>
    suspend fun setTextColor(color: Int)

    val layoutScaleStateFlow: Flow<Float>
    suspend fun setLayoutScale(scale: Float)

    val wallpaperScrimAlphaStateFlow: Flow<Float>
    suspend fun setWallpaperScrimAlpha(alpha: Float)

    val verticalPaddingStateFlow: Flow<Float>
    suspend fun setVerticalPadding(scale: Float)

    val isFontBoldStateFlow: Flow<Boolean>
    suspend fun setFontBold(isBold: Boolean)

    val favoritesAlignmentFlow: Flow<FavoritesAlignment>
    suspend fun setFavoritesAlignment(alignment: FavoritesAlignment)

    val wallpaperSurfaceModeFlow: Flow<WallpaperSurfaceMode>
    suspend fun setWallpaperSurfaceMode(mode: WallpaperSurfaceMode)


    val showCalendarEventFlow: Flow<Boolean>
    suspend fun setShowCalendarEvent(isEnabled: Boolean)

    val showAlarmFlow: Flow<Boolean>
    suspend fun setShowAlarm(isEnabled: Boolean)

    val autoShowKeyboardFlow: Flow<Boolean>
    suspend fun setAutoShowKeyboard(isEnabled: Boolean)

    val autoLaunchAppFlow: Flow<Boolean>
    suspend fun setAutoLaunchApp(isEnabled: Boolean)

    val contentTopMarginScaleFlow: Flow<Float>
    suspend fun setContentTopMarginScale(scale: Float)

    val rotationLockedFlow: Flow<Boolean>
    suspend fun setRotationLocked(isEnabled: Boolean)
}