package com.github.reygnn.nyx_launcher.home.model

/**
 * The first empty cell scanning pages in order, then row-major within a page. If
 * every existing page is full, returns the first cell of a new trailing page
 * (`CellPos(pages, 0, 0)`), which the transitions accept as an append. Pure.
 */
fun HomeLayout.firstFreeCell(): CellPos {
    val cols = grid.columns
    val rows = grid.rows
    for (page in 0 until pages) {
        val occupied = items
            .filter { it.pos.page == page }
            .mapTo(HashSet()) { it.pos.y * cols + it.pos.x }
        for (index in 0 until cols * rows) {
            if (index !in occupied) return CellPos(page, index % cols, index / cols)
        }
    }
    return CellPos(pages, 0, 0)
}
