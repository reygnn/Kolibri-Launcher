package com.github.reygnn.kolibri_launcher.core

/**
 * The explicit registry of DataStore keys that were REMOVED from the **settings** store over the
 * app's life and now linger as inert orphans on installs that predate the removal (Kolibri ships
 * no in-code migrations — Rule 5 — so retired keys are never cleaned automatically).
 *
 * This is a **whitelist-of-dead**, deliberately NOT a blacklist-of-unknown: the storage-cleanup
 * feature (Settings) deletes ONLY keys matching an entry here. A LIVE key is therefore never at
 * risk — it would have to be explicitly listed as retired first, which one only does when removing
 * the feature that owned it. Adding an entry is a small, reviewed act; forgetting to add one just
 * leaves a harmless orphan (the worst case is under-cleaning, never over-deleting).
 *
 * **Discipline:** when you remove a key (or move it to another store), add it here. Each entry is
 * verified — the key/prefix is written by NO current settings-store repo. The set is pinned by
 * [com.github.reygnn.kolibri_launcher.core.RetiredDataStoreKeysTest] so any change is deliberate.
 *
 * Scope is the **settings** store only. The consent store is privacy-sensitive and left untouched;
 * the usage store holds live data (nothing retired there yet).
 */
object RetiredDataStoreKeys {

    /**
     * Exact settings-store key names that are retired. Verified absent from all current
     * settings-store repos.
     * - `wallpaper_flattened_path` — the on-disk composite pointer, removed in the v4 in-memory
     *   composite rework (WALLPAPER_COMPOSITE_LIFECYCLE_SPEC). Grep-verified: no reader/writer left.
     */
    val settingsExactKeys: Set<String> = setOf(
        "wallpaper_flattened_path",
    )

    /**
     * Settings-store key-name PREFIXES that are retired (dynamic per-entity keys).
     * - `usage_` — per-app usage timestamps, MOVED to the separate usage DataStore in the AUDIT-19
     *   F1 split. Both `AppUsageRepositoryImpl` and `UsageExportRepositoryImpl` inject
     *   `@UsageDataStore`, so no settings-store repo uses this prefix; the settings copies are
     *   pre-split orphans.
     */
    val settingsPrefixes: Set<String> = setOf(
        AppConstants.KEY_USAGE_PREFIX,
    )

    /** True if [keyName] is a retired settings-store key (exact name or a retired prefix). */
    fun isRetiredSettingsKey(keyName: String): Boolean =
        keyName in settingsExactKeys || settingsPrefixes.any { keyName.startsWith(it) }
}
