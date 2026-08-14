package com.github.reygnn.kolibri_launcher.ui.swipeactions

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.MainDispatcher
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.sortedByDisplayName
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.github.reygnn.kolibri_launcher.domain.model.filterByName
import com.github.reygnn.kolibri_launcher.domain.usecase.GetInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetSwipeActionComponentUseCase
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
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

@HiltViewModel
class SwipeActionsViewModel @Inject constructor(
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val getSwipeActionComponentUseCase: GetSwipeActionComponentUseCase,
    private val setSwipeActionUseCase: SetSwipeActionUseCase,
    @MainDispatcher mainDispatcher: CoroutineDispatcher
) : BaseViewModel<UiEvent>(mainDispatcher) {

    override val errorEvent = UiEvent.ShowToast(R.string.error_generic)

    private val _uiState = MutableStateFlow(SwipeActionsUiState())
    val uiState: StateFlow<SwipeActionsUiState> = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")
    private val allAppsMasterList = MutableStateFlow<List<AppInfo>>(emptyList())

    // Welcher Slot ist gerade für die Zuweisung aktiv?
    private val currentSlotBeingAssigned = MutableStateFlow(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)

    // Die ComponentNames der zugewiesenen Apps
    private val swipeLeftComponent = MutableStateFlow<String?>(null)
    private val swipeRightComponent = MutableStateFlow<String?>(null)
    private var isInitialized = false

    // Save-gate (AUDIT-17 F2). swipeLeftComponent/swipeRightComponent default to
    // null and are only populated at the END of initialize(), after the apps list
    // resolves. During that window the chips render "empty" but the Done button is
    // already enabled, so a Done tap would persist null/null over the stored
    // assignments -- the same save-over-empty class the sibling selection screens
    // guard (OnboardingViewModel PreselectState, HiddenAppsViewModel diff-against-
    // initial). Only persist once the initial read has populated the slots; a
    // legitimate user-cleared null (isLoaded == true) still saves.
    private var isLoaded = false

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

                // 2. Filter the app list via the shared name filter.
                val filteredApps = allApps.filterByName(query)

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
     *
     * Guarded against config-change re-entry: the Activity calls this
     * unconditionally in onCreate, but the ViewModel is retained across
     * rotation. Without the guard a re-run would overwrite the in-memory
     * slot assignments with the persisted ones, silently dropping the user's
     * uncommitted changes. Same pattern as OnboardingViewModel.loadInitialData().
     */
    internal fun initialize() {
        if (isInitialized) return
        isInitialized = true

        launchSafe {
            try {
                // Cold-path race: GetInstalledAppsUseCase emits a
                // WhileSubscribed StateFlow with initialValue=emptyList(),
                // so a bare .first() from a cold Swipe-Actions entry (no
                // HomeFragment subscriber yet) returns the empty list and
                // unsubscribes. Wait for a real emission with a bounded
                // timeout — same pattern as BackupDataAssembler and
                // HiddenAppsViewModel.
                val allApps = withTimeoutOrNull(AppConstants.INSTALLED_APPS_PRIME_TIMEOUT_MS) {
                    getInstalledAppsUseCase().first { it.isNotEmpty() }
                }?.sortedByDisplayName()
                    ?: error("Timed out waiting for InstalledAppsRepository to populate in SwipeActionsViewModel")

                // Load the stored slot assignments BEFORE publishing the master
                // list, then assign all three without a suspend point between
                // them, so the init-block combine collector never emits a
                // transient "full list, no assignments" state (sub-frame flash).
                //
                // Authoritative fresh reads (getSwipeActionComponent → store):
                // reopening this cold settings screen must show the value
                // currently stored, never a cached previous assignment in the
                // chip. Same authoritative-read contract as the launch path.
                val left = getSwipeActionComponentUseCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)
                val right = getSwipeActionComponentUseCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT)
                allAppsMasterList.value = allApps
                swipeLeftComponent.value = left
                swipeRightComponent.value = right
                // Slots are now populated from the store -- a Done tap may persist
                // (AUDIT-17 F2). Set last, after the assignments above.
                isLoaded = true

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
                // Save-gate (AUDIT-17 F2): only persist once initialize() has loaded
                // the stored slots. A Done tap in the pre-load window would otherwise
                // overwrite the stored assignments with the default null/null. Still
                // navigate up either way, so an early tap isn't a dead end.
                if (isLoaded) {
                    setSwipeActionUseCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, swipeLeftComponent.value)
                    setSwipeActionUseCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, swipeRightComponent.value)
                }

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