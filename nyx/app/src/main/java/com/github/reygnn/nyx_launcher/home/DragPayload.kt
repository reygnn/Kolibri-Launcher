package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.ItemId

/**
 * What rides along a local drag as `localState`: an existing home item (→ move),
 * a fresh app dragged out of the drawer panel (→ place), or a member dragged out
 * of an open folder (→ extract to the drop target).
 */
sealed interface DragPayload {
    data class Existing(val id: ItemId) : DragPayload
    data class NewApp(val key: ComponentKey) : DragPayload
    data class FolderMember(val folderId: ItemId, val key: ComponentKey) : DragPayload
}
