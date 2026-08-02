package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot

/**
 * Ein Interface, das den Vertrag für das Speichern und Abrufen von
 * App-Zuweisungen für Wischgesten (Swipe Actions) definiert.
 */
interface SwipeActionsRepository : Purgeable {

    /**
     * Speichert die Zuweisung für einen bestimmten [SwipeSlot].
     *
     * @param slot Der Slot, der aktualisiert wird (muss LEFT oder RIGHT sein).
     * @param componentName Der ComponentName der App (z.B. "com.app/com.app.MainActivity")
     * oder `null`, um die Zuweisung für diesen Slot zu löschen.
     */
    suspend fun setSwipeAction(slot: SwipeSlot, componentName: String?)

    /**
     * Reconciles the swipe slots against the loaded app list, gating each
     * removal through [isStillPresent] — analogous to
     * [FavoritesRepository.reconcileFavoriteComponents] (RECONCILE_FIX_SPEC
     * R-INV-2). A slot whose component is absent from [installedComponentNames]
     * is only a candidate; it is cleared only if [isStillPresent] returns false.
     *
     * Slot-keyed store: the delete re-reads each slot INSIDE `edit{}` and clears
     * it only if it STILL holds a verified-absent component (value-guard, §2/§5)
     * — never a blind `remove(slot)`, which would clobber a concurrent
     * reassignment. Fail-closed read; empty-installed guard lives at the caller.
     */
    suspend fun reconcileSwipeActions(
        installedComponentNames: List<String>,
        isStillPresent: suspend (String) -> Boolean,
    )

    /**
     * Reads the CURRENT component assigned to [slot] straight from the store.
     * The launch path needs the authoritative value: a slot changed in the
     * Settings activity must take effect on the very next swipe, so the read
     * never goes through a cache. Returns `null` for an unassigned slot, for
     * [SwipeSlot.NONE], or on a transient read failure (non-destructive: no
     * launch rather than a wrong app).
     */
    suspend fun getSwipeActionComponent(slot: SwipeSlot): String?
}