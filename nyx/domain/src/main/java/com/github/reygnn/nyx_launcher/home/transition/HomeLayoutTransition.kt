package com.github.reygnn.nyx_launcher.home.transition

import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
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
    }

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
        val currentDockIndex = layout.dock.indexOfFirst { it.id == moving }
        if (currentDockIndex >= 0 && currentDockIndex == index) return MoveResult.NoOp

        val dockWithoutSource = layout.dock.filterNot { it.id == moving }
        if (dockWithoutSource.size >= layout.grid.columns) {
            return MoveResult.Rejected(MoveResult.Reason.DOCK_FULL) // §7-D3
        }
        return when {
            index < 0 || index > dockWithoutSource.size ->
                MoveResult.Rejected(MoveResult.Reason.OFF_GRID)
            index < dockWithoutSource.size ->
                MoveResult.Rejected(MoveResult.Reason.TARGET_OCCUPIED_INCOMPATIBLE)
            else ->
                MoveResult.Moved(layout.removing(moving).copy(dock = dockWithoutSource + source))
        }
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
            is DropTarget.DockSlot -> when {
                layout.dock.size >= layout.grid.columns -> MoveResult.Reason.DOCK_FULL
                target.index < 0 || target.index > layout.dock.size -> MoveResult.Reason.OFF_GRID
                target.index < layout.dock.size -> MoveResult.Reason.TARGET_OCCUPIED_INCOMPATIBLE
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
            is DropTarget.DockSlot -> layout.copy(dock = layout.dock + item) // validated as append
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
