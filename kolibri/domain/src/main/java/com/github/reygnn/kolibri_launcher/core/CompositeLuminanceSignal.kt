package com.github.reygnn.kolibri_launcher.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide signal carrying the current in-memory wallpaper COMPOSITE's median luminance
 * (WALLPAPER_COMPOSITE_LIFECYCLE_SPEC v4.3, ACCEPTED_LIMITATIONS #1).
 *
 * The flattened composite lives only in an `:app` cache the `:domain` classifier cannot read.
 * The composite warm (`WallpaperDelegate`) samples the SOFTWARE composite's luminance during the
 * flatten and pushes it here; `ClassifyWallpaperUseCase` reads it. This is the same
 * inversion-of-control port as [SystemWallpaperColorsSignal] — a `:domain`/`core` type fed by
 * `:app`, so the dependency rule `:app → :domain` stays intact (no disk, no persisted pointer,
 * no layering breach).
 *
 * `StateFlow` so a subscriber sees the latest value on subscription. `null` = "no composite
 * luminance yet": a cold start before the first warm, or the wallpaper was cleared — the
 * classifier then falls back to its `layers[0]` heuristic (ACCEPTED_LIMITATIONS #1a). The held
 * value is the LAST warmed composite's luminance; a layer-set change triggers a new warm that
 * re-emits, with a brief eventual-consistency window while it runs (ACCEPTED_LIMITATIONS #1b).
 */
@Singleton
open class CompositeLuminanceSignal @Inject constructor() {

    private val _luminance = MutableStateFlow<Float?>(null)
    val luminance: StateFlow<Float?> = _luminance.asStateFlow()

    open fun emit(value: Float?) {
        _luminance.value = value
    }
}
