package com.github.reygnn.nyx_launcher.home

/** Where the long-press context-menu card is anchored, in [homeRoot] coordinates. */
internal data class MenuAnchor(val x: Int, val y: Int)

/**
 * Pure anchor math for the long-press context menu: given the source icon's
 * position and size (already translated into root coordinates) plus the card and
 * root sizes, decides where the card sits. Prefers above the icon, flips below
 * when there is no room, and clamps into the root minus [margin] on both axes.
 * The [coerceAtLeast] on the max guards the degenerate case where the card is as
 * wide/tall as the root (max would otherwise fall below min → IllegalArgument).
 *
 * Android-free and total, so it is JVM-testable; the view glue
 * (`getLocationInWindow`, `doOnLayout`, setting `translationX/Y`) stays in
 * MainActivity.positionContextMenu.
 */
internal fun contextMenuAnchor(
    iconX: Int,
    iconY: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    cardWidth: Int,
    cardHeight: Int,
    rootWidth: Int,
    rootHeight: Int,
    margin: Int,
): MenuAnchor {
    val maxX = (rootWidth - cardWidth - margin).coerceAtLeast(margin)
    val maxY = (rootHeight - cardHeight - margin).coerceAtLeast(margin)
    val x = (iconX + sourceWidth / 2 - cardWidth / 2).coerceIn(margin, maxX)
    val y = (if (iconY - cardHeight - margin >= margin) iconY - cardHeight - margin else iconY + sourceHeight + margin)
        .coerceIn(margin, maxY)
    return MenuAnchor(x = x, y = y)
}
