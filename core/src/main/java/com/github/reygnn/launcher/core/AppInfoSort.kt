package com.github.reygnn.launcher.core

/**
 * Sorts the receiver alphabetically by display name, case-insensitively.
 *
 * Matches the precomputed [AppInfo.displayNameLower] (locale-invariant, AUDIT-14
 * Nit §208). Kotlin's `sortedBy` is stable, so equal keys keep input order.
 *
 * Moved here (neutral `:core`) as part of the shared installed-apps cut so both
 * apps' consumers call the same greppable function. Note: the shared holder holds
 * the list **raw** (SIA-INV-3); display sort is the *consumer's* job — each app's
 * `Get*AppsUseCase` calls this over `rawAppsFlow` (matching Nyx's
 * APPLIST_SORT_SPLIT posture). Pure and total: `displayNameLower` is non-null.
 */
fun List<AppInfo>.sortedByDisplayName(): List<AppInfo> =
    sortedBy { it.displayNameLower }
