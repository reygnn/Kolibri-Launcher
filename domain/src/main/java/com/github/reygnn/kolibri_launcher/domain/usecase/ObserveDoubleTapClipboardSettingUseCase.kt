/*
 * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Observes whether the double-tap clipboard action is enabled.
 *
 * Read once per double tap by `GestureDelegate` to choose between performing
 * the clipboard action and showing the enable-it hint. It is the ONLY reader:
 * whether a double tap consumes the touch sequence no longer depends on this
 * setting (a detected double tap always consumes — see `HomeGestureLayout`),
 * so there is no second consumer this value must stay consistent with.
 */
class ObserveDoubleTapClipboardSettingUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(): Flow<Boolean> = settingsRepository.doubleTapClipboardEnabledFlow
}
