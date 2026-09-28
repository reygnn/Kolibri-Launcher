package com.github.reygnn.nyx_launcher.home

/**
 * Framework-free projection of a launcher shortcut, carrying only what the
 * selection policy needs. The `MainActivity` maps a platform `ShortcutInfo` into
 * this at the boundary; nothing here touches Android.
 */
internal data class ShortcutCandidate(
    val enabled: Boolean,
    val rank: Int,
    val shortLabel: String?,
    val longLabel: String?,
)

/** How many app shortcuts the home context menu surfaces. */
internal const val MAX_APP_SHORTCUTS = 4

/**
 * Pure launcher-shortcut selection policy for the home context menu: keep the
 * enabled shortcuts, order by ascending [ShortcutCandidate.rank], cap at
 * [MAX_APP_SHORTCUTS], and resolve each label as `shortLabel ?: longLabel` —
 * dropping any shortcut with no usable label.
 *
 * The label drop happens AFTER the cap, so a label-less shortcut still consumes
 * one of the [MAX_APP_SHORTCUTS] slots (i.e. it is not backfilled from a lower-
 * ranked one) — this preserves the exact behaviour of the original inline code.
 *
 * Returns the surviving shortcuts paired with their resolved label, in display
 * order. Android-free and total, so it is JVM-testable; the `ShortcutInfo` →
 * [ShortcutCandidate] projection, icon resolution and `startShortcut` stay in the
 * UI. Generic over [T] so the caller can carry the real shortcut object through to
 * the tap handler.
 */
internal fun <T> selectAppShortcuts(
    shortcuts: List<T>,
    candidate: (T) -> ShortcutCandidate,
): List<Pair<T, String>> =
    shortcuts
        .filter { candidate(it).enabled }
        .sortedBy { candidate(it).rank }
        .take(MAX_APP_SHORTCUTS)
        .mapNotNull { sc ->
            val c = candidate(sc)
            val label = c.shortLabel ?: c.longLabel ?: return@mapNotNull null
            sc to label
        }
