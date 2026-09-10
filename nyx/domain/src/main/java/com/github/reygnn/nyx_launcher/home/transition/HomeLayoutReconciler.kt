package com.github.reygnn.nyx_launcher.home.transition

import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.github.reygnn.nyx_launcher.home.model.ReconcileOutcome
import com.github.reygnn.nyx_launcher.home.model.ReconcileReport

/**
 * Pure reconcile policy (RECONCILE_HOME_LAYOUT_SPEC §2). Android-free, total,
 * deterministic (ids via factory) — a JVM truth table. The fail-closed gate lives
 * in the use-case, so this only ever sees a genuine [installed] set.
 *
 * Passes: prune dead → dedup by precedence → repair folders (dissolve at 1 / drop
 * at 0) → trim the dock to `columns` → trim trailing empty pages (keep >= 1).
 * Idempotent (RHL-INV-2).
 *
 * Dedup precedence (RHL-INV-4): each [ComponentKey] survives at its most
 * intentional position — Dock (slot order) > Grid top-level (page, y, x) > Folder
 * member (folder position, then member index). Duplicates only arise from
 * import/merge; normal transitions keep app-uniqueness.
 */
object HomeLayoutReconciler {

    fun reconcile(
        layout: HomeLayout,
        installed: Set<ComponentKey>,
        newId: () -> ItemId,
    ): ReconcileOutcome {
        var prunedApps = 0
        var dedupedApps = 0
        var dissolvedFolders = 0
        var removedEmptyFolders = 0
        val columns = layout.grid.columns

        // ---- Pass 1: prune dead references (members filtered; folders kept) ----
        fun pruneMembers(members: List<ComponentKey>): List<ComponentKey> {
            val kept = members.filter { it in installed }
            prunedApps += members.size - kept.size
            return kept
        }
        val dockP: List<HomeItem> = layout.dock.mapNotNull { item ->
            when (item) {
                is HomeItem.App -> if (item.key in installed) item else { prunedApps++; null }
                is HomeItem.Folder -> item.copy(members = pruneMembers(item.members))
            }
        }
        val itemsP: List<PlacedItem> = layout.items.mapNotNull { placed ->
            when (val home = placed.item) {
                is HomeItem.App -> if (home.key in installed) placed else { prunedApps++; null }
                is HomeItem.Folder -> placed.copy(item = home.copy(members = pruneMembers(home.members)))
            }
        }

        // ---- Pass 2: dedup by precedence (RHL-INV-4) ----
        val seen = HashSet<ComponentKey>()
        val droppedAppIds = HashSet<ItemId>()

        // 2a: dock top-level apps (slot order) reserve keys first.
        for (item in dockP) if (item is HomeItem.App && !seen.add(item.key)) {
            droppedAppIds.add(item.id); dedupedApps++
        }
        // 2b: grid top-level apps (page, y, x order).
        val gridByPos = itemsP.sortedWith(compareBy({ it.pos.page }, { it.pos.y }, { it.pos.x }))
        for (placed in gridByPos) {
            val home = placed.item
            if (home is HomeItem.App && !seen.add(home.key)) {
                droppedAppIds.add(home.id); dedupedApps++
            }
        }
        // 2c: folder members — dock folders (slot), then grid folders (pos).
        fun dedupMembers(members: List<ComponentKey>): List<ComponentKey> {
            val kept = ArrayList<ComponentKey>(members.size)
            for (key in members) if (seen.add(key)) kept.add(key) else dedupedApps++
            return kept
        }
        val dedupedMembersById = HashMap<ItemId, List<ComponentKey>>()
        for (item in dockP) if (item is HomeItem.Folder) {
            dedupedMembersById[item.id] = dedupMembers(item.members)
        }
        for (placed in gridByPos) {
            val home = placed.item
            if (home is HomeItem.Folder) dedupedMembersById[home.id] = dedupMembers(home.members)
        }

        fun applyDedup(item: HomeItem): HomeItem? = when (item) {
            is HomeItem.App -> if (item.id in droppedAppIds) null else item
            is HomeItem.Folder -> item.copy(members = dedupedMembersById[item.id] ?: item.members)
        }
        val dockD = dockP.mapNotNull { applyDedup(it) }
        val itemsD = itemsP.mapNotNull { placed -> applyDedup(placed.item)?.let { placed.copy(item = it) } }

        // ---- Pass 3: repair folders (dissolve at 1, drop at 0) ----
        fun repair(item: HomeItem): HomeItem? = when (item) {
            is HomeItem.App -> item
            is HomeItem.Folder -> when (item.members.size) {
                0 -> { removedEmptyFolders++; null }
                1 -> { dissolvedFolders++; HomeItem.App(newId(), item.members.single()) }
                else -> item
            }
        }
        val dockR = dockD.mapNotNull { repair(it) }
        val itemsR = itemsD.mapNotNull { placed -> repair(placed.item)?.let { placed.copy(item = it) } }

        // ---- Pass 4: dock capacity = columns ----
        var dockTrimmed = 0
        val dockFinal = if (dockR.size > columns) {
            dockTrimmed = dockR.size - columns
            dockR.take(columns)
        } else {
            dockR
        }

        // ---- Pass 5: trim trailing empty pages (keep >= 1); interior kept ----
        val usedPages = itemsR.maxOfOrNull { it.pos.page + 1 } ?: 0
        val newPages = minOf(layout.pages, maxOf(1, usedPages))
        val trimmedPages = layout.pages - newPages

        val changed = prunedApps > 0 || dedupedApps > 0 || dissolvedFolders > 0 ||
            removedEmptyFolders > 0 || dockTrimmed > 0 || trimmedPages > 0
        if (!changed) return ReconcileOutcome.Unchanged

        return ReconcileOutcome.Changed(
            layout = layout.copy(pages = newPages, items = itemsR, dock = dockFinal),
            report = ReconcileReport(
                prunedApps = prunedApps,
                dedupedApps = dedupedApps,
                dissolvedFolders = dissolvedFolders,
                removedEmptyFolders = removedEmptyFolders,
                trimmedPages = trimmedPages,
                dockTrimmed = dockTrimmed,
            ),
        )
    }
}
