package com.github.reygnn.launcher.common.ui.wallpaperfab

/**
 * Shared visual constants for the wallpaper-edit FAB cluster and its
 * sibling overflow panel. Live here so a single tweak (e.g. matching
 * a future Material disabled-emphasis value) reaches both surfaces —
 * and both apps (kolibri + nyx) via common-ui.
 */

/**
 * Alpha applied to a disabled icon button on top of the regular
 * `isEnabled = false` grey-out. Matches the legacy bottom toolbar's
 * appearance for layer-reorder buttons. Mirror of Material's
 * `material_emphasis_disabled` (0.38f).
 */
const val DISABLED_ALPHA = 0.38f
