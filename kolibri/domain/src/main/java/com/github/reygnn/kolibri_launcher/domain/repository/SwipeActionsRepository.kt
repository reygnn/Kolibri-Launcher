package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.launcher.core.Purgeable

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
     * Reads the CURRENT component assigned to [slot] straight from the store.
     * The launch path needs the authoritative value: a slot changed in the
     * Settings activity must take effect on the very next swipe, so the read
     * never goes through a cache. Returns `null` for an unassigned slot, for
     * [SwipeSlot.NONE], or on a transient read failure (non-destructive: no
     * launch rather than a wrong app).
     */
    suspend fun getSwipeActionComponent(slot: SwipeSlot): String?
}
