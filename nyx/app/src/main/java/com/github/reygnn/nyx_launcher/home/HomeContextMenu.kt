package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.ItemId

/**
 * A long-press context-menu entry as a pure decision, decoupled from its UI row.
 * Each variant carries exactly the data its action needs; MainActivity maps it to
 * a label ([android.R.string]) plus the ViewModel/Activity call in
 * `contextMenuItemFor`. Mirrors Kolibri's `AppContextMenuAction` pattern (a
 * sealed action decided in pure code, rendered in the UI layer), kept in `:app`
 * here because [DragPayload] is an app-level drag concept.
 */
internal sealed interface HomeContextMenuAction {
    data class AppInfo(val packageName: String) : HomeContextMenuAction
    data class RemoveFromHome(val id: ItemId) : HomeContextMenuAction
    // [id] is the home placement to remove once a from-tile uninstall completes (null for a
    // drawer app, which has no placement).
    data class Uninstall(val packageName: String, val id: ItemId? = null) : HomeContextMenuAction
    data class AddToHome(val key: ComponentKey) : HomeContextMenuAction
    data class HideApp(val key: ComponentKey) : HomeContextMenuAction
    data class UnhideApp(val key: ComponentKey) : HomeContextMenuAction
}

/**
 * Decides which context-menu actions a long-press offers, in display order.
 * Pure and total, so it is JVM-testable as a truth table; the view glue
 * (resolving [packageName] from the layout, the system `isSystemApp` /
 * hidden-set reads, building rows, the async shortcut prepend) stays in
 * MainActivity.showContextMenu.
 *
 * - [packageName]: the app package the payload points at, or null when it cannot
 *   be resolved (an existing home item that is a folder, or a stale id).
 * - [isHidden]: whether the app is currently in the hidden set (only consulted
 *   for a drawer app, where hide/unhide is offered).
 * - [isSystemApp]: gates the uninstall entry (system apps cannot be uninstalled).
 * - [isInstalled]: whether the app is currently installed. A MISSING tile (a kept
 *   reference to an uninstalled app, Windows-shortcut model) drops both App info and
 *   Uninstall — there is nothing to open or uninstall — leaving only Remove from home.
 *
 * Order matches the shipped UI:
 * - Existing home item: App info (if resolvable + installed), Remove from home,
 *   Uninstall (if installed + non-system).
 * - New app from the drawer: Add to home, Hide/Unhide, App info (if installed),
 *   Uninstall (if installed + non-system).
 * - Folder member: none (folder members extract by drag only).
 */
internal fun buildHomeContextMenuActions(
    payload: DragPayload,
    packageName: String?,
    isHidden: Boolean,
    isSystemApp: Boolean,
    isInstalled: Boolean,
): List<HomeContextMenuAction> = when (payload) {
    is DragPayload.Existing -> buildList {
        if (packageName != null && isInstalled) add(HomeContextMenuAction.AppInfo(packageName))
        add(HomeContextMenuAction.RemoveFromHome(payload.id))
        // Carry the placement id: a from-tile uninstall removes the tile once the app is gone.
        if (packageName != null && isInstalled && !isSystemApp) add(HomeContextMenuAction.Uninstall(packageName, payload.id))
    }
    is DragPayload.NewApp -> buildList {
        add(HomeContextMenuAction.AddToHome(payload.key))
        add(
            if (isHidden) HomeContextMenuAction.UnhideApp(payload.key)
            else HomeContextMenuAction.HideApp(payload.key),
        )
        if (packageName != null && isInstalled) add(HomeContextMenuAction.AppInfo(packageName))
        if (packageName != null && isInstalled && !isSystemApp) add(HomeContextMenuAction.Uninstall(packageName))
    }
    is DragPayload.FolderMember -> emptyList()
}
