package com.github.reygnn.nyx_launcher.home.model

/**
 * Mints fresh [DrawerFolderId]s. Injected into the drawer transition wiring so it
 * stays deterministic under test (a stub returns fixed ids); the `:data` impl uses
 * UUIDs. Mirrors [ItemIdFactory] — the domain stays RNG-free (DRAWER_FOLDERS_SPEC §7).
 */
fun interface DrawerFolderIdFactory {
    fun next(): DrawerFolderId
}
