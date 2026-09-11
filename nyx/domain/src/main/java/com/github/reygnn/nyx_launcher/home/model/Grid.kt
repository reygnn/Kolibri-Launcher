package com.github.reygnn.nyx_launcher.home.model

/**
 * Grid dimensions of a single home page (e.g. 4×6).
 *
 * Device-derived, not fixed (ICON_HOME_MODEL_SPEC §10): a [GridSpecProvider]
 * computes columns/rows from the screen size, and [HomeLayoutRegridder] re-fits
 * the layout onto it at cold start. An explicit user-facing Settings override is
 * still open.
 */
data class GridSpec(val columns: Int, val rows: Int)

/**
 * A cell on the paged home grid.
 *
 * The dock is seatless — it holds a flat ordered list of items and uses no
 * [CellPos] (see [HomeLayout.dock]).
 */
data class CellPos(val page: Int, val x: Int, val y: Int)

/**
 * How many cells an item spans. v1 is always (1, 1); spans > 1 are reserved for
 * widgets (v2), so the field exists now to keep that lift from breaking callers.
 */
data class Span(val w: Int = 1, val h: Int = 1)
