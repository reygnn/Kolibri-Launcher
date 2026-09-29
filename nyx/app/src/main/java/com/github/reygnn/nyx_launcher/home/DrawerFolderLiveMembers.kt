package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.LazySlotMembership

/**
 * The members an OPEN drawer-folder overlay should show right now (§Audit-3 A3-04): [members]
 * (the membership the overlay reflects, incl. optimistic bulk-adds) reconciled against the live
 * [installed] set — the same rule the drawer projection (`projectDrawerContent`) applies, so the
 * overlay never shows a member the drawer tile behind it would drop.
 *
 * - An uninstalled member is dropped, not greyed: drawer folders are a projection over the live
 *   app list, never a dead reference (unlike home folders). Persisted membership is untouched,
 *   so a reinstall while the overlay is still open brings the member back.
 * - Returns null when fewer than 2 live members remain — the projection dissolves such a folder
 *   (DFOLD-INV-1), so the caller closes the overlay.
 * - An empty [installed] means "not loaded" and filters nothing ([LazySlotMembership]).
 */
internal fun drawerFolderLiveMembers(
    members: List<ComponentKey>,
    installed: Set<ComponentKey>,
): List<ComponentKey>? =
    members.filterNot { LazySlotMembership.isMissing(it, installed) }.takeIf { it.size >= 2 }
