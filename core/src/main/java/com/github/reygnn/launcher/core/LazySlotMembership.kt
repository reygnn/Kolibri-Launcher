package com.github.reygnn.launcher.core

/**
 * The ONE lazy-slot membership rule, shared by every "keep the reference, mark it
 * missing" surface — nyx home tiles ([com.github.reygnn.nyx_launcher.home] HomeCell),
 * kolibri favorites (GetFavoriteAppsUseCase) and kolibri swipe slots
 * (HandleSwipeActionUseCase) — the no-auto-prune / Windows-shortcut model
 * (root TODO.md).
 *
 * It lives here, generic over the key type, so those surfaces cannot DRIFT on the one
 * subtle, load-bearing clause: an EMPTY installed view means "not loaded yet" (the
 * cold-start window before the first enumeration, or a transient load failure), NOT
 * "everything is gone". An absent reference is therefore only "missing" when the
 * installed view is NON-EMPTY. Dropping that guard is the exact drift that greys every
 * tile during cold start and — on any mutating path — risks discarding a still-installed
 * reference (the class of bug the whole lazy rebuild was meant to end).
 *
 * This replaces the deleted cross-launcher DeletionGate parity tests: those pinned that
 * both launchers applied the SAME delete gate; there is no delete gate any more, but this
 * keep-vs-mark-missing decision is the new thing both launchers share, so it is unified
 * here (parity by construction — one implementation, not two mirrored ones) instead of
 * being re-derived at each call site.
 *
 * [T] is whatever key each surface uses — [ComponentKey] in nyx, the flat `"pkg/cls"`
 * string in kolibri. Pure and total; unit-pinned in LazySlotMembershipTest.
 */
object LazySlotMembership {

    /**
     * Whether [key] should render/behave as "missing" given the current [installed]
     * view: true iff [installed] is NON-EMPTY and does not contain [key]. An empty
     * [installed] set is treated as "not loaded" and flags nothing.
     */
    fun <T> isMissing(key: T, installed: Set<T>): Boolean =
        installed.isNotEmpty() && key !in installed
}
