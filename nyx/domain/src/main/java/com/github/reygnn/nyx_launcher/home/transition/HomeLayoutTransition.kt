package com.github.reygnn.nyx_launcher.home.transition

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.FolderEditResult
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.LayoutEdit
import com.github.reygnn.nyx_launcher.home.model.MoveResult
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.model.Span

/**
 * Pure home-layout transitions. No repository, no dispatcher, no Android, no
 * clock/RNG — new ids arrive as a factory lambda (MOVE_ITEM_SPEC §0, MIU-INV-1).
 *
 * Every function is total (MIU-INV-2): exactly one result, never throws for
 * UI-reachable input. A structurally impossible input (unknown id, an app
 * already in the target folder, a non-folder rename target) collapses to the
 * "no change" result here; the calling use-case is where `silentError` fires
 * (loud in DEBUG). It is never dressed up as a user-facing `Rejected`.
 *
 * Truth tables: MOVE_ITEM_SPEC §3, REMOVE_FROM_FOLDER_SPEC §2. The tests mirror
 * them 1:1.
 */
object HomeLayoutTransition {

    // ============================ move (MOVE_ITEM_SPEC) ======================

    fun move(
        layout: HomeLayout,
        moving: ItemId,
        target: DropTarget,
        newFolderId: () -> ItemId,
    ): MoveResult {
        val source: HomeItem = layout.itemById(moving) ?: return MoveResult.NoOp
        // Preserve the moved grid item's span across the relocation (A1-17): dragging a
        // widget (v2, span > 1×1) must not shrink it back to 1×1. A dock source has no
        // span, so it falls back to the default. Mirrors the regridder, which likewise
        // keeps a relocated item's span.
        val sourceSpan = layout.items.firstOrNull { it.item.id == moving }?.span ?: Span()
        return moveResolved(layout, source, sourceSpan, moving, target, newFolderId)
    }

    /**
     * Shared core: place [source] (identified by [moving]) onto [target]. [sourceSpan]
     * is the moved item's span, threaded through so a relocation preserves it (a new
     * item passes the default 1×1). [moving] need NOT exist in [layout] — for a
     * brand-new item (see [place]) the removing/self-drop/occupant steps are simply
     * no-ops against it.
     */
    private fun moveResolved(
        layout: HomeLayout,
        source: HomeItem,
        sourceSpan: Span,
        moving: ItemId,
        target: DropTarget,
        newFolderId: () -> ItemId,
    ): MoveResult = when (target) {
        is DropTarget.Cell -> moveToCell(layout, source, sourceSpan, moving, target.pos, newFolderId)
        is DropTarget.DockSlot -> moveToDock(layout, source, moving, target.index)
        is DropTarget.GridInsert -> insertOnGrid(layout, source, sourceSpan, moving, target.page, target.index)
    }

    /**
     * Reorder-insert on the grid: put [source] at reading-order [index] on [page] and
     * shift to open that cell (Launcher3-style). The shift walks to the nearest gap: a
     * gap AFTER [index] pushes the block `[index, gap)` one cell forward; if the tail is
     * dense but a gap exists BEFORE [index] (e.g. the cell the source itself just
     * vacated), the block `(gap, index]` slides one cell back instead — so a reorder on a
     * page that still has any free cell never spills onto a new page. Only a genuinely
     * full page (no gap on either side) overflows its last occupant onto the next page's
     * first free cell (adding a page only when needed). An empty target cell is a plain
     * place with no shift; [index] == columns*rows appends. No item is ever dropped or
     * foldered — foldering is the [DropTarget.Cell] centre path.
     */
    private fun insertOnGrid(
        layout: HomeLayout,
        source: HomeItem,
        sourceSpan: Span,
        moving: ItemId,
        page: Int,
        index: Int,
    ): MoveResult {
        val cols = layout.grid.columns
        val cells = cols * layout.grid.rows
        if (page < 0 || page > layout.pages || page >= HomeLayout.MAX_PAGES || index < 0 || index > cells) {
            return MoveResult.Rejected(MoveResult.Reason.OFF_GRID)
        }
        val base = layout.removing(moving)

        // Past the last cell, or a brand-new trailing page → append at the first
        // free cell from this page on (adds a trailing page if all are full). A home
        // already at the page cap with no free cell is genuinely full → reject.
        if (index >= cells || page >= base.pages) {
            val pos = firstFreeCellFrom(base, page.coerceAtMost(base.pages))
            if (pos.page >= HomeLayout.MAX_PAGES) return MoveResult.Rejected(MoveResult.Reason.OFF_GRID)
            val pages = if (pos.page >= base.pages) pos.page + 1 else base.pages
            return resultOf(layout, base.copy(pages = pages, items = base.items + PlacedItem(source, pos, sourceSpan)))
        }

        val pageItems = base.items.filter { it.pos.page == page }
        val occupied = pageItems.associateBy { it.pos.y * cols + it.pos.x }

        // Empty target cell → straight place, no shift.
        if (occupied[index] == null) {
            return resultOf(layout, base.copy(items = base.items + PlacedItem(source, cellOf(page, index, cols), sourceSpan)))
        }

        // Nearest gap: prefer a forward gap (shift the block [index, fwdGap) one cell
        // up), else fall back to a backward gap (shift (backGap, index] one cell down).
        // A reorder that frees the source's own cell provides such a backward gap, so a
        // page reordered within itself never needs a new page.
        var fwdGap = index + 1
        while (fwdGap < cells && occupied[fwdGap] != null) fwdGap++
        var backGap = index - 1
        while (backGap >= 0 && occupied[backGap] != null) backGap--

        if (fwdGap < cells || backGap >= 0) {
            val shiftRange: IntRange
            val delta: Int
            if (fwdGap < cells) {
                shiftRange = index until fwdGap; delta = 1
            } else {
                shiftRange = (backGap + 1)..index; delta = -1
            }
            val shifted = ArrayList<PlacedItem>(pageItems.size + 1)
            for (p in pageItems) {
                val li = p.pos.y * cols + p.pos.x
                shifted.add(if (li in shiftRange) p.copy(pos = cellOf(page, li + delta, cols)) else p)
            }
            shifted.add(PlacedItem(source, cellOf(page, index, cols), sourceSpan))
            val items = base.items.filterNot { it.pos.page == page } + shifted
            return resultOf(layout, base.copy(items = items))
        }

        // Genuinely full page (no gap either side): forward-shift and spill the
        // last-cell occupant onto the next page's first free cell.
        val newPageItems = ArrayList<PlacedItem>(pageItems.size + 1)
        var overflow: PlacedItem? = null
        for (p in pageItems) {
            val li = p.pos.y * cols + p.pos.x
            if (li < index) {
                newPageItems.add(p) // before the insert point — unchanged
            } else {
                val to = li + 1
                if (to < cells) newPageItems.add(p.copy(pos = cellOf(page, to, cols)))
                else overflow = p // last-cell occupant of a dense page spills over
            }
        }
        newPageItems.add(PlacedItem(source, cellOf(page, index, cols), sourceSpan))

        var items = base.items.filterNot { it.pos.page == page } + newPageItems
        var pages = base.pages
        overflow?.let {
            val pos = firstFreeCellFrom(base.copy(items = items), page + 1)
            // Nowhere within the cap for the spilled occupant → the home is full; reject
            // the whole insert rather than drop the app or exceed the page cap.
            if (pos.page >= HomeLayout.MAX_PAGES) return MoveResult.Rejected(MoveResult.Reason.OFF_GRID)
            if (pos.page >= pages) pages = pos.page + 1
            items = items + it.copy(pos = pos)
        }
        return resultOf(layout, base.copy(pages = pages, items = items))
    }

    private fun resultOf(before: HomeLayout, after: HomeLayout): MoveResult =
        if (after == before) MoveResult.NoOp else MoveResult.Moved(after)

    private fun moveToCell(
        layout: HomeLayout,
        source: HomeItem,
        sourceSpan: Span,
        moving: ItemId,
        pos: CellPos,
        newFolderId: () -> ItemId,
    ): MoveResult {
        val currentOnGrid = layout.items.firstOrNull { it.item.id == moving }
        if (currentOnGrid != null && currentOnGrid.pos == pos) return MoveResult.NoOp

        offGridReason(layout, pos)?.let { return MoveResult.Rejected(it) }

        val occupant: HomeItem? =
            layout.items.firstOrNull { it.item.id != moving && it.pos == pos }?.item

        return when (occupant) {
            null -> {
                val base = layout.removing(moving)
                val pages = if (pos.page == layout.pages) layout.pages + 1 else base.pages
                MoveResult.Moved(base.copy(pages = pages, items = base.items + PlacedItem(source, pos, sourceSpan)))
            }

            is HomeItem.App -> when (source) {
                is HomeItem.App -> {
                    // §7-D1: target first, then the dragged app.
                    val folder = HomeItem.Folder(newFolderId(), "", listOf(occupant.key, source.key))
                    val base = layout.removing(moving).removing(occupant.id)
                    MoveResult.FolderCreated(base.copy(items = base.items + PlacedItem(folder, pos)), folder.id)
                }
                is HomeItem.Folder -> MoveResult.Rejected(MoveResult.Reason.TARGET_OCCUPIED_INCOMPATIBLE)
            }

            is HomeItem.Folder -> when (source) {
                is HomeItem.App -> {
                    if (source.key in occupant.members) return MoveResult.NoOp // IHM-INV-7 guard
                    val updated = occupant.copy(members = occupant.members + source.key)
                    MoveResult.AddedToFolder(layout.removing(moving).replacingItem(occupant.id, updated), occupant.id)
                }
                is HomeItem.Folder -> MoveResult.Rejected(MoveResult.Reason.TARGET_OCCUPIED_INCOMPATIBLE)
            }
        }
    }

    private fun moveToDock(
        layout: HomeLayout,
        source: HomeItem,
        moving: ItemId,
        index: Int,
    ): MoveResult {
        if (index < 0) return MoveResult.Rejected(MoveResult.Reason.OFF_GRID)

        // The dock is a flat ordered list: a drop INSERTS at [index], shifting the
        // rest right — it never "lands on" an occupied slot (§7-D3). [index] is
        // exclusive of the source (the UI counts only the other icons left of the
        // finger), so it is already relative to dockWithoutSource; clamp for the
        // past-the-end append case.
        val dockWithoutSource = layout.dock.filterNot { it.id == moving }
        // Capacity is per grid columns. A reorder within the dock never grows it (the
        // source is excluded then re-added), so it is always allowed — including tidying
        // a transient over-capacity dock (a grid shrink before the regridder re-homes the
        // overflow). Only an INCOMING item (from grid/drawer) that would push the dock
        // past capacity is rejected.
        val isReorderWithinDock = dockWithoutSource.size < layout.dock.size
        if (!isReorderWithinDock && dockWithoutSource.size >= layout.grid.columns) {
            return MoveResult.Rejected(MoveResult.Reason.DOCK_FULL)
        }
        // An index past the (source-inclusive) dock size is out of range — the UI
        // never counts more slots than that. Reject it rather than silently clamp
        // and append, mirroring emptyTargetReason's DockSlot check and the GridInsert
        // path (which rejects index > cells). The legitimate past-the-end append
        // (index == dock.size, source counted) still passes and is clamped below.
        if (index > layout.dock.size) return MoveResult.Rejected(MoveResult.Reason.OFF_GRID)
        val insertIdx = index.coerceAtMost(dockWithoutSource.size)
        val newDock = dockWithoutSource.toMutableList().apply { add(insertIdx, source) }
        if (newDock == layout.dock) return MoveResult.NoOp // reorder to the same spot
        return MoveResult.Moved(layout.removing(moving).copy(dock = newDock))
    }

    // =================== removeFromFolder (REMOVE_FROM_FOLDER_SPEC) ==========

    fun removeFromFolder(
        layout: HomeLayout,
        folder: ItemId,
        member: ComponentKey,
        target: DropTarget,
        newId: () -> ItemId,
    ): FolderEditResult {
        val folderItem = layout.itemById(folder) as? HomeItem.Folder ?: return FolderEditResult.NoOp
        if (member !in folderItem.members) return FolderEditResult.NoOp // RFF-INV-4
        // IHM-INV-7 forbids duplicate members, but the total function must stay defined for an
        // invariant-violating (imported/hand-edited) blob. A duplicated member makes "remove
        // one" ambiguous: filterNot below strips EVERY copy, wrongly dissolving the folder and
        // losing a copy. Treat it as structurally-impossible input → NoOp (the use-case fires
        // silentError in DEBUG), leaving the layout untouched rather than mangling it.
        if (folderItem.members.count { it == member } > 1) return FolderEditResult.NoOp
        val placement = layout.placementOf(folder) ?: return FolderEditResult.NoOp

        // Scoped IHM-INV-7: a drop onto ANOTHER grid folder moves the member straight into
        // it — no extraction to empty space. Folder B is its own uniqueness scope, so the
        // member may already be a tile or a member of a third folder; only a duplicate WITHIN
        // B is refused. A drop on the source folder's own cell (targetFolder.id == folder) is
        // not a folder-move and falls through to the reject-occupied path below (that keeps the
        // existing own-cell TARGET_OCCUPIED behaviour). A grid App occupant (not a folder) also
        // falls through → still Rejected (folder-from-extraction stays v2).
        val targetFolder: HomeItem.Folder? = (target as? DropTarget.Cell)?.let { t ->
            layout.items.firstOrNull { it.pos == t.pos }?.item as? HomeItem.Folder
        }
        if (targetFolder != null && targetFolder.id != folder) {
            if (member in targetFolder.members) return FolderEditResult.NoOp // B-scope uniqueness
            val withB = layout.replacingItem(
                targetFolder.id, targetFolder.copy(members = targetFolder.members + member),
            )
            val remainingInA = folderItem.members.filterNot { it == member }
            return if (remainingInA.size >= 2) {
                val shrunk = withB.replacingItem(folder, folderItem.copy(members = remainingInA))
                FolderEditResult.MovedBetweenFolders(shrunk, from = folder, to = targetFolder.id)
            } else {
                // A drops to one member → dissolves; survivor promoted to A's old placement
                // (RFF-INV-1/-2). The moved member travels as a raw ComponentKey into B.
                // Scoped IHM-INV-7: if the survivor is ALSO already a top-level tile, do NOT
                // mint a duplicate — leave the existing tile in place and just retire A (so
                // zero new ids), otherwise mint the one survivor id.
                val (dissolved, survivorId) =
                    promoteSurvivor(withB.removing(folder), remainingInA.single(), placement, newId)
                FolderEditResult.MovedBetweenFoldersDissolve(
                    dissolved, to = targetFolder.id, survivor = survivorId,
                )
            }
        }

        emptyTargetReason(layout, target)?.let { return FolderEditResult.Rejected(it) }

        val remaining = folderItem.members.filterNot { it == member }
        return if (remaining.size >= 2) {
            // Scoped IHM-INV-7: if `member` is ALSO already a top-level tile, do NOT mint a
            // second one — relocate the existing tile to the target (mirrors place()/HEU-INV-1).
            // RFF-INV-5: one new id in the normal case, zero when an existing tile is reused.
            val shrunk = layout.replacingItem(folder, folderItem.copy(members = remaining))
            val (out, extractedId) = promoteToTarget(shrunk, member, target, newId)
            FolderEditResult.Extracted(out, extractedId)
        } else {
            // remaining.size == 1 → dissolve. RFF-INV-5: extracted then survivor (id order).
            // Scoped IHM-INV-7: either the extracted member or the survivor (or both) may
            // already be top-level tiles — each reuses its existing tile instead of minting a
            // duplicate. The extracted member is relocated to the target; the survivor is
            // promoted to the folder's old cell only if it is not already a tile.
            val withoutFolder = layout.removing(folder)
            val (withMember, extractedId) = promoteToTarget(withoutFolder, member, target, newId)
            val (out, survivorId) = promoteSurvivor(withMember, remaining.single(), placement, newId)
            FolderEditResult.FolderDissolved(out, extractedId, survivorId)
        }
    }

    // ===================== place / remove / rename (HOME_EDIT) ===============

    fun place(
        layout: HomeLayout,
        app: ComponentKey,
        target: DropTarget,
        newId: () -> ItemId,
    ): MoveResult {
        // HEU-INV-1 (scoped IHM-INV-7): a TOP-LEVEL occurrence (grid ∪ dock is one scope)
        // still moves rather than duplicating — an app is at most one top-level thing. But
        // folder membership is a SEPARATE scope now: an app that lives ONLY in a folder is
        // placed fresh (a drawer drop onto an empty cell makes it a tile in ADDITION to the
        // membership; a drop onto another folder adds it there — moveResolved's occupant path).
        // The old `isFolderMember ⇒ NoOp` guard is therefore gone.
        layout.topLevelIdOf(app)?.let { return move(layout, it, target, newId) }
        val fresh = HomeItem.App(newId(), app)
        return moveResolved(layout, fresh, Span(), fresh.id, target, newId)
    }

    fun remove(layout: HomeLayout, id: ItemId): LayoutEdit {
        // HEU-INV-2: removing a folder drops only the folder item; member apps
        // are never lost (they remain reachable in the drawer). No dissolve.
        if (layout.itemById(id) == null) return LayoutEdit.NoOp
        return LayoutEdit.Changed(layout.removing(id))
    }

    fun renameFolder(layout: HomeLayout, folder: ItemId, title: String): LayoutEdit {
        val f = layout.itemById(folder) as? HomeItem.Folder ?: return LayoutEdit.NoOp
        if (f.title == title) return LayoutEdit.NoOp
        return LayoutEdit.Changed(layout.replacingItem(folder, f.copy(title = title)))
    }

    // ============================ pure helpers ==============================

    private sealed interface Placement {
        data class Grid(val pos: CellPos) : Placement
        data class Dock(val index: Int) : Placement
    }

    private fun HomeLayout.itemById(id: ItemId): HomeItem? =
        items.firstOrNull { it.item.id == id }?.item ?: dock.firstOrNull { it.id == id }

    private fun HomeLayout.placementOf(id: ItemId): Placement? {
        items.firstOrNull { it.item.id == id }?.let { return Placement.Grid(it.pos) }
        val di = dock.indexOfFirst { it.id == id }
        return if (di >= 0) Placement.Dock(di) else null
    }

    private fun HomeLayout.topLevelIdOf(key: ComponentKey): ItemId? =
        items.firstOrNull { (it.item as? HomeItem.App)?.key == key }?.item?.id
            ?: dock.firstOrNull { (it as? HomeItem.App)?.key == key }?.id

    private fun HomeLayout.removing(id: ItemId): HomeLayout =
        copy(items = items.filterNot { it.item.id == id }, dock = dock.filterNot { it.id == id })

    /** [CellPos] for a linear reading-order index (`y*columns + x`) on [page]. */
    private fun cellOf(page: Int, index: Int, cols: Int) = CellPos(page, index % cols, index / cols)

    /**
     * First free cell scanning pages from [startPage] on (row-major within a page);
     * if none, the first cell of a new trailing page (`CellPos(pages, 0, 0)`).
     */
    private fun firstFreeCellFrom(layout: HomeLayout, startPage: Int): CellPos {
        val cols = layout.grid.columns
        val cells = cols * layout.grid.rows
        for (page in startPage until layout.pages) {
            val occ = layout.items.filter { it.pos.page == page }
                .mapTo(HashSet()) { it.pos.y * cols + it.pos.x }
            for (li in 0 until cells) if (li !in occ) return cellOf(page, li, cols)
        }
        return CellPos(layout.pages, 0, 0)
    }

    private fun HomeLayout.replacingItem(id: ItemId, newItem: HomeItem): HomeLayout =
        copy(items = items.map { if (it.item.id == id) it.copy(item = newItem) else it })

    /** OFF_GRID reason for a cell, or null if in bounds ([0,pages] allows append). */
    private fun offGridReason(layout: HomeLayout, pos: CellPos): MoveResult.Reason? {
        val g = layout.grid
        // The landing page (index == pages) is a valid target UNLESS it would exceed
        // the page cap, so clamp the upper bound to MAX_PAGES - 1.
        val onGrid = pos.page in 0..minOf(layout.pages, HomeLayout.MAX_PAGES - 1) &&
            pos.x in 0 until g.columns &&
            pos.y in 0 until g.rows
        return if (onGrid) null else MoveResult.Reason.OFF_GRID
    }

    /** Reason why [target] is not a free landing spot for a NEW item, or null. */
    private fun emptyTargetReason(layout: HomeLayout, target: DropTarget): MoveResult.Reason? =
        when (target) {
            is DropTarget.Cell -> offGridReason(layout, target.pos)
                ?: if (layout.items.any { it.pos == target.pos }) MoveResult.Reason.TARGET_OCCUPIED_INCOMPATIBLE else null
            // Folder extraction has no reorder-shift: a reorder-insert target is
            // treated as a plain placement at its cell (rejected if occupied).
            is DropTarget.GridInsert -> gridInsertCell(layout, target).let { pos ->
                offGridReason(layout, pos)
                    ?: if (layout.items.any { it.pos == pos }) MoveResult.Reason.TARGET_OCCUPIED_INCOMPATIBLE else null
            }
            // A dock drop inserts at [index], shifting the rest right — any in-range
            // index is a valid landing spot; only a full dock or an out-of-range
            // index is rejected.
            is DropTarget.DockSlot -> when {
                layout.dock.size >= layout.grid.columns -> MoveResult.Reason.DOCK_FULL
                target.index < 0 || target.index > layout.dock.size -> MoveResult.Reason.OFF_GRID
                else -> null
            }
        }

    /** Adds a NEW [item] at an already-validated-empty [target]. */
    private fun placeNewAtTarget(layout: HomeLayout, item: HomeItem, target: DropTarget): HomeLayout =
        when (target) {
            is DropTarget.Cell -> {
                val pages = if (target.pos.page == layout.pages) layout.pages + 1 else layout.pages
                layout.copy(pages = pages, items = layout.items + PlacedItem(item, target.pos))
            }
            is DropTarget.GridInsert -> gridInsertCell(layout, target).let { pos ->
                val pages = if (pos.page == layout.pages) layout.pages + 1 else layout.pages
                layout.copy(pages = pages, items = layout.items + PlacedItem(item, pos))
            }
            is DropTarget.DockSlot -> {
                val idx = target.index.coerceIn(0, layout.dock.size)
                layout.copy(dock = layout.dock.toMutableList().apply { add(idx, item) })
            }
        }

    /**
     * Concrete target cell for a folder-extract [DropTarget.GridInsert]. An index
     * past the last cell (`>= columns*rows`, e.g. a drop on the right edge of the
     * bottom-right cell → `li + 1 == cells`) would map to an off-grid cell and be
     * rejected; fall back to the first free cell instead so the extraction lands.
     */
    private fun gridInsertCell(layout: HomeLayout, target: DropTarget.GridInsert): CellPos {
        val cells = layout.grid.columns * layout.grid.rows
        return if (target.index in 0 until cells) cellOf(target.page, target.index, layout.grid.columns)
        else firstFreeCellFrom(layout, 0)
    }

    /**
     * Promote [key] to a top-level tile at an explicit [target] drop — SCOPED IHM-INV-7:
     * if [key] is already a top-level tile, RELOCATE that existing tile to [target]
     * (reusing its [ItemId]) rather than minting a second occurrence (mirrors [place] /
     * HEU-INV-1). [target] must be pre-validated empty by the caller. Returns the new
     * layout and the surviving top-level id (existing → zero new ids; else one).
     */
    private fun promoteToTarget(
        layout: HomeLayout,
        key: ComponentKey,
        target: DropTarget,
        newId: () -> ItemId,
    ): Pair<HomeLayout, ItemId> {
        val existing = layout.topLevelIdOf(key)
        val id = existing ?: newId()
        val base = if (existing != null) layout.removing(existing) else layout
        return placeNewAtTarget(base, HomeItem.App(id, key), target) to id
    }

    /**
     * Promote [key] as a dissolved folder's survivor to its old [placement] — SCOPED
     * IHM-INV-7: if [key] is already a top-level tile, do NOT mint a second one; the
     * existing tile stays put and the freed cell/slot is simply left empty. Returns the
     * layout and the surviving top-level id (existing → zero new ids; else one).
     */
    private fun promoteSurvivor(
        layout: HomeLayout,
        key: ComponentKey,
        placement: Placement,
        newId: () -> ItemId,
    ): Pair<HomeLayout, ItemId> {
        layout.topLevelIdOf(key)?.let { return layout to it }
        val survivor = HomeItem.App(newId(), key)
        return placeAtPlacement(layout, survivor, placement) to survivor.id
    }

    /** Adds [item] at a deterministic [placement] (a dissolved folder's old spot). */
    private fun placeAtPlacement(layout: HomeLayout, item: HomeItem, placement: Placement): HomeLayout =
        when (placement) {
            is Placement.Grid -> layout.copy(items = layout.items + PlacedItem(item, placement.pos))
            is Placement.Dock -> {
                val idx = placement.index.coerceIn(0, layout.dock.size)
                layout.copy(dock = layout.dock.toMutableList().apply { add(idx, item) })
            }
        }
}
