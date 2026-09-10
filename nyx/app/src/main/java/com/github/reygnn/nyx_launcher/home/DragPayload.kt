package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.ItemId

/**
 * What rides along a local drag as `localState`: either an existing home item
 * (→ move) or a fresh app dragged out of the drawer panel (→ place).
 */
sealed interface DragPayload {
    data class Existing(val id: ItemId) : DragPayload
    data class NewApp(val key: ComponentKey) : DragPayload
}
