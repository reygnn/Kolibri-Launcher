package com.github.reygnn.nyx_launcher.home

/**
 * Pure decision for whether the device-grid re-fit must be SKIPPED for the current
 * (rotation-lock, orientation) state — extracted from `MainActivity.applyDeviceGrid`
 * so this load-bearing rule is JVM-testable (family Rule 10).
 *
 * The rule: while the launcher is rotation-locked (portrait), never persist a grid
 * fit measured in landscape. A locked user keeps their curated portrait grid, and a
 * transient landscape frame at cold start — before the orientation request settles —
 * would otherwise trigger a repack that `HomeLayoutRegridder` applies by keeping the
 * apps but NOT their positions, irreversibly scrambling the layout.
 */
object RotationGridPolicy {

    /** True when the caller must NOT run/persist a device-grid fit right now. */
    fun shouldSkipGridFit(rotationLocked: Boolean, isLandscape: Boolean): Boolean =
        rotationLocked && isLandscape
}
