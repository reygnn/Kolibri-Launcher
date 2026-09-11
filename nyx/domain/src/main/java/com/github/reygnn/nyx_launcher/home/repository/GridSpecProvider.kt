package com.github.reygnn.nyx_launcher.home.repository

import com.github.reygnn.nyx_launcher.home.model.GridSpec

/**
 * Supplies the device-derived home [GridSpec] (columns × rows), so the grid
 * scales with the screen instead of a hard-coded 4×6 (ICON_HOME_MODEL_SPEC §10).
 *
 * Android seam: the implementation reads display metrics, so it lives in `:data`
 * while the pure regrid transition ([com.github.reygnn.nyx_launcher.home.transition.HomeLayoutRegridder])
 * and its use case depend only on this interface.
 */
fun interface GridSpecProvider {
    fun deviceGrid(): GridSpec
}
