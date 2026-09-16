package com.github.reygnn.nyx_launcher.home.model

import com.github.reygnn.launcher.core.ComponentKey

/**
 * Persisted drawer-folder state (DRAWER_FOLDERS_SPEC §3). The drawer is a derived,
 * self-healing projection over the live system app list; the ONLY thing persisted
 * is folder **membership** — no positions, no ordering of loose apps.
 *
 * A [DrawerFolder] is a slim, INDEPENDENT twin of [HomeItem.Folder] (decision D-5):
 * only the type shape and the icon renderer are shared with home folders, never the
 * persisted state — an app may live in a home folder and a drawer folder at once and
 * independently (DFOLD-INV-6). Membership invariants (≥ 2 resolvable members, unique,
 * at most one drawer folder per app) are enforced by the shared `FolderMembership`
 * rules + the reconcile in `GetDrawerContentUseCase`.
 */
@JvmInline
value class DrawerFolderId(val raw: String)

data class DrawerFolder(
    val id: DrawerFolderId,
    /** Blank ⇒ the localized default name, resolved in the UI (never stored). */
    val title: String,
    /** Ordered, unique; a persisted folder holds ≥ 2 resolvable members (DFOLD-INV-1/-2). */
    val members: List<ComponentKey>,
)

data class DrawerFolders(val folders: List<DrawerFolder>) {
    companion object {
        val EMPTY = DrawerFolders(emptyList())
    }
}
