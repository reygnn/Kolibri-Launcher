package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.AppDrawerMode
import com.github.reygnn.kolibri_launcher.domain.model.AppDrawerSurfaceClassification
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Resolves the user's [AppDrawerMode] setting into a final
 * [AppDrawerSurfaceClassification] (LIGHT or DARK).
 *
 * Hysteresis was deliberately omitted. Kolibri-internal wallpapers
 * are pure-static data classes (URI + transforms; no animation, no
 * sensor, no time-of-day). System-wallpaper changes flow through
 * `WallpaperManager`'s `colorHints`, which the OS already publishes
 * as a stable boolean per wallpaper. Neither input flaps on its own,
 * so a deadband would be cosmetic. **Re-evaluation trigger:** add
 * hysteresis (and a `lastClassification` persistence key) once
 * dynamic Kolibri-internal layers are introduced — sensor parallax,
 * time-of-day swaps, etc.
 *
 * Commit-1 scope: AUTO resolves to DARK (regression-safe; matches the
 * pre-feature behaviour of the AppDrawer's hardcoded `#DD000000`
 * background). Commit 2 wires the wallpaper classifier into the AUTO
 * branch.
 */
class ResolveAppDrawerSurfaceUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    operator fun invoke(): Flow<AppDrawerSurfaceClassification> =
        settingsRepository.appDrawerModeFlow.map { mode ->
            when (mode) {
                AppDrawerMode.LIGHT -> AppDrawerSurfaceClassification.LIGHT
                AppDrawerMode.DARK -> AppDrawerSurfaceClassification.DARK
                AppDrawerMode.AUTO -> AppDrawerSurfaceClassification.DARK
            }
        }
}
