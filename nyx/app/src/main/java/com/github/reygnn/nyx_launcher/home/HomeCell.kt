package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId

/**
 * One rendering slot: empty, an app (launchable), or a folder (opens a sheet; its
 * icon is the derived 2×2 composite of [Folder.members]).
 */
sealed interface HomeCell {
    data object Empty : HomeCell
    data class App(val id: ItemId, val key: ComponentKey) : HomeCell
    data class Folder(val id: ItemId, val members: List<ComponentKey>) : HomeCell
}

private fun HomeItem.toCell(): HomeCell = when (this) {
    is HomeItem.App -> HomeCell.App(id, key)
    is HomeItem.Folder -> HomeCell.Folder(id, members)
}

/** Dense row-major cells for one page (empties for gaps); index = y*columns + x. */
fun HomeLayout.pageCells(page: Int): List<HomeCell> {
    val cols = grid.columns
    val byIndex = items.filter { it.pos.page == page }.associateBy { it.pos.y * cols + it.pos.x }
    return (0 until cols * grid.rows).map { index -> byIndex[index]?.item?.toCell() ?: HomeCell.Empty }
}

/** Flat cell list for the dock (no empties). */
fun HomeLayout.dockCells(): List<HomeCell> = dock.map { it.toCell() }
