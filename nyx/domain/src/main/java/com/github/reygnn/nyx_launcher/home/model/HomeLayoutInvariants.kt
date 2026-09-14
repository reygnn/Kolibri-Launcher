package com.github.reygnn.nyx_launcher.home.model

import com.github.reygnn.launcher.core.ComponentKey

/**
 * Runtime check of the structural home-layout invariants the transitions must
 * preserve *by construction* (ICON_HOME_MODEL_SPEC):
 *  - IHM-INV-3: page count in range, every cell on-grid, no two cells overlap.
 *  - IHM-INV-4: [ItemId] unique across `items` u `dock`.
 *  - IHM-INV-7 (SCOPED): a [ComponentKey] appears at most once in the top-level
 *    scope (`items` u `dock` combined) and at most once WITHIN each folder; the
 *    scopes are independent, so the same key may be a top-level tile AND a member
 *    of one or more folders -- that is legal and NOT flagged.
 *  - RFF-INV-1: a folder never persists with < 2 members.
 *
 * Turns "correct by construction" into "verified at runtime": call
 * [assertInvariants] from a DEBUG build after every transition (behind
 * BuildConfig.DEBUG) and from the property test after every generated edit.
 * [invariantViolations] returns EVERY breach; empty == well-formed.
 */
fun HomeLayout.invariantViolations(dockCapacity: Int = grid.columns): List<String> {
    val v = ArrayList<String>()
    val cols = grid.columns
    val rows = grid.rows

    if (pages !in 1..HomeLayout.MAX_PAGES) v += "pages=$pages outside 1..${HomeLayout.MAX_PAGES}"
    val occupied = HashSet<Triple<Int, Int, Int>>()
    for (p in items) {
        val (page, x, y) = p.pos
        if (page !in 0 until pages) v += "item '${p.item.id.raw}' on page $page but pages=$pages"
        if (x < 0 || y < 0 || x + p.span.w > cols || y + p.span.h > rows) {
            v += "item '${p.item.id.raw}' off-grid at ($page,$x,$y) span ${p.span.w}x${p.span.h} in ${cols}x$rows"
        }
        for (dx in 0 until p.span.w) for (dy in 0 until p.span.h) {
            if (!occupied.add(Triple(page, x + dx, y + dy))) {
                v += "cell collision at ($page,${x + dx},${y + dy}) (item '${p.item.id.raw}')"
            }
        }
    }

    val idCounts = HashMap<String, Int>()
    for (p in items) idCounts.merge(p.item.id.raw, 1, Int::plus)
    for (d in dock) idCounts.merge(d.id.raw, 1, Int::plus)
    for ((id, n) in idCounts) if (n > 1) v += "duplicate ItemId '$id' ($n occurrences)"

    if (dock.size > dockCapacity) v += "dock size ${dock.size} exceeds capacity $dockCapacity"

    val topLevelKeys = HashSet<ComponentKey>()
    for (p in items) (p.item as? HomeItem.App)?.let {
        if (!topLevelKeys.add(it.key)) v += "top-level key '${it.key.flat}' appears twice (grid item '${p.item.id.raw}')"
    }
    for (d in dock) (d as? HomeItem.App)?.let {
        if (!topLevelKeys.add(it.key)) v += "top-level key '${it.key.flat}' appears twice (dock item '${d.id.raw}')"
    }

    fun checkFolder(f: HomeItem.Folder, where: String) {
        if (f.members.size < 2) v += "folder '${f.id.raw}' ($where) has ${f.members.size} member(s), fewer than 2 (RFF-INV-1)"
        val seenHere = HashSet<ComponentKey>(f.members.size)
        for (m in f.members) if (!seenHere.add(m)) v += "duplicate member '${m.flat}' within folder '${f.id.raw}' ($where)"
    }
    for (p in items) (p.item as? HomeItem.Folder)?.let { checkFolder(it, "grid") }
    for (d in dock) (d as? HomeItem.Folder)?.let { checkFolder(it, "dock") }

    return v
}

/** Throws listing every violated invariant, or returns cleanly when well-formed. */
fun HomeLayout.assertInvariants(dockCapacity: Int = grid.columns) {
    val violations = invariantViolations(dockCapacity)
    check(violations.isEmpty()) {
        "HomeLayout invariant violation(s):\n  - " + violations.joinToString("\n  - ")
    }
}
