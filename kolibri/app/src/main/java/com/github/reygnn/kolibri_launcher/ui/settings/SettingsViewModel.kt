package com.github.reygnn.kolibri_launcher.ui.settings

import com.github.reygnn.kolibri_launcher.BuildConfig
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.MainDispatcher
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.DataStoreMaintenanceRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.FactoryResetUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.ui.base.BaseViewModel
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val factoryResetUseCase: FactoryResetUseCase,
    private val favoritesRepository: FavoritesRepository,
    private val favoritesOrderRepository: FavoritesOrderRepository,
    private val dataStoreMaintenanceRepository: DataStoreMaintenanceRepository,
    @MainDispatcher mainDispatcher: CoroutineDispatcher
) : BaseViewModel<UiEvent>(mainDispatcher) {

    override val errorEvent = UiEvent.ShowToast(R.string.error_generic)

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    init {
        observeApps()
    }

    private fun observeApps() {
        launchSafe {
            try {
                // Nutzung des UseCases statt des Repositories
                getInstalledAppsUseCase.unsortedInstalledAppsFlow.collect { apps ->
                    if (BuildConfig.DEBUG) {
                        Timber.d("[ViewModel] Collected ${apps.size} apps")
                    }
                    _installedApps.value = apps
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error observing apps")
                sendEvent(UiEvent.ShowToast(R.string.error_loading_apps))
            }
        }
    }

    fun onAppListNotLoaded() {
        launchSafe {
            sendEvent(UiEvent.ShowToast(R.string.error_app_list_not_loaded))
            if (BuildConfig.DEBUG) {
                Timber.w("App list not loaded when action attempted")
            }
        }
    }

    fun onNoFavoritesToSort() {
        launchSafe {
            sendEvent(UiEvent.ShowToast(R.string.no_favorites_to_sort))
        }
    }

    /**
     * Outcome of [prepareFavoritesForSorting]. Keeps the fragment thin glue: it
     * maps each case to a toast or the sort-dialog transaction.
     */
    sealed interface SortFavoritesOutcome {
        /** The installed-apps list wasn't loaded yet — nothing to sort against. */
        data object AppsNotLoaded : SortFavoritesOutcome

        /** No favorites intersect the installed apps — nothing to sort. */
        data object NoFavorites : SortFavoritesOutcome

        /** Ready to open the sort dialog on this ordered favorites list. */
        data class Ready(val orderedFavorites: List<AppInfo>) : SortFavoritesOutcome
    }

    /**
     * Builds the ordered favorites list for the sort dialog.
     *
     * Reads favorites and their order via the authoritative snapshots
     * ([FavoritesRepository.getFavoriteComponentsSnapshot] /
     * [FavoritesOrderRepository.getFavoriteComponentsOrderSnapshot]) — the explicit
     * decide-path point-reads, not the display flows. This was the AUDIT-13 fix
     * (Settings is a separate Activity with no warm Home subscriber, so a `.first()`
     * on the then-hot replay flow could sort a stale set after a backup restore);
     * since the DATASTORE_READ_SPEC Belang A teardown those flows are cold, so the
     * snapshots and the flows now agree — the snapshot call stays as the clear
     * decide-path contract. Lifted out of the fragment (Rule 10) so this decision
     * is JVM-testable.
     *
     * Fail-open per read (mirrors the fragment it replaced): a non-cancellation
     * read/sort error degrades to empty / unsorted rather than aborting the dialog;
     * `CancellationException` always propagates.
     */
    suspend fun prepareFavoritesForSorting(allApps: List<AppInfo>): SortFavoritesOutcome {
        if (allApps.isEmpty()) return SortFavoritesOutcome.AppsNotLoaded

        val favoriteComponents = try {
            favoritesRepository.getFavoriteComponentsSnapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error getting favorite components")
            emptySet()
        }

        val favoriteApps = allApps.filter { favoriteComponents.contains(it.componentName) }
        if (favoriteApps.isEmpty()) return SortFavoritesOutcome.NoFavorites

        val savedOrder = try {
            favoritesOrderRepository.getFavoriteComponentsOrderSnapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error getting saved order")
            emptyList()
        }

        val orderedFavorites = try {
            favoritesOrderRepository.sortFavoriteComponents(favoriteApps, savedOrder)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error sorting favorites")
            favoriteApps
        }

        return SortFavoritesOutcome.Ready(orderedFavorites)
    }

    fun onWallpaperSettingsFallback() {
        launchSafe {
            sendEvent(UiEvent.ShowToast(R.string.wallpaper_settings_fallback))
        }
    }

    fun onErrorOpeningWallpaperSettings(e: Throwable) {
        launchSafe {
            sendEvent(UiEvent.ShowToast(R.string.error_wallpaper_settings_open))
            TimberWrapper.silentError(e, "Error opening wallpaper settings")
        }
    }

    fun onErrorOpeningAppInfo(e: Throwable) {
        launchSafe {
            sendEvent(UiEvent.ShowToast(R.string.error_app_info_open))
            TimberWrapper.silentError(e, "Error opening app info")
        }
    }

    fun onErrorOpeningDefaultLauncherSettings(e: Throwable) {
        launchSafe {
            sendEvent(UiEvent.ShowToast(R.string.error_default_launcher_settings_open))
            TimberWrapper.silentError(e, "Error opening default launcher settings")
        }
    }

    /**
     * Führt einen kompletten "Factory Reset" der App-Daten durch.
     * @param includeUsageData Ob auch die App-Nutzungsdaten (Sortierung)
     * gelöscht werden sollen.
     */
    fun onFactoryResetConfirmed(includeUsageData: Boolean) {
        launchSafe {
            // Nutzung des UseCases statt des Repositories
            val result = factoryResetUseCase(includeUsageData)

            when (result) {
                is FactoryResetUseCase.Result.Success -> {
                    sendEvent(UiEvent.ShowToast(R.string.reset_success))
                }
                is FactoryResetUseCase.Result.PartialFailure -> {
                    // Optionale Unterscheidung, hier standardmäßig "failed" Toast
                    sendEvent(UiEvent.ShowToast(R.string.reset_failed))
                }
                is FactoryResetUseCase.Result.Error -> {
                    sendEvent(UiEvent.ShowToast(R.string.reset_failed))
                }
            }
        }
    }

    /**
     * Removes orphaned settings-store keys (retired features / moved stores). Driven by the
     * [com.github.reygnn.kolibri_launcher.core.OwnsSettingsStoreKeys] keep-list: every key no live
     * owner claims is removed, so nothing hand-maintained can go stale. Reports whether anything was
     * cleaned.
     */
    fun onCleanupStorageConfirmed() {
        launchSafe {
            val messageResId = when (val result = dataStoreMaintenanceRepository.removeOrphanKeys()) {
                is DataStoreMaintenanceRepository.Result.Removed ->
                    if (result.count > 0) R.string.cleanup_storage_done else R.string.cleanup_storage_none
                // A failed edit must not masquerade as "already clean" (Rule 11 / AUDIT-10).
                DataStoreMaintenanceRepository.Result.Failed -> R.string.cleanup_storage_error
            }
            sendEvent(UiEvent.ShowToast(messageResId))
        }
    }

    /**
     * Dry run for the storage cleanup: returns which keys [onCleanupStorageConfirmed] would delete,
     * without deleting anything, so the UI can show the user before they confirm. Maps the domain
     * [DataStoreMaintenanceRepository.PreviewResult] to a UI-facing [CleanupPreview]; the empty-vs-
     * non-empty and failure routing lives in the caller.
     */
    suspend fun getCleanupPreview(): CleanupPreview =
        when (val result = dataStoreMaintenanceRepository.previewOrphanKeys()) {
            is DataStoreMaintenanceRepository.PreviewResult.Loaded -> CleanupPreview.Loaded(result.keyNames)
            DataStoreMaintenanceRepository.PreviewResult.Failed -> CleanupPreview.Failed
        }

    /** UI-facing result of [getCleanupPreview]. */
    sealed interface CleanupPreview {
        /** [keyNames] are the keys a cleanup would remove (empty = already clean). */
        data class Loaded(val keyNames: List<String>) : CleanupPreview

        /** The preview read failed; the orphan set is unknown. */
        data object Failed : CleanupPreview
    }
}