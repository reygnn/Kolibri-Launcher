package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import kotlinx.coroutines.flow.Flow

/**
 * Ein Interface, das den Vertrag für das Speichern und Abrufen von
 * App-Zuweisungen für Wischgesten (Swipe Actions) definiert.
 */
interface SwipeActionsRepository : Purgeable {

    /**
     * Ein Flow, der den ComponentName der App für "Swipe Left" bereitstellt.
     * Emittiert `null`, wenn keine App zugewiesen ist.
     */
    val swipeLeftAppFlow: Flow<String?>

    /**
     * Ein Flow, der den ComponentName der App für "Swipe Right" bereitstellt.
     * Emittiert `null`, wenn keine App zugewiesen ist.
     */
    val swipeRightAppFlow: Flow<String?>

    /**
     * Speichert die Zuweisung für einen bestimmten [SwipeSlot].
     *
     * @param slot Der Slot, der aktualisiert wird (muss LEFT oder RIGHT sein).
     * @param componentName Der ComponentName der App (z.B. "com.app/com.app.MainActivity")
     * oder `null`, um die Zuweisung für diesen Slot zu löschen.
     */
    suspend fun setSwipeAction(slot: SwipeSlot, componentName: String?)
}