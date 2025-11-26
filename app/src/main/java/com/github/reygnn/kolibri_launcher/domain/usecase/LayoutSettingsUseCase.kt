package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

// 1. Get Layout Settings (Alle 4 Flows)
class GetLayoutSettingsUseCase @Inject constructor(
    private val repository: SettingsRepository
) {
    val layoutScale: Flow<Float> = repository.layoutScaleStateFlow
    val verticalPadding: Flow<Float> = repository.verticalPaddingStateFlow
    val isFontBold: Flow<Boolean> = repository.isFontBoldStateFlow
    val contentTopMargin: Flow<Float> = repository.contentTopMarginScaleFlow
}

// 2. Setters
class SetLayoutScaleUseCase @Inject constructor(private val repository: SettingsRepository) {
    suspend operator fun invoke(scale: Float) = repository.setLayoutScale(scale)
}

class SetVerticalPaddingUseCase @Inject constructor(private val repository: SettingsRepository) {
    suspend operator fun invoke(scale: Float) = repository.setVerticalPadding(scale)
}

class SetFontBoldUseCase @Inject constructor(private val repository: SettingsRepository) {
    suspend operator fun invoke(isBold: Boolean) = repository.setFontBold(isBold)
}

class SetContentTopMarginUseCase @Inject constructor(private val repository: SettingsRepository) {
    suspend operator fun invoke(scale: Float) = repository.setContentTopMarginScale(scale)
}