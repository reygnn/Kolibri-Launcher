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
 * The sibling [GetDoubleTapClipboardSettingUseCase] answers "is it on right
 * now?" for the moment a gesture actually fires. This one exists because the
 * touch layer needs the answer *synchronously*, before the gesture is
 * dispatched, to decide whether the double tap consumes the follow-on
 * long-press and swipe — so the UI keeps a continuously updated copy rather
 * than a snapshot that is one gesture behind.
 */
class ObserveDoubleTapClipboardSettingUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(): Flow<Boolean> = settingsRepository.doubleTapClipboardEnabledFlow
}
