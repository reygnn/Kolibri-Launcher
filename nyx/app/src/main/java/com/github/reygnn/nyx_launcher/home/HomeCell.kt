package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.LazySlotMembership
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId

/**
 * One rendering slot: empty, an app (launchable), or a folder (opens a sheet; its
 * icon is the derived 2×2 composite of [Folder.members]).
 *
 * [App.missing] marks a tile whose app is no longer installed (Windows-shortcut
 * model, root TODO.md): the reference is KEPT, rendered greyed, and interacting with
 * it offers to remove it. Folder members are not individually flagged as missing at
 * the tile level (a missing member is reached through its folder) — but the folder's
 * 2×2 composite only draws its INSTALLED members, so [Folder.presentMembers] carries
 * which members are currently installed. It is part of the cell's value identity so the
 * grid DiffUtil rebinds (and the composite re-renders) when a member is un/reinstalled;
 * without it a member uninstall would leave a stale composite (the [members] list is
 * unchanged, so DiffUtil would skip the folder tile).
 */
sealed interface HomeCell {
    data object Empty : HomeCell
    data class App(val id: ItemId, val key: ComponentKey, val missing: Boolean = false) : HomeCell
    data class Folder(
        val id: ItemId,
        val members: List<ComponentKey>,
        val presentMembers: List<ComponentKey> = members,
    ) : HomeCell
}

/**
 * [installed] is the set of currently-installed component keys. A key absent from a
 * NON-EMPTY [installed] set is "missing"; an EMPTY set means "not loaded yet" (a real
 * device always has apps), so nothing is flagged — this avoids greying every tile
 * during the cold-start enumeration window.
 */
private fun HomeItem.toCell(installed: Set<ComponentKey>): HomeCell = when (this) {
    is HomeItem.App -> HomeCell.App(id, key, missing = LazySlotMembership.isMissing(key, installed))
    // presentMembers drives the composite re-render on member un/reinstall (see [HomeCell]).
    // An EMPTY installed set means "not loaded yet" (same guard as App.missing above), so
    // treat all members as present rather than blanking every folder during cold start.
    is HomeItem.Folder -> HomeCell.Folder(
        id,
        members,
        presentMembers = if (installed.isEmpty()) members else members.filter { it in installed },
    )
}

/**
 * Dense row-major cells for one page (empties for gaps); index = y*columns + x.
 * [installed] flags missing app tiles (see [HomeCell.App.missing] / [toCell]); the
 * default empty set flags nothing (used by pure tests that don't exercise missing).
 */
fun HomeLayout.pageCells(page: Int, installed: Set<ComponentKey> = emptySet()): List<HomeCell> {
    val cols = grid.columns
    val rows = grid.rows
    // Filter to in-bounds cells BEFORE indexing: on-grid is a transition + regridder
    // invariant, but an imported/restored/hand-edited blob is saved verbatim with no
    // coordinate clamp. Without this guard an off-grid column (x >= cols) folds y*cols+x
    // onto a DIFFERENT valid cell's index and silently aliases over a neighbour; an
    // out-of-bounds item is simply ignored instead.
    val byIndex = items
        .filter { it.pos.page == page && it.pos.x in 0 until cols && it.pos.y in 0 until rows }
        .associateBy { it.pos.y * cols + it.pos.x }
    return (0 until cols * rows).map { index -> byIndex[index]?.item?.toCell(installed) ?: HomeCell.Empty }
}

/** Flat cell list for the dock (no empties). [installed] flags missing tiles (see [pageCells]). */
fun HomeLayout.dockCells(installed: Set<ComponentKey> = emptySet()): List<HomeCell> = dock.map { it.toCell(installed) }

/**
 * How many pages the pager renders: the occupied pages plus exactly one empty
 * trailing "landing" page as a standing drop target (HOME_CURATION_SPEC §10), i.e.
 * `highest-occupied-page + 2`, or 1 when the grid is empty. Interior empty pages
 * are implicitly included (they're below the highest occupied page). This is a
 * pure rendering affordance — the landing page is only persisted once something is
 * dropped on it (the append-on-drop transition), so the repository stays faithful.
 *
 * Capped at [HomeLayout.MAX_PAGES]: once the home is full of pages no empty landing
 * page is offered, so the user can't drag onto a page beyond the cap (the transitions
 * would reject it anyway) and the page-dot indicator never overflows.
 */
fun HomeLayout.renderedPageCount(): Int =
    items.maxOfOrNull { it.pos.page }?.let { minOf(it + 2, HomeLayout.MAX_PAGES) } ?: 1
