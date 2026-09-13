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
        return moveResolved(layout, source, moving, target, newFolderId)
    }

    /**
     * Shared core: place [source] (identified by [moving]) onto [target].
     * [moving] need NOT exist in [layout] — for a brand-new item (see [place])
     * the removing/self-drop/occupant steps are simply no-ops against it.
     */
    private fun moveResolved(
        layout: HomeLayout,
        source: HomeItem,
        moving: ItemId,
        target: DropTarget,
        newFolderId: () -> ItemId,
    ): MoveResult = when (target) {
        is DropTarget.Cell -> moveToCell(layout, source, moving, target.pos, newFolderId)
        is DropTarget.DockSlot -> moveToDock(layout, source, moving, target.index)
        is DropTarget.GridInsert -> insertOnGrid(layout, source, moving, target.page, target.index)
    }

    /**
     * Reorder-insert on the grid: put [source] at reading-order [index] on [page]
     * and shift the occupant there — and the following ones — one cell forward,
     * stopping at the first gap that absorbs the shift (Launcher3-style). If the
     * page is dense from [index] to its end, the last occupant spills onto the next
     * page's first free cell (adding a page only when needed). An empty target cell
     * is a plain place with no shift; [index] == columns*rows appends. No item is
     * ever dropped or foldered — foldering is the [DropTarget.Cell] centre path.
     */
    private fun insertOnGrid(
        layout: HomeLayout,
        source: HomeItem,
        moving: ItemId,
        page: Int,
        index: Int,
    ): MoveResult {
        val cols = layout.grid.columns
        val cells = cols * layout.grid.rows
        if (page < 0 || page > layout.pages || index < 0 || index > cells) {
            return MoveResult.Rejected(MoveResult.Reason.OFF_GRID)
        }
        val base = layout.removing(moving)

        // Past the last cell, or a brand-new trailing page → append at the first
        // free cell from this page on (adds a trailing page if all are full).
        if (index >= cells || page >= base.pages) {
            val pos = firstFreeCellFrom(base, page.coerceAtMost(base.pages))
            val pages = if (pos.page >= base.pages) pos.page + 1 else base.pages
            return resultOf(layout, base.copy(pages = pages, items = base.items + PlacedItem(source, pos)))
        }

        val pageItems = base.items.filter { it.pos.page == page }
        val occupied = pageItems.associateBy { it.pos.y * cols + it.pos.x }

        // Empty target cell → straight place, no shift.
        if (occupied[index] == null) {
            return resultOf(layout, base.copy(items = base.items + PlacedItem(source, cellOf(page, index, cols))))
        }

        // The shift block is [index, gap-1]; `gap` is the first empty cell after it.
        var gap = index + 1
        while (gap < cells && occupied[gap] != null) gap++

        val newPageItems = ArrayList<PlacedItem>(pageItems.size + 1)
        var overflow: PlacedItem? = null
        for (p in pageItems) {
            val li = p.pos.y * cols + p.pos.x
            if (li < index || li >= gap) {
                newPageItems.add(p) // before the insert point, or past the gap — unchanged
            } else {
                val to = li + 1
                if (to < cells) newPageItems.add(p.copy(pos = cellOf(page, to, cols)))
                else overflow = p // last-cell occupant of a dense page spills over
            }
        }
        newPageItems.add(PlacedItem(source, cellOf(page, index, cols)))

        var items = base.items.filterNot { it.pos.page == page } + newPageItems
        var pages = base.pages
        overflow?.let {
            val pos = firstFreeCellFrom(base.copy(items = items), page + 1)
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
                MoveResult.Moved(base.copy(pages = pages, items = base.items + PlacedItem(source, pos)))
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
        // Capacity is per grid columns; a reorder within the dock never grows it
        // (source is excluded), so only an incoming item can hit the cap.
        if (dockWithoutSource.size >= layout.grid.columns) {
            return MoveResult.Rejected(MoveResult.Reason.DOCK_FULL)
        }
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
        val placement = layout.placementOf(folder) ?: return FolderEditResult.NoOp

        emptyTargetReason(layout, target)?.let { return FolderEditResult.Rejected(it) }

        val remaining = folderItem.members.filterNot { it == member }
        return if (remaining.size >= 2) {
            val extracted = HomeItem.App(newId(), member) // RFF-INV-5: 1 id
            val shrunk = layout.replacingItem(folder, folderItem.copy(members = remaining))
            FolderEditResult.Extracted(placeNewAtTarget(shrunk, extracted, target), extracted.id)
        } else {
            // remaining.size == 1 → dissolve. RFF-INV-5: 2 ids, extracted then survivor.
            val extracted = HomeItem.App(newId(), member)
            val survivor = HomeItem.App(newId(), remaining.single())
            val withSurvivor = placeAtPlacement(layout.removing(folder), survivor, placement)
            FolderEditResult.FolderDissolved(
                placeNewAtTarget(withSurvivor, extracted, target),
                extracted.id,
                survivor.id,
            )
        }
    }

    // ===================== place / remove / rename (HOME_EDIT) ===============

    fun place(
        layout: HomeLayout,
        app: ComponentKey,
        target: DropTarget,
        newId: () -> ItemId,
    ): MoveResult {
        // HEU-INV-1: already placed ⇒ move the existing item, never duplicate.
        layout.topLevelIdOf(app)?.let { return move(layout, it, target, newId) }
        if (layout.isFolderMember(app)) return MoveResult.NoOp
        val fresh = HomeItem.App(newId(), app)
        return moveResolved(layout, fresh, fresh.id, target, newId)
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

    private fun HomeLayout.isFolderMember(key: ComponentKey): Boolean =
        items.any { (it.item as? HomeItem.Folder)?.members?.contains(key) == true } ||
            dock.any { (it as? HomeItem.Folder)?.members?.contains(key) == true }

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
        val onGrid = pos.page in 0..layout.pages &&
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
