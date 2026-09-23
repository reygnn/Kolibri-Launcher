package com.github.reygnn.launcher.core

/**
 * Pure-Kotlin immutable data class for a launcher entry — the neutral, shared
 * app model (MONOREPO_MERGE_SPEC §7, MRG-INV-9 / SIA-INV-3).
 *
 * Holds the minimum information about an installed app and has no Android-framework
 * dependencies — neither Context/Drawable nor Parcelable. It is deliberately
 * **icon-less** (icons are resolved per app: Nyx `IconRef`/`:data`, Kolibri its
 * own path — SHARED_INSTALLED_APPS_SPEC §8) and carries **no `isFavorite`**:
 * favorite / hidden / custom-name / sort are per-app overlays (Klasse B), never
 * stored in the shared model (SIA-INV-3, MRG-INV-9). Kolibri's former
 * `AppInfo.isFavorite` field is dropped by the Phase-1 canonicalization that
 * precedes this migration; favorite membership now lives only in
 * `GetFavoriteAppsUseCase` / `FavoritesRepository`.
 *
 * This is the carry-over of Kolibri's battle-tested precomputed model into the
 * neutral `com.github.reygnn.launcher.core` namespace (MRG-INV-6). Nyx's former
 * `LauncherApp` becomes a projection of this type (`label = originalName`,
 * `customName` applied as an overlay at the consumer).
 */
data class AppInfo(
    val originalName: String,
    val displayName: String,
    val packageName: String,
    val className: String,
) {
    /**
     * Precomputed lowercase sort key for [displayName].
     *
     * Computed once per instance (and correctly recomputed on
     * `copy(displayName = …)`, since `copy` runs through the constructor), so name
     * sorts do not reallocate a `lowercase()` per comparison — the O(N·log N)
     * allocations in the comparator collapse to O(N) at instance creation
     * (AUDIT-14 Nit §208).
     *
     * Deliberately a body `val` and **not** a constructor parameter, so it stays
     * out of `equals`/`hashCode`/`toString`/`componentN`; the equals-based Flow
     * `distinctUntilChanged` therefore behaves unchanged. `lowercase()` is
     * locale-invariant, so the ordering is identical to the former comparator calls.
     */
    val displayNameLower: String = displayName.lowercase()

    /**
     * The [className] in its long form: a leading-dot relative spelling
     * (`.Activity`) is expanded to `package.Activity`, a fully-qualified name is
     * left untouched. Android accepts both spellings, but an explicit
     * `ComponentName` used to launch must carry the fully-qualified class — the
     * system resolves the activity by exact class-name match against the parsed
     * manifest (which stores long-form names), so a relative spelling would fail
     * to resolve. This is the single source of truth for that normalization,
     * shared by [key] (identity) and by each app's launcher.
     *
     * A body `val` (declared before [key]), which — like [displayNameLower] and
     * [componentName] — keeps it out of `equals`/`hashCode`/`copy`/`componentN`.
     */
    val normalizedClassName: String =
        if (className.startsWith(".")) "$packageName$className" else className

    /**
     * The canonical structured identity of this entry.
     *
     * Carries the normalized (long-form) class name, so [key] and [componentName]
     * never disagree; [ComponentKey.flat] is the single definition of the
     * flattened wire format and [componentName] is merely its projection.
     */
    val key: ComponentKey = ComponentKey(packageName, normalizedClassName)

    /**
     * Flattened `"pkg/cls"` projection of [key]. Precomputed body `val` (out of
     * equals/hashCode/copy like the others) — read on essentially every entry
     * (hidden-filter, favorites membership, DiffUtil identity). Byte-for-byte the
     * historical value.
     */
    val componentName: String = key.flat
}
