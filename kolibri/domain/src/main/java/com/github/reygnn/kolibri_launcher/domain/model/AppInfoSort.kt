package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Sorts the receiver alphabetically by display name, case-insensitively.
 *
 * Matches the precomputed [AppInfo.displayNameLower] (locale-invariant, AUDIT-14
 * Nit §208) — the same key `sortedBy { it.displayNameLower }` used inline across
 * the codebase. Kotlin's `sortedBy` is stable, so equal keys keep their input
 * order.
 *
 * This is the single home for the display-name sort that was copy-pasted across
 * ~18 sites (drawer, favorites, hidden/swipe/onboarding VMs, the enumeration and
 * the favorites-order/usage impls, and the fakes). Sibling of [filterByName]
 * ([AppInfoSearch.kt]): keeping one pure, named function means every consumer
 * that owns its display order calls the same greppable thing instead of
 * re-spelling the lambda — the intent ("sort for display") is explicit and the
 * key lives in one place.
 *
 * Pure and total: `displayNameLower` is a non-null property, so this cannot throw.
 */
fun List<AppInfo>.sortedByDisplayName(): List<AppInfo> =
    sortedBy { it.displayNameLower }
