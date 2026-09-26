package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure JVM truth-table for [buildHomeContextMenuActions]. */
class HomeContextMenuTest {

    private val key = ComponentKey("com.example.alpha", "com.example.alpha.Main")
    private val id = ItemId("item-1")

    private fun actions(
        payload: DragPayload,
        packageName: String? = key.packageName,
        isHidden: Boolean = false,
        isSystemApp: Boolean = false,
        isInstalled: Boolean = true,
    ) = buildHomeContextMenuActions(payload, packageName, isHidden, isSystemApp, isInstalled)

    // ---- Existing home item ----

    @Test fun existing_app_offers_info_remove_uninstall_in_order() {
        assertThat(actions(DragPayload.Existing(id))).containsExactly(
            HomeContextMenuAction.AppInfo(key.packageName),
            HomeContextMenuAction.RemoveFromHome(id),
            HomeContextMenuAction.Uninstall(key.packageName, id),
        ).inOrder()
    }

    @Test fun existing_system_app_drops_uninstall() {
        assertThat(actions(DragPayload.Existing(id), isSystemApp = true)).containsExactly(
            HomeContextMenuAction.AppInfo(key.packageName),
            HomeContextMenuAction.RemoveFromHome(id),
        ).inOrder()
    }

    @Test fun existing_folder_offers_only_remove() {
        // A folder (or a stale id) resolves to no package → info/uninstall drop out.
        assertThat(actions(DragPayload.Existing(id), packageName = null)).containsExactly(
            HomeContextMenuAction.RemoveFromHome(id),
        )
    }

    @Test fun existing_missing_app_offers_only_remove() {
        // A kept reference to an uninstalled app (missing tile): App info + Uninstall are
        // dead actions (nothing to open/uninstall), so only Remove from home remains.
        assertThat(actions(DragPayload.Existing(id), isInstalled = false)).containsExactly(
            HomeContextMenuAction.RemoveFromHome(id),
        )
    }

    @Test fun existing_app_ignores_hidden_state() {
        // hide/unhide is a drawer-only action; an existing home item never offers it,
        // even when the app happens to be in the hidden set. Pins that isHidden is
        // deliberately not consulted for Existing.
        assertThat(actions(DragPayload.Existing(id), isHidden = true)).containsExactly(
            HomeContextMenuAction.AppInfo(key.packageName),
            HomeContextMenuAction.RemoveFromHome(id),
            HomeContextMenuAction.Uninstall(key.packageName, id),
        ).inOrder()
    }

    // ---- New app from the drawer ----

    @Test fun new_app_offers_add_hide_info_uninstall_in_order() {
        assertThat(actions(DragPayload.NewApp(key))).containsExactly(
            HomeContextMenuAction.AddToHome(key),
            HomeContextMenuAction.HideApp(key),
            HomeContextMenuAction.AppInfo(key.packageName),
            HomeContextMenuAction.Uninstall(key.packageName),
        ).inOrder()
    }

    @Test fun new_app_that_is_hidden_offers_unhide_instead_of_hide() {
        assertThat(actions(DragPayload.NewApp(key), isHidden = true)).contains(
            HomeContextMenuAction.UnhideApp(key),
        )
        assertThat(actions(DragPayload.NewApp(key), isHidden = true))
            .doesNotContain(HomeContextMenuAction.HideApp(key))
    }

    @Test fun new_system_app_drops_uninstall() {
        assertThat(actions(DragPayload.NewApp(key), isSystemApp = true)).containsExactly(
            HomeContextMenuAction.AddToHome(key),
            HomeContextMenuAction.HideApp(key),
            HomeContextMenuAction.AppInfo(key.packageName),
        ).inOrder()
    }

    // ---- Folder member ----

    @Test fun folder_member_offers_nothing() {
        assertThat(actions(DragPayload.FolderMember(id, key))).isEmpty()
    }
}
