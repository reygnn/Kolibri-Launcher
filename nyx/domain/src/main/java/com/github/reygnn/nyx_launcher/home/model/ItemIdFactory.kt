package com.github.reygnn.nyx_launcher.home.model

/**
 * Mints fresh [ItemId]s. Injected into the pure transitions so they stay
 * deterministic under test (a stub returns fixed ids); the `:data` impl uses
 * UUIDs. See MOVE_ITEM_SPEC §4.
 */
fun interface ItemIdFactory {
    fun next(): ItemId
}
