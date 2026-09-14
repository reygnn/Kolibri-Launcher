package com.github.reygnn.nyx_launcher.home.transition

import com.github.reygnn.launcher.core.ComponentKey
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
 * at 0) → trim trailing empty pages (keep >= 1). Dock capacity is NOT enforced here
 * (the regridder owns it, against the real device grid). Idempotent (RHL-INV-2).
 *
 * Dedup is PER SCOPE (RHL-INV-4, scoped IHM-INV-7). Two independent scopes:
 *  - Top-level (grid ∪ dock): a [ComponentKey] survives once, at its most intentional
 *    position — Dock (slot order) > Grid (page, y, x). An app is at most one top-level
 *    thing (a tile OR a dock icon), never both.
 *  - Each folder on its own: a member is deduped only WITHIN its own folder (first
 *    occurrence kept). A member is NOT dropped for also being a top-level app or a
 *    member of another folder — those are different scopes, so the same key may live as
 *    a tile AND in several folders at once.
 * Duplicates within a scope only arise from import/merge; normal transitions keep
 * per-scope uniqueness.
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

        // ---- Pass 2: dedup PER SCOPE (RHL-INV-4, scoped IHM-INV-7) ----
        val droppedAppIds = HashSet<ItemId>()

        // 2a + 2b: TOP-LEVEL scope (grid ∪ dock share one set). Dock slots reserve keys
        // first (Dock > Grid), then grid top-level apps in (page, y, x) order.
        val seenTopLevel = HashSet<ComponentKey>()
        for (item in dockP) if (item is HomeItem.App && !seenTopLevel.add(item.key)) {
            droppedAppIds.add(item.id); dedupedApps++
        }
        val gridByPos = itemsP.sortedWith(compareBy({ it.pos.page }, { it.pos.y }, { it.pos.x }))
        for (placed in gridByPos) {
            val home = placed.item
            if (home is HomeItem.App && !seenTopLevel.add(home.key)) {
                droppedAppIds.add(home.id); dedupedApps++
            }
        }
        // 2c: folder members — each folder is its OWN scope. Dedup only WITHIN a folder's
        // own member list (fresh set per folder); a member is NOT compared against the
        // top-level set or against other folders. So a key may be a tile AND a member of
        // several folders at once (independent scopes).
        fun dedupWithinFolder(members: List<ComponentKey>): List<ComponentKey> {
            val seenHere = HashSet<ComponentKey>(members.size)
            val kept = ArrayList<ComponentKey>(members.size)
            for (key in members) if (seenHere.add(key)) kept.add(key) else dedupedApps++
            return kept
        }
        val dedupedMembersById = HashMap<ItemId, List<ComponentKey>>()
        for (item in dockP) if (item is HomeItem.Folder) {
            dedupedMembersById[item.id] = dedupWithinFolder(item.members)
        }
        for (placed in gridByPos) {
            val home = placed.item
            if (home is HomeItem.Folder) dedupedMembersById[home.id] = dedupWithinFolder(home.members)
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

        // Dock capacity is intentionally NOT enforced here: it is keyed to the grid
        // columns, and reconcile can run against a stale grid (before FitHomeGridUseCase
        // stamps the measured device grid). The regridder owns dock capacity against
        // the REAL grid and re-homes overflow onto the grid rather than dropping it.

        // ---- Pass 5: trim trailing empty pages (keep >= 1); interior kept ----
        val usedPages = itemsR.maxOfOrNull { it.pos.page + 1 } ?: 0
        val newPages = minOf(layout.pages, maxOf(1, usedPages))
        val trimmedPages = layout.pages - newPages

        val changed = prunedApps > 0 || dedupedApps > 0 || dissolvedFolders > 0 ||
            removedEmptyFolders > 0 || trimmedPages > 0
        if (!changed) return ReconcileOutcome.Unchanged

        return ReconcileOutcome.Changed(
            layout = layout.copy(pages = newPages, items = itemsR, dock = dockR),
            report = ReconcileReport(
                prunedApps = prunedApps,
                dedupedApps = dedupedApps,
                dissolvedFolders = dissolvedFolders,
                removedEmptyFolders = removedEmptyFolders,
                trimmedPages = trimmedPages,
                dockTrimmed = 0,
            ),
        )
    }
}
