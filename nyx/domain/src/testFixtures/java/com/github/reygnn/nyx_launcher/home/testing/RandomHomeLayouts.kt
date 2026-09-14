package com.github.reygnn.nyx_launcher.home.testing

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import kotlin.random.Random

/**
 * Shared generator for random, invariant-VALID [HomeLayout]s. Extracted from the
 * transition property test so other modules (e.g. `:data`'s DTO mapper
 * round-trip) can drive the same realistic layout shapes: varied grid dimensions
 * (so dock capacity — = columns — ranges from 2 upward), app pools, folders with
 * members, dock apps and dock folders across multiple pages.
 *
 * Model-only on purpose — depends solely on `home.model`, never on the transition
 * engine, so it stays usable from any test source set that pulls these fixtures.
 */
object RandomHomeLayouts {

    /**
     * A per-run randomized problem shape: grid dimensions + the installed app pool.
     * [grid].columns is the dock capacity, so drawing it from 2..6 (instead of a
     * fixed 4) is what exercises the small-dock and dock-full/overflow paths.
     */
    data class Scenario(val grid: GridSpec, val pool: List<ComponentKey>)

    fun scenario(rnd: Random): Scenario {
        val cols = 2 + rnd.nextInt(5)          // 2..6  → dock capacity 2..6
        val rows = 3 + rnd.nextInt(4)          // 3..6
        val poolSize = 4 + rnd.nextInt(13)     // 4..16 distinct apps
        val pool = (0 until poolSize).map { ComponentKey("com.app$it", "Main") }
        return Scenario(GridSpec(cols, rows), pool)
    }

    /** A random, invariant-VALID starting layout for [sc]'s grid and app pool. */
    fun seed(rnd: Random, sc: Scenario): HomeLayout {
        val cols = sc.grid.columns; val rows = sc.grid.rows
        val pages = 1 + rnd.nextInt(3)         // 1..3 (well within MAX_PAGES)
        val pool = sc.pool
        val items = ArrayList<PlacedItem>()
        val dock = ArrayList<HomeItem>()
        var n = 0
        val usedTop = HashSet<ComponentKey>()
        val free = ArrayList<CellPos>()
        for (p in 0 until pages) for (y in 0 until rows) for (x in 0 until cols) free += CellPos(p, x, y)
        free.shuffle(rnd)
        repeat(rnd.nextInt(3)) {
            if (free.isEmpty()) return@repeat
            val m = (0 until (2 + rnd.nextInt(2))).map { pool[rnd.nextInt(pool.size)] }.distinct()
            if (m.size >= 2) items += PlacedItem(HomeItem.Folder(ItemId("s${n++}"), "", m), free.removeAt(0))
        }
        repeat(rnd.nextInt(5)) {
            if (free.isEmpty()) return@repeat
            val k = pool[rnd.nextInt(pool.size)]; if (!usedTop.add(k)) return@repeat
            items += PlacedItem(HomeItem.App(ItemId("s${n++}"), k), free.removeAt(0))
        }
        // Dock is seeded up to `cols` entries → never above capacity (= cols).
        repeat(rnd.nextInt(cols + 1)) {
            if (rnd.nextInt(4) == 0) {
                val m = (0 until 2).map { pool[rnd.nextInt(pool.size)] }.distinct()
                if (m.size == 2) dock += HomeItem.Folder(ItemId("s${n++}"), "", m)
            } else {
                val k = pool[rnd.nextInt(pool.size)]; if (usedTop.add(k)) dock += HomeItem.App(ItemId("s${n++}"), k)
            }
        }
        return HomeLayout(GridSpec(cols, rows), pages, items, dock)
    }
}
