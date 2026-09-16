package com.github.reygnn.nyx_launcher.home

import androidx.lifecycle.viewModelScope
import com.github.reygnn.launcher.common.ui.base.BaseViewModel
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.MainDispatcher
import com.github.reygnn.nyx_launcher.home.model.DrawerDropTarget
import com.github.reygnn.nyx_launcher.home.model.DrawerEntry
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderId
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderIdFactory
import com.github.reygnn.nyx_launcher.home.model.DropTarget
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.LauncherApp
import com.github.reygnn.nyx_launcher.home.repository.DrawerFoldersRepository
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import com.github.reygnn.nyx_launcher.home.transition.DrawerFoldersTransition
import com.github.reygnn.nyx_launcher.home.usecase.FitHomeGridUseCase
import com.github.reygnn.nyx_launcher.home.usecase.GetDrawerAppsUseCase
import com.github.reygnn.nyx_launcher.home.usecase.GetDrawerContentUseCase
import com.github.reygnn.nyx_launcher.home.usecase.MoveItemUseCase
import com.github.reygnn.nyx_launcher.home.usecase.ObserveHomeLayoutUseCase
import com.github.reygnn.nyx_launcher.home.usecase.PlaceItemUseCase
import com.github.reygnn.nyx_launcher.home.usecase.RemoveFromFolderUseCase
import com.github.reygnn.nyx_launcher.home.usecase.RemoveItemUseCase
import com.github.reygnn.nyx_launcher.home.usecase.RenameFolderUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home + drawer state holder. [layout], [drawerApps] and [drawerContent] are
 * lifecycle-aware StateFlows. Mutations run through the pure transitions + save;
 * the source flows re-emit on success, so the UI re-renders itself.
 *
 * Extends the shared [BaseViewModel] (:common-ui) purely for its coroutine
 * crash-safety: the fire-and-forget edit dispatches go through [launchSafe], so a
 * throwing use case is reported instead of escaping to the global handler and
 * crashing the HOME activity. The event type is [Nothing] — this ViewModel emits
 * no one-shot UI events (all state is exposed as StateFlow), so the base's event
 * channel is never used.
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
    getDrawerContent: GetDrawerContentUseCase,
    private val drawerFoldersRepository: DrawerFoldersRepository,
    private val drawerFolderIdFactory: DrawerFolderIdFactory,
    @MainDispatcher mainDispatcher: CoroutineDispatcher,
) : BaseViewModel<Nothing>(mainDispatcher) {

    val layout: StateFlow<HomeLayout?> = observeHomeLayout()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val monochromeIcons: StateFlow<Boolean> = preferences.monochromeIcons()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _drawerApps = MutableStateFlow<List<LauncherApp>>(emptyList())
    val drawerApps: StateFlow<List<LauncherApp>> = _drawerApps.asStateFlow()

    /**
     * The drawer's rendered content (DRAWER_FOLDERS_SPEC §5): the live apps projected
     * through the persisted folder membership — a pinned folder block, then the loose
     * apps. Re-emits when the apps refresh ([refreshDrawer]) or the membership changes.
     */
    val drawerContent: StateFlow<List<DrawerEntry>> =
        getDrawerContent(drawerApps)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        launchSafe { moveItem(moving, target) }
    }

    fun place(app: ComponentKey, target: DropTarget) {
        launchSafe { placeItem(app, target) }
    }

    fun extractFromFolder(folder: ItemId, member: ComponentKey, target: DropTarget) {
        launchSafe { removeFromFolder(folder, member, target) }
    }

    fun remove(id: ItemId) {
        launchSafe { removeItem(id) }
    }

    fun renameFolder(folder: ItemId, title: String) {
        launchSafe { renameFolderUseCase(folder, title) }
    }

    // ---- drawer folders (DRAWER_FOLDERS_SPEC §8) ----

    /** Drop the loose app [source] onto [target] → create a drawer folder from the two. */
    fun createDrawerFolder(source: ComponentKey, target: ComponentKey) {
        launchSafe {
            drawerFoldersRepository.update {
                DrawerFoldersTransition.drop(it, source, DrawerDropTarget.OntoApp(target), drawerFolderIdFactory::next)
            }
        }
    }

    /** Drop the loose app [source] onto the drawer folder [folderId] → add it as a member. */
    fun addToDrawerFolder(source: ComponentKey, folderId: DrawerFolderId) {
        launchSafe {
            drawerFoldersRepository.update {
                DrawerFoldersTransition.drop(it, source, DrawerDropTarget.OntoFolder(folderId), drawerFolderIdFactory::next)
            }
        }
    }

    /** Extract [member] from the opened drawer folder [folderId] (shrink, or dissolve below two). */
    fun extractFromDrawerFolder(folderId: DrawerFolderId, member: ComponentKey) {
        launchSafe {
            drawerFoldersRepository.update {
                DrawerFoldersTransition.extract(it, folderId, member)
            }
        }
    }

    /** Rename the drawer folder [folderId] to [title] (caller passes the normalized title). */
    fun renameDrawerFolder(folderId: DrawerFolderId, title: String) {
        launchSafe {
            drawerFoldersRepository.update {
                DrawerFoldersTransition.rename(it, folderId, title)
            }
        }
    }

    /**
     * Re-fit the layout onto the device grid the UI measured from the real home
     * area. Idempotent: a no-op when the grid already matches. Runs on every
     * MainActivity layout, so a new device or an orientation change is absorbed.
     */
    fun applyDeviceGrid(columns: Int, rows: Int) {
        launchSafe { fitHomeGrid(GridSpec(columns, rows)) }
    }
}
