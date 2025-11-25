package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import kotlinx.coroutines.flow.Flow

// Das ist der Vertrag. Er sagt nur, WAS getan werden kann, nicht WIE.
interface SettingsRepository : Purgeable {
    val sortOrderFlow: Flow<SortOrder>
    val doubleTapToLockEnabledFlow: Flow<Boolean>
    val swipeDownToNotificationsEnabledFlow: Flow<Boolean>
    val readabilityModeFlow: Flow<String>

    suspend fun setSortOrder(sortOrder: SortOrder)
    suspend fun setDoubleTapToLock(isEnabled: Boolean)
    suspend fun setSwipeDownToNotifications(isEnabled: Boolean)
    suspend fun setReadabilityMode(mode: String)

    val onboardingCompletedFlow: Flow<Boolean>
    suspend fun setOnboardingCompleted()

    val textShadowEnabledFlow: Flow<Boolean>
    suspend fun setTextShadowEnabled(isEnabled: Boolean)

    val textColorFlow: Flow<Int>
    suspend fun setTextColor(color: Int)

    val chipBackgroundColorFlow: Flow<Int>
    suspend fun setChipBackgroundColor(color: Int)
    val layoutScaleStateFlow: Flow<Float>
    suspend fun setLayoutScale(scale: Float)

    val verticalPaddingStateFlow: Flow<Float>
    suspend fun setVerticalPadding(scale: Float)

    val isFontBoldStateFlow: Flow<Boolean>
    suspend fun setFontBold(isBold: Boolean)


    val showCalendarEventFlow: Flow<Boolean>
    suspend fun setShowCalendarEvent(isEnabled: Boolean)

    val showAlarmFlow: Flow<Boolean>
    suspend fun setShowAlarm(isEnabled: Boolean)

    val autoShowKeyboardFlow: Flow<Boolean>
    suspend fun setAutoShowKeyboard(isEnabled: Boolean)

    val autoLaunchAppFlow: Flow<Boolean>
    suspend fun setAutoLaunchApp(isEnabled: Boolean)

    /**
     * Flow für den Split-Mode Threshold in Pixel.
     *
     * - 0 = Default Verhalten (Android's canScrollVertically)
     * - > 0 = Custom Threshold (z.B. 42, 60, 100)
     *
     * Max: 512 Pixel
     */
    val splitModeThresholdFlow: Flow<Int>
    /**
     * Setzt den Split-Mode Threshold.
     *
     * @param thresholdPixels Threshold in Pixel (0-512)
     *                        0 = Android decides (fail-safe)
     *                        2-200 = Typische Power-User Werte
     */
    suspend fun setSplitModeThreshold(thresholdPixels: Int)
}