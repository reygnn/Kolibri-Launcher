package com.github.reygnn.kolibri_launcher

import kotlinx.coroutines.flow.Flow

// Das ist der Vertrag. Er sagt nur, WAS getan werden kann, nicht WIE.
interface SettingsRepository : Purgeable {
    val sortOrderFlow: Flow<SortOrder>
    val doubleTapToLockEnabledFlow: Flow<Boolean>
    val readabilityModeFlow: Flow<String>

    suspend fun setSortOrder(sortOrder: SortOrder)
    suspend fun setDoubleTapToLock(isEnabled: Boolean)
    suspend fun setReadabilityMode(mode: String)

    val onboardingCompletedFlow: Flow<Boolean>
    suspend fun setOnboardingCompleted()

    val textShadowEnabledFlow: Flow<Boolean>
    suspend fun setTextShadowEnabled(isEnabled: Boolean)

    val textColorFlow: Flow<Int>
    suspend fun setTextColor(color: Int)
}