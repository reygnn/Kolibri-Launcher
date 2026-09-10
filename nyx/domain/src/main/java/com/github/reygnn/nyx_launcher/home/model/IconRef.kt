package com.github.reygnn.nyx_launcher.home.model

/**
 * A reference to an icon — never the bitmap itself (IHM-INV-1). Resolving,
 * compositing and caching happen in `:data` (ICON_LOADER_SPEC).
 */
sealed interface IconRef {

    /** Resolved by the system via `LauncherApps.getActivityIcon(density)`. */
    data class System(val key: ComponentKey) : IconRef

    /**
     * v2: mapped from an installed icon pack via its `appfilter.xml`.
     *
     * The case exists now so the sealed hierarchy and the persisted blob do not
     * break when pack support lands; the resolver itself is out of scope
     * (ICON_LOADER_SPEC §11, `IconVariant.PACK`).
     */
    data class Pack(val key: ComponentKey, val packId: String) : IconRef

    // NOTE: folder icons are DERIVED — a composite of the member icons — and are
    // never stored as an IconRef (IHM-INV-2 / ICON_LOADER_SPEC §7).
}
