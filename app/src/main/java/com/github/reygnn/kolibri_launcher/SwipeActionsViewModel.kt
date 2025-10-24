package com.github.reygnn.kolibri_launcher

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@HiltViewModel
class SwipeActionsViewModel @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository,
    private val swipeActionsRepository: SwipeActionsRepository, // NEUE Abhängigkeit
    @MainDispatcher mainDispatcher: CoroutineDispatcher
) : BaseViewModel<UiEvent>(mainDispatcher) {

    private val _uiState = MutableStateFlow(SwipeActionsUiState())
    val uiState: StateFlow<SwipeActionsUiState> = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")
    private val allAppsMasterList = MutableStateFlow<List<AppInfo>>(emptyList())

    // Welcher Slot ist gerade für die Zuweisung aktiv?
    private val currentSlotBeingAssigned = MutableStateFlow(SwipeSlot.LEFT)

    // Die ComponentNames der zugewiesenen Apps
    private val swipeLeftComponent = MutableStateFlow<String?>(null)
    private val swipeRightComponent = MutableStateFlow<String?>(null)

    init {
        launchSafe {
            // Kombiniere alle Datenströme, um den finalen UI-State zu erstellen
            combine(
                allAppsMasterList,
                searchQuery,
                currentSlotBeingAssigned,
                swipeLeftComponent,
                swipeRightComponent
            ) { allApps, query, activeSlot, leftComp, rightComp ->

                // 1. Finde die AppInfo-Objekte für die Slots
                val appForLeft = allApps.find { it.componentName == leftComp }
                val appForRight = allApps.find { it.componentName == rightComp }

                // 2. Erstelle die gefilterte App-Liste
                val filteredApps = if (query.isBlank()) {
                    allApps
                } else {
                    allApps.filter { it.displayName.contains(query, ignoreCase = true) }
                }

                // 3. Erstelle die "Selectable" Liste für den Adapter
                val selectableList = filteredApps.map { app ->
                    val assignedSlot = when (app.componentName) {
                        leftComp -> SwipeSlot.LEFT
                        rightComp -> SwipeSlot.RIGHT
                        else -> SwipeSlot.NONE
                    }
                    SwipeActionSelectableApp(appInfo = app, assignedSlot = assignedSlot)
                }

                // 4. Erstelle den neuen UI-State
                SwipeActionsUiState(
                    titleResId = R.string.swipe_actions_title_screen,
                    subtitleResId = R.string.swipe_actions_subtitle_screen,
                    selectableApps = selectableList,
                    appForLeft = appForLeft,
                    appForRight = appForRight,
                    currentSlotBeingAssigned = activeSlot
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    /**
     * Startet das Laden der Daten. Wird von der Activity aufgerufen.
     */
    internal fun initialize() {
        launchSafe {
            try {
                // Lade alle installierten Apps
                val allApps = installedAppsRepository.getInstalledApps().first()
                    .sortedBy { it.displayName.lowercase() }
                allAppsMasterList.value = allApps

                // Lade die aktuell gespeicherten Zuweisungen
                swipeLeftComponent.value = swipeActionsRepository.swipeLeftAppFlow.first()
                swipeRightComponent.value = swipeActionsRepository.swipeRightAppFlow.first()

            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error loading swipe actions")
                sendEvent(UiEvent.ShowToast(R.string.error_loading_apps))
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        executeSafe {
            searchQuery.value = query
        }
    }

    /**
     * Wird aufgerufen, wenn der Benutzer oben auf "Links" oder "Rechts" tippt.
     * Setzt den Slot, der als Nächstes befüllt wird.
     */
    fun onSlotSelected(slot: SwipeSlot) {
        executeSafe {
            if (slot != SwipeSlot.NONE) {
                currentSlotBeingAssigned.value = slot
            }
        }
    }

    /**
     * Wird aufgerufen, wenn der Benutzer eine App in der RecyclerView antippt.
     * Weist die App dem aktuell aktiven Slot zu.
     */
    fun onAppSelected(app: AppInfo) {
        executeSafe {
            val component = app.componentName
            val activeSlot = currentSlotBeingAssigned.value

            val currentLeft = swipeLeftComponent.value
            val currentRight = swipeRightComponent.value

            if (activeSlot == SwipeSlot.LEFT) {
                // App dem "Left"-Slot zuweisen
                // Wenn die App schon "Left" war, hebe Zuweisung auf (setze auf null)
                val newLeft = if (currentLeft == component) null else component
                swipeLeftComponent.value = newLeft

                // Wenn diese App vorher "Right" war, entferne sie von "Right"
                if (newLeft != null && currentRight == component) {
                    swipeRightComponent.value = null
                }
            } else {
                // App dem "Right"-Slot zuweisen
                // Wenn die App schon "Right" war, hebe Zuweisung auf
                val newRight = if (currentRight == component) null else component
                swipeRightComponent.value = newRight

                // Wenn diese App vorher "Left" war, entferne sie von "Left"
                if (newRight != null && currentLeft == component) {
                    swipeLeftComponent.value = null
                }
            }
        }
    }

    /**
     * Setzt den angegebenen Slot auf null (keine App zugewiesen).
     */
    fun onSlotCleared(slot: SwipeSlot) {
        executeSafe {
            when (slot) {
                SwipeSlot.LEFT -> swipeLeftComponent.value = null
                SwipeSlot.RIGHT -> swipeRightComponent.value = null
                SwipeSlot.NONE -> {} // Nichts tun
            }
        }
    }

    /**
     * Speichert die aktuelle Auswahl und schließt den Bildschirm.
     */
    fun onDoneClicked() {
        launchSafe {
            try {
                // Speichere die finalen Werte im Repository
                swipeActionsRepository.setSwipeAction(SwipeSlot.LEFT, swipeLeftComponent.value)
                swipeActionsRepository.setSwipeAction(SwipeSlot.RIGHT, swipeRightComponent.value)

                sendEvent(UiEvent.NavigateUp)
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error saving swipe actions")
                sendEvent(UiEvent.ShowToast(R.string.error_saving_swipe_actions))
                sendEvent(UiEvent.NavigateUp) // Trotz Fehler schließen
            }
        }
    }
}