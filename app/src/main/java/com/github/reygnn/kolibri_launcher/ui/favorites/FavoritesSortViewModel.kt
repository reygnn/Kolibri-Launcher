package com.github.reygnn.kolibri_launcher.ui.favorites

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.MainDispatcher
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.ui.base.BaseViewModel
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Owns the state for the favorites-reorder screen and the sort/reset/persist
 * orchestration that previously lived directly inside `FavoritesSortFragment`.
 *
 * The Fragment retains everything that genuinely needs Android types — the
 * `RecyclerView` + adapter wiring, the `ItemTouchHelper` drag handling, the
 * `setFragmentResult` callback, and the toast rendering. This ViewModel
 * holds the list of apps as a `StateFlow` (Fragment collects → adapter
 * `submitList`), captures the original order on first input so the Reset
 * button has something to revert to, and emits `UiEvent`s for both the
 * status toasts and the order-changed signal that drives the fragment
 * result.
 *
 * Introduced as part of the Rule 10 cleanup (CLAUDE.md): the sort algorithm
 * and the persist-then-broadcast-or-toast orchestration are pure logic and
 * belong outside the Fragment so they can be unit-tested on the JVM.
 */
@HiltViewModel
class FavoritesSortViewModel @Inject constructor(
    private val favoritesOrderRepository: FavoritesOrderRepository,
    @MainDispatcher mainDispatcher: CoroutineDispatcher,
) : BaseViewModel<UiEvent>(mainDispatcher) {

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    /**
     * The list as it was when the screen opened — Reset returns to this
     * snapshot, not to whatever the repository currently has. Captured on
     * the first call to [setInitialApps]; subsequent calls are ignored so
     * the original snapshot survives configuration changes (the Fragment
     * runs `onCreate` again and would otherwise overwrite the user's
     * unsaved drag-and-drop progress).
     */
    private var originalOrder: List<AppInfo> = emptyList()
    private var initialized = false

    fun setInitialApps(apps: List<AppInfo>) {
        if (initialized) return
        initialized = true
        originalOrder = apps.toList()
        _apps.value = originalOrder
    }

    /**
     * Called from the adapter's `onMoveFinished` callback after a
     * drag-and-drop reorder. The adapter has already animated the move;
     * we just record the new order and persist.
     */
    fun onMoved(newOrder: List<AppInfo>) {
        _apps.value = newOrder
        persistOrder(newOrder, successToastResId = null)
    }

    /**
     * Sort the current visible list alphabetically by `displayName` (case
     * insensitive), persist, and emit the "sorted" toast on success.
     * Sorting an already-sorted list is a no-op as far as the user can
     * tell but still re-persists, matching the pre-extraction behavior.
     */
    fun onSortAlphabetically() {
        val sorted = _apps.value.sortedBy { it.displayName.lowercase() }
        _apps.value = sorted
        persistOrder(sorted, successToastResId = R.string.favorites_sorted_alphabetically)
    }

    /**
     * Restore the order captured at [setInitialApps] time and persist.
     * If the user never touched anything since opening the screen, this
     * still re-persists — matching the pre-extraction behavior.
     */
    fun onResetToOriginal() {
        _apps.value = originalOrder
        persistOrder(originalOrder, successToastResId = R.string.favorites_order_reset)
    }

    /**
     * The persist + broadcast-or-error-toast tail. On success, emits
     * [UiEvent.FavoritesOrderChanged] (which the Fragment translates
     * into a `setFragmentResult`) plus the optional [successToastResId]
     * if one was provided. On any failure (`saveOrder` returning false
     * or throwing), emits a generic save-failed toast and skips the
     * change broadcast.
     */
    private fun persistOrder(apps: List<AppInfo>, successToastResId: Int?) {
        launchSafe {
            try {
                val componentNames = apps.map { it.componentName }
                val success = favoritesOrderRepository.saveOrder(componentNames)

                if (success) {
                    sendEvent(UiEvent.FavoritesOrderChanged)
                    if (successToastResId != null) {
                        sendEvent(UiEvent.ShowToast(successToastResId))
                    }
                } else {
                    sendEvent(UiEvent.ShowToast(R.string.error_saving_order))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error persisting favorites order")
                sendEvent(UiEvent.ShowToast(R.string.error_saving_order))
            }
        }
    }
}
