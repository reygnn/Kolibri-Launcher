package com.github.reygnn.kolibri_launcher.ui.swipeactions

import androidx.annotation.StringRes
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo

/**
 * Definiert den gesamten UI-Zustand für den SwipeActionsActivity-Bildschirm.
 *
 * @property titleResId Die String-Ressource für den Haupttitel.
 * @property subtitleResId Die String-Ressource für den Untertitel.
 * @property selectableApps Die gefilterte Liste aller Apps, angereichert mit ihrem Zuweisungsstatus.
 * @property appForLeft Die App, die aktuell dem "Swipe Left"-Slot zugewiesen ist (null, wenn keine).
 * @property appForRight Die App, die aktuell dem "Swipe Right"-Slot zugewiesen ist (null, wenn keine).
 * @property currentSlotBeingAssigned Welcher Slot ist gerade aktiv? (LEFT oder RIGHT).
 * Wenn der Benutzer jetzt eine App anklickt, wird sie
 * diesem Slot zugewiesen.
 */
data class SwipeActionsUiState(
    @param:StringRes val titleResId: Int = R.string.swipe_actions_title_screen,
    @param:StringRes val subtitleResId: Int = R.string.swipe_actions_subtitle_screen,
    val selectableApps: List<SwipeActionSelectableApp> = emptyList(),
    val appForLeft: AppInfo? = null,
    val appForRight: AppInfo? = null,
    val currentSlotBeingAssigned: SwipeSlot = SwipeSlot.LEFT // Standardmäßig ist "Links" aktiv
)