package com.github.reygnn.nyx_launcher.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import com.github.reygnn.nyx_launcher.home.usecase.FitHomeGridUseCase
import com.github.reygnn.nyx_launcher.home.usecase.GetDrawerAppsUseCase
import com.github.reygnn.nyx_launcher.home.usecase.MoveItemUseCase
import com.github.reygnn.nyx_launcher.home.usecase.ObserveHomeLayoutUseCase
import com.github.reygnn.nyx_launcher.home.usecase.PlaceItemUseCase
import com.github.reygnn.nyx_launcher.home.usecase.RemoveFromFolderUseCase
import com.github.reygnn.nyx_launcher.home.usecase.RemoveItemUseCase
import com.github.reygnn.nyx_launcher.home.usecase.RenameFolderUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home + drawer state holder. [layout] and [drawerApps] are lifecycle-aware
 * StateFlows. Mutations run through the pure transitions + save; the layout flow
 * re-emits on success, so the UI re-renders itself.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    observeHomeLayout: ObserveHomeLayoutUseCase,
    private val getDrawerApps: GetDrawerAppsUseCase,
    private val moveItem: MoveItemUseCase,
    private val placeItem: PlaceItemUseCase,
    private val removeFromFolder: RemoveFromFolderUseCase,
    private val removeItem: RemoveItemUseCase,
    private val renameFolderUseCase: RenameFolderUseCase,
    private val fitHomeGrid: FitHomeGridUseCase,
    preferences: PreferencesRepository,
) : ViewModel() {

    val layout: StateFlow<HomeLayout?> = observeHomeLayout()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val monochromeIcons: StateFlow<Boolean> = preferences.monochromeIcons()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _drawerApps = MutableStateFlow<List<LauncherApp>>(emptyList())
    val drawerApps: StateFlow<List<LauncherApp>> = _drawerApps.asStateFlow()

    private var refreshJob: Job? = null

    init {
        refreshDrawer()
    }

    /**
     * Re-query the drawer app list. Called at startup and on every drawer open
     * (MainActivity.showDrawer), so apps installed/removed while nyx was already
     * running show up — the list is otherwise loaded once and goes stale (A1-04).
     *
     * Cancels any in-flight refresh so the newest query wins (no stale clobber on
     * rapid re-open), and never overwrites a populated list with an empty one:
     * after A1-01 an empty result signals a failed enumeration, so a transient
     * failure on re-open must not blank the drawer.
     */
    fun refreshDrawer() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val apps = getDrawerApps()
            if (apps.isNotEmpty() || _drawerApps.value.isEmpty()) {
                _drawerApps.value = apps
            }
        }
    }

    fun move(moving: ItemId, target: DropTarget) {
        viewModelScope.launch { moveItem(moving, target) }
    }

    fun place(app: ComponentKey, target: DropTarget) {
        viewModelScope.launch { placeItem(app, target) }
    }

    fun extractFromFolder(folder: ItemId, member: ComponentKey, target: DropTarget) {
        viewModelScope.launch { removeFromFolder(folder, member, target) }
    }

    fun remove(id: ItemId) {
        viewModelScope.launch { removeItem(id) }
    }

    fun renameFolder(folder: ItemId, title: String) {
        viewModelScope.launch { renameFolderUseCase(folder, title) }
    }

    /**
     * Re-fit the layout onto the device grid the UI measured from the real home
     * area. Idempotent: a no-op when the grid already matches. Runs on every
     * MainActivity layout, so a new device or an orientation change is absorbed.
     */
    fun applyDeviceGrid(columns: Int, rows: Int) {
        viewModelScope.launch { fitHomeGrid(GridSpec(columns, rows)) }
    }
}
