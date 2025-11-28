package com.github.reygnn.kolibri_launcher.ui.swipeactions

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo

/**
 * Repräsentiert eine App in der "Swipe Actions"-Auswahlliste.
 *
 * @property appInfo Die Basis-Informationen der App.
 * @property assignedSlot Der Slot, dem diese App aktuell zugewiesen ist (LEFT, RIGHT, or NONE).
 */
data class SwipeActionSelectableApp(
    val appInfo: AppInfo,
    val assignedSlot: SwipeSlot
)