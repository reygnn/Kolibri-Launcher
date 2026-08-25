/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher.ui.onboarding

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.MainDispatcher
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.sortedByDisplayName
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesEditRead
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.SelectableAppInfo
import com.github.reygnn.kolibri_launcher.domain.model.filterByName
import com.github.reygnn.kolibri_launcher.domain.usecase.CompleteOnboardingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteComponentsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetOnboardingAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ImportBackupUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.MarkOnboardingCompletedUseCase
import com.github.reygnn.kolibri_launcher.ui.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val onboardingAppsUseCase: GetOnboardingAppsUseCase,
    private val getFavoriteComponentsUseCase: GetFavoriteComponentsUseCase,
    private val completeOnboardingUseCase: CompleteOnboardingUseCase,
    private val importBackupUseCase: ImportBackupUseCase,
    private val markOnboardingCompletedUseCase: MarkOnboardingCompletedUseCase,
    @MainDispatcher mainDispatcher: CoroutineDispatcher
) : BaseViewModel<OnboardingEvent>(mainDispatcher) {

    private var launchMode: LaunchMode = LaunchMode.INITIAL_SETUP
    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val selectedComponents = MutableStateFlow<Set<String>>(emptySet())
    private val searchQuery = MutableStateFlow("")
    private var isInitialized = false

    /**
     * EDIT-favorites pre-selection state (DATASTORE_READ_SPEC Belang C). A boolean
     * would NOT close the wipe: the load runs async, so "Done tapped before the read
     * resolves" would still fire an empty save. This tri-state defaults to
     * [PreselectState.NotLoaded] (save BLOCKED in EDIT mode) and only flips to
     * [PreselectState.Loaded] once `selectedComponents` has been populated from a
     * successful read. INITIAL_SETUP ignores it (an empty save is legitimate on first
     * run).
     */
    private sealed interface PreselectState {
        data object NotLoaded : PreselectState
        data object Loaded : PreselectState
        data object Unavailable : PreselectState
    }
    private var preselectState: PreselectState = PreselectState.NotLoaded

    // Helper function to send OnboardingEvents safely
    private fun sendOnboardingEvent(event: OnboardingEvent) {
        launchSafe {
            sendEvent(event)
        }
    }

    init {
        launchSafe {
            try {
                combine(
                    onboardingAppsUseCase.onboardingAppsFlow,
                    selectedComponents,
                    searchQuery
                ) { allApps, selected, query ->
                    val filteredApps = allApps.filterByName(query)

                    val selectableList = filteredApps.map { app ->
                        SelectableAppInfo(
                            appInfo = app,
                            isSelected = selected.contains(app.componentName)
                        )
                    }

                    val selectedAppInfos = allApps
                        .filter { selected.contains(it.componentName) }
                        .sortedByDisplayName()

                    _uiState.value.copy(
                        selectableApps = selectableList,
                        selectedApps = selectedAppInfos
                    )
                }.collect { newState ->
                    _uiState.value = newState
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Failed to load apps.")
                sendOnboardingEvent(OnboardingEvent.ShowError(R.string.error_loading_apps))
            }
        }
    }

    fun setLaunchMode(mode: LaunchMode) {
        this.launchMode = mode

        val titleRes =
            if (mode == LaunchMode.EDIT_FAVORITES) R.string.onboarding_title_edit_favorites else R.string.onboarding_title_welcome
        val subtitleRes =
            if (mode == LaunchMode.EDIT_FAVORITES) R.string.onboarding_subtitle_edit_favorites else R.string.onboarding_subtitle_welcome
        // First-run extras only in INITIAL_SETUP. EDIT_FAVORITES (and, via the
        // false default, HiddenAppsActivity) never show them.
        val showExtras = mode == LaunchMode.INITIAL_SETUP
        _uiState.update {
            it.copy(titleResId = titleRes, subtitleResId = subtitleRes, showSetupExtras = showExtras)
        }
    }

    fun loadInitialData() {
        if (isInitialized) return
        isInitialized = true

        launchSafe {
            try {
                when (launchMode) {
                    LaunchMode.INITIAL_SETUP ->
                        // First run: an empty selection is legitimate; the save-gate
                        // exempts INITIAL_SETUP, so preselectState stays NotLoaded and
                        // is never consulted for this mode.
                        selectedComponents.value = emptySet()

                    LaunchMode.EDIT_FAVORITES ->
                        when (val read = getFavoriteComponentsUseCase()) {
                            is FavoritesEditRead.Loaded -> {
                                // DSR-INV-6: populate selectedComponents BEFORE flipping the
                                // state, with no suspension point between the two writes —
                                // both run on Main, so a concurrent onDoneClicked can never
                                // see Loaded with a not-yet-populated selection.
                                selectedComponents.value = read.components
                                preselectState = PreselectState.Loaded
                            }
                            is FavoritesEditRead.Unavailable -> {
                                // Fail-CLOSED: keep the selection empty AND mark Unavailable
                                // so the save-gate blocks a wipe; surface the failure now
                                // (load time), not silently at save.
                                preselectState = PreselectState.Unavailable
                                sendOnboardingEvent(OnboardingEvent.ShowError(R.string.error_loading_favorites))
                            }
                        }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // readFavoritesForEdit returns Unavailable for I/O rather than throwing;
                // a throw here is a non-I/O programmer error — treat it fail-closed too,
                // so the EDIT-mode save stays blocked.
                preselectState = PreselectState.Unavailable
                TimberWrapper.silentError(e, "Error loading initial favorites.")
                sendOnboardingEvent(OnboardingEvent.ShowError(R.string.error_loading_favorites))
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun onAppToggled(app: AppInfo) {
        launchSafe {
            val currentSelection = selectedComponents.value
            val component = app.componentName

            if (currentSelection.contains(component)) {
                selectedComponents.value = currentSelection - component
            } else {
                if (currentSelection.size >= AppConstants.MAX_FAVORITES_ON_HOME) {
                    sendOnboardingEvent(OnboardingEvent.ShowLimitReachedToast(AppConstants.MAX_FAVORITES_ON_HOME))
                } else {
                    selectedComponents.value = currentSelection + component
                }
            }
        }
    }

    fun onDoneClicked() {
        launchSafe {
            // A backup restore in flight has already persisted (or is persisting) the
            // favorites, while the in-memory selection is still empty. Completing here
            // would save that empty selection over them — the exact wipe restore guards
            // against. The button is also disabled in the UI; this is the correctness
            // backstop. Once the restore finishes it either navigates away or mirrors
            // the restored favorites into the selection, so a later Done is safe.
            if (_uiState.value.isRestoring) return@launchSafe
            // Save-gate (DATASTORE_READ_SPEC Belang C, DSR-INV-4): in EDIT mode, only
            // persist when the pre-selection loaded successfully. NotLoaded (read not
            // finished — e.g. Done tapped mid-load) or Unavailable (read failed) must
            // NOT save, or an empty/default selection would overwrite the real
            // favorites. INITIAL_SETUP is exempt: an empty save is legitimate on first
            // run. Correctness lives HERE, not just in the distinguishable read.
            if (launchMode == LaunchMode.EDIT_FAVORITES && preselectState != PreselectState.Loaded) {
                sendOnboardingEvent(OnboardingEvent.ShowError(R.string.error_loading_favorites))
                return@launchSafe
            }
            try {
                completeOnboardingUseCase(
                    componentNames = selectedComponents.value.toList(),
                    isInitialSetup = (launchMode == LaunchMode.INITIAL_SETUP)
                )

                sendOnboardingEvent(OnboardingEvent.NavigateToMain)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(
                    e,
                    "CRITICAL: Failed to save favorites or complete onboarding."
                )
                sendOnboardingEvent(OnboardingEvent.ShowError(R.string.onboarding_error_save_failed))
            }
        }
    }

    /**
     * First-run "restore a full backup right now" path. Loads the picked file and
     * restores EVERYTHING (default [ImportOptions] = all flags true).
     *
     * CRITICAL — do NOT reuse the onDoneClicked path afterwards: on success the
     * favorites have already been persisted by the import. In INITIAL_SETUP the
     * in-memory `selectedComponents` is still empty, so calling
     * CompleteOnboardingUseCase would immediately overwrite the restored
     * favorites with an empty list (the same wipe the DSR save-gate guards
     * against). We therefore only flip the onboarding-completed flag via
     * [markOnboardingCompletedUseCase] — the backup carries no such flag, so
     * without this the user would be dropped back into onboarding on next start
     * — and then navigate. The flag is INITIAL_SETUP-only; in EDIT_FAVORITES the
     * button isn't shown, and the guard keeps that invariant explicit.
     *
     * Two extra defenses against the same wipe (see AUDIT: restore/Done race):
     *  - [OnboardingUiState.isRestoring] blocks a concurrent [onDoneClicked] while
     *    this async import runs.
     *  - on success we mirror the restored favorites into `selectedComponents`, so
     *    even if the user reaches Done afterwards (e.g. the mark write below fails),
     *    the save writes the RESTORED set, not the empty one.
     */
    fun restoreBackupAndFinish(uriString: String) {
        launchSafe {
            _uiState.update { it.copy(isRestoring = true) }
            try {
                when (importBackupUseCase(uriString, ImportOptions())) {
                    is ImportResult.Success -> {
                        // The import already persisted the favorites. Mirror them into
                        // the in-memory selection (best-effort; they are on disk
                        // regardless) so a later Done saves the RESTORED set, not empty.
                        when (val read = getFavoriteComponentsUseCase()) {
                            is FavoritesEditRead.Loaded -> selectedComponents.value = read.components
                            is FavoritesEditRead.Unavailable -> Unit
                        }
                        if (launchMode == LaunchMode.INITIAL_SETUP) {
                            markOnboardingCompletedUseCase()
                        }
                        // Keep isRestoring = true: the activity navigates away here, so
                        // the buttons never re-enable on this screen.
                        sendOnboardingEvent(OnboardingEvent.NavigateToMain)
                    }
                    is ImportResult.LimitExceeded ->
                        failRestore(R.string.onboarding_restore_limit_exceeded)
                    is ImportResult.UnsupportedVersion ->
                        failRestore(R.string.onboarding_restore_unsupported_version)
                    is ImportResult.InvalidFormat ->
                        failRestore(R.string.onboarding_restore_invalid_format)
                    is ImportResult.Error ->
                        failRestore(R.string.onboarding_restore_failed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Unexpected throw from the import / favorites read / mark write. Any
                // restored favorites are already on disk; unfreeze the UI and surface
                // feedback instead of leaving the screen stuck with no toast (which is
                // what would happen if this escaped to the launchSafe safety net, as
                // OnboardingViewModel shows no generic error toast).
                TimberWrapper.silentError(e, "Backup restore failed unexpectedly.")
                failRestore(R.string.onboarding_restore_failed)
            }
        }
    }

    /** Re-enable the onboarding UI after a failed restore and show [messageResId]. */
    private fun failRestore(messageResId: Int) {
        _uiState.update { it.copy(isRestoring = false) }
        sendOnboardingEvent(OnboardingEvent.ShowError(messageResId))
    }
}