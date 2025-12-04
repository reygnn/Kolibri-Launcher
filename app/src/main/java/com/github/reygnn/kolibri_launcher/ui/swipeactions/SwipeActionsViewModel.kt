package com.github.reygnn.kolibri_launcher.ui.swipeactions

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.di.MainDispatcher
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.usecase.GetInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetSwipeLeftAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetSwipeRightAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.ui.base.BaseViewModel
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

@HiltViewModel
class SwipeActionsViewModel @Inject constructor(
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val getSwipeLeftAppUseCase: GetSwipeLeftAppUseCase,
    private val getSwipeRightAppUseCase: GetSwipeRightAppUseCase,
    private val setSwipeActionUseCase: SetSwipeActionUseCase,
    @MainDispatcher mainDispatcher: CoroutineDispatcher
) : BaseViewModel<UiEvent>(mainDispatcher) {

    private val _uiState = MutableStateFlow(SwipeActionsUiState())
    val uiState: StateFlow<SwipeActionsUiState> = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")
    private val allAppsMasterList = MutableStateFlow<List<AppInfo>>(emptyList())

    // Welcher Slot ist gerade für die Zuweisung aktiv?
    private val currentSlotBeingAssigned = MutableStateFlow(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)

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
                        leftComp -> SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT
                        rightComp -> SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT
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
                // Lade alle installierten Apps via UseCase
                val allApps = getInstalledAppsUseCase().first()
                    .sortedBy { it.displayName.lowercase() }
                allAppsMasterList.value = allApps

                // Lade die aktuell gespeicherten Zuweisungen via UseCases
                swipeLeftComponent.value = getSwipeLeftAppUseCase().first()
                swipeRightComponent.value = getSwipeRightAppUseCase().first()

            } catch (e: CancellationException) {
                throw e
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

    fun onSlotSelected(slot: SwipeSlot) {
        executeSafe {
            if (slot != SwipeSlot.NONE) {
                currentSlotBeingAssigned.value = slot
            }
        }
    }

    fun onAppSelected(app: AppInfo) {
        executeSafe {
            val component = app.componentName
            val activeSlot = currentSlotBeingAssigned.value

            val currentLeft = swipeLeftComponent.value
            val currentRight = swipeRightComponent.value

            if (activeSlot == SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT) {
                // App dem "Left"-Slot zuweisen
                val newLeft = if (currentLeft == component) null else component
                swipeLeftComponent.value = newLeft

                if (newLeft != null && currentRight == component) {
                    swipeRightComponent.value = null
                }
            } else {
                // App dem "Right"-Slot zuweisen
                val newRight = if (currentRight == component) null else component
                swipeRightComponent.value = newRight

                if (newRight != null && currentLeft == component) {
                    swipeLeftComponent.value = null
                }
            }
        }
    }

    fun onSlotCleared(slot: SwipeSlot) {
        executeSafe {
            when (slot) {
                SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT -> swipeLeftComponent.value = null
                SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT -> swipeRightComponent.value = null
                SwipeSlot.NONE -> {}
            }
        }
    }

    fun onDoneClicked() {
        launchSafe {
            try {
                // Speichere via UseCase
                setSwipeActionUseCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, swipeLeftComponent.value)
                setSwipeActionUseCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, swipeRightComponent.value)

                sendEvent(UiEvent.NavigateUp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error saving swipe actions")
                sendEvent(UiEvent.ShowToast(R.string.error_saving_swipe_actions))
                sendEvent(UiEvent.NavigateUp)
            }
        }
    }
}