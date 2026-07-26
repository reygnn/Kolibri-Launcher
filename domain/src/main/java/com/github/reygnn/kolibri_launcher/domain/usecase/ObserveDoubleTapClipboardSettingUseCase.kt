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
 * The touch layer needs this answer *synchronously*, before a gesture is
 * dispatched, to decide whether the double tap consumes the follow-on
 * long-press and swipe — so the UI keeps a continuously updated copy rather
 * than reading DataStore per gesture. Both the suppression decision and the
 * action decision read that one copy, so they can never disagree.
 */
class ObserveDoubleTapClipboardSettingUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(): Flow<Boolean> = settingsRepository.doubleTapClipboardEnabledFlow
}
