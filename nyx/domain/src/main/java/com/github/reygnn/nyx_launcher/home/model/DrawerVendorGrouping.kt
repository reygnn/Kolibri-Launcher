package com.github.reygnn.nyx_launcher.home.model

import com.github.reygnn.launcher.core.ComponentKey

/**
 * PURE LOGIC — groups drawer apps by their vendor (package maker), so the drawer-folder
 * UI can offer "add all Google / Samsung / … apps at once" (a bulk-add convenience).
 *
 * The vendor is derived from the package's first two segments (e.g. `com.google.android.gm`
 * → `com.google`) and mapped to a friendly label for common makers; `com.samsung.*` and
 * `com.sec.*` both fold into "Samsung" because the grouping key is the LABEL, not the raw
 * prefix. Unknown vendors fall back to the capitalised second segment (`com.spotify.music`
 * → "Spotify"). Only vendors with ≥ 2 apps are returned — a one-app "vendor" is pointless
 * to bulk-add. This is a heuristic (package names are not a reliable authorship signal),
 * but a convenient, safe one: the worst case is a slightly odd grouping, never data loss.
 */
object DrawerVendorGrouping {

    data class VendorGroup(
        /** Friendly maker name shown to the user (e.g. "Google"). */
        val label: String,
        /** Keys of every app in this group, in the incoming app order. */
        val keys: List<ComponentKey>,
    )

    // Curated labels for the makers common on Android devices. Keyed by the two-segment
    // package prefix; two prefixes mapping to the same label merge into one group.
    private val CURATED = mapOf(
        "com.google" to "Google",
        "com.android" to "Android System",
        "com.samsung" to "Samsung",
        "com.sec" to "Samsung",
        "com.microsoft" to "Microsoft",
        "com.facebook" to "Meta",
        "com.amazon" to "Amazon",
        "org.mozilla" to "Mozilla",
    )

    fun groups(apps: List<LauncherApp>): List<VendorGroup> =
        apps.groupBy { labelFor(it.key.packageName) }
            .filterValues { it.size >= 2 }
            .map { (label, group) -> VendorGroup(label, group.map { it.key }) }
            .sortedBy { it.label.lowercase() }

    private fun labelFor(pkg: String): String {
        val segments = pkg.split('.')
        val prefix = segments.take(2).joinToString(".")
        return CURATED[prefix]
            ?: segments.getOrNull(1)?.replaceFirstChar { it.uppercase() }
            ?: pkg
    }
}
