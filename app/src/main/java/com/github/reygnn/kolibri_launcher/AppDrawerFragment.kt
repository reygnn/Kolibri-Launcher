/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.reygnn.kolibri_launcher.databinding.FragmentAppDrawerBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * CRASH-SAFE VERSION
 *
 * Crash safety through:
 * - Nullable binding with proper cleanup
 * - Safe RecyclerView handling
 * - Try-catch around all UI operations
 * - Lifecycle-aware coroutines with error handling
 * - Safe dialog management
 * - Safe search functionality
 * - Defensive null checks throughout
 */
@AndroidEntryPoint
class AppDrawerFragment : Fragment(R.layout.fragment_app_drawer) {

    private val viewModel: HomeViewModel by activityViewModels()

    // CRASH-SAFE: Nullable binding
    private var _binding: FragmentAppDrawerBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var appUsageManager: AppUsageRepository

    private lateinit var appDrawerAdapter: AppDrawerAdapter
    private var masterAppList: List<AppInfo> = emptyList()
    private var longClickedApp: AppInfo? = null
    private var currentDialog: DialogFragment? = null
    private var searchJob: Job? = null
    private var shouldScrollToTop = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppDrawerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            setupWindowInsets(view)
            setupRecyclerView()
            setupSearch()
            setupSortFab()
            observeViewModel()
            setupFragmentResultListener()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onViewCreated")
        }
    }

    private fun observeViewModel() {
        // Observer 1: App-Liste
        try {
            viewModel.drawerApps.observe(viewLifecycleOwner) { sortedApps ->
                try {
                    if (sortedApps != null) {
                        masterAppList = sortedApps
                        displayFilteredApps()
                    }
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error updating master app list")
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting up drawerApps observer")
        }

        // Observer 2: Sortierreihenfolge
        try {
            viewModel.sortOrder.observe(viewLifecycleOwner) { order ->
                if (_binding == null) return@observe

                try {
                    val iconRes = when (order) {
                        SortOrder.ALPHABETICAL -> android.R.drawable.ic_menu_sort_alphabetically
                        SortOrder.TIME_WEIGHTED_USAGE -> android.R.drawable.ic_menu_recent_history
                        null -> return@observe
                    }
                    binding.fabSort.setImageResource(iconRes)
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error updating sort icon")
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting up sortOrder observer")
        }

        // Observer 3: UI-Farben
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    viewModel.uiColorsState.collect { colors ->
                        if (_binding == null || !isAdded) return@collect

                        try {
                            if (::appDrawerAdapter.isInitialized) {
                                appDrawerAdapter.setUiColors(colors.textColor, colors.shadowColor)
                            }
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "Error updating adapter colors")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error in uiColorsState collection")
                }
            }
        }
    }

    private fun setupFragmentResultListener() {
        try {
            childFragmentManager.setFragmentResultListener(
                AppContextMenuDialogFragment.REQUEST_KEY,
                viewLifecycleOwner
            ) { _, bundle ->
                try {
                    val app = longClickedApp
                    if (app == null) {
                        Timber.w("Fragment result received but longClickedApp is null")
                        return@setFragmentResultListener
                    }

                    val action = try {
                        bundle.getString(AppContextMenuDialogFragment.RESULT_KEY_ACTION)
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error getting action from bundle")
                        null
                    }

                    when (action) {
                        "launch_shortcut" -> {
                            handleShortcutLaunch(bundle)
                        }
                        AppContextMenuAction.ACTION_ID_APP_INFO -> {
                            showAppInfo(app)
                        }
                        AppContextMenuAction.ACTION_ID_TOGGLE_FAVORITE -> {
                            toggleFavorite(app)
                        }
                        AppContextMenuAction.ACTION_ID_HIDE_APP -> {
                            hideApp(app)
                        }
                        AppContextMenuAction.ACTION_ID_RESET_USAGE -> {
                            resetAppUsage(app)
                        }
                    }
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error in fragment result listener")
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting up fragment result listener")
        }
    }

    private fun handleShortcutLaunch(bundle: Bundle) {
        try {
            val shortcut = try {
                bundle.getParcelable(
                    AppContextMenuDialogFragment.RESULT_KEY_SHORTCUT,
                    ShortcutInfo::class.java
                )
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error getting shortcut from bundle")
                null
            }

            if (shortcut == null) {
                viewModel.onAppInfoError()
                return
            }

            try {
                val launcherApps = requireContext()
                    .getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps

                if (launcherApps == null) {
                    TimberWrapper.silentError("LauncherApps service is null")
                    viewModel.onAppInfoError()
                    return
                }

                launcherApps.startShortcut(shortcut, null, null)
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error launching shortcut")
                viewModel.onAppInfoError()
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in handleShortcutLaunch")
        }
    }

    private fun toggleFavorite(app: AppInfo) {
        try {
            val state = viewModel.favoriteAppsState.value
            val currentFavoritesCount = if (state is UiState.Success) {
                state.data.apps.size
            } else {
                0
            }
            viewModel.onToggleFavorite(app, currentFavoritesCount)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error toggling favorite")
        }
    }

    private fun hideApp(app: AppInfo) {
        try {
            viewModel.onHideApp(app)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error hiding app")
        }
    }

    private fun showAppInfo(app: AppInfo) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", app.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error showing app info")
            viewModel.onAppInfoError()
        }
    }

    private fun resetAppUsage(app: AppInfo) {
        try {
            shouldScrollToTop = true
            viewModel.onResetAppUsage(app)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error resetting app usage")
        }
    }

    private fun setupRecyclerView() {
        try {
            appDrawerAdapter = AppDrawerAdapter(
                onAppClicked = { app ->
                    try {
                        Timber.d("AppDrawerFragment lambda called for ${app.displayName}")
                        viewModel.onAppClicked(app)
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error in app click")
                    }
                },
                onAppLongClicked = { app ->
                    try {
                        showAppContextMenu(app)
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error in app long click")
                    }
                }
            )

            binding.appsRecyclerView.apply {
                adapter = appDrawerAdapter
                layoutManager = LinearLayoutManager(requireContext())
                // CRASH-SAFE: Disable animations to prevent IllegalStateException
                itemAnimator = null
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting up RecyclerView")
        }
    }

    private fun setupSearch() {
        try {
            binding.searchEditText.doOnTextChanged { text, _, _, _ ->
                try {
                    searchJob?.cancel()

                    searchJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        try {
                            delay(300)
                            displayFilteredApps()
                        } catch (e: CancellationException) {
                            // Normal cancellation, ignore
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "Error in search delay")
                        }
                    }
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error in text changed listener")
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting up search")
        }
    }

    private fun setupSortFab() {
        try {
            binding.fabSort.setOnClickListener {
                try {
                    shouldScrollToTop = true
                    viewModel.toggleSortOrder()
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error toggling sort order")
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting up sort FAB")
        }
    }

    private fun displayFilteredApps() {
        val currentBinding = _binding
        if (currentBinding == null || !isAdded) {
            Timber.w("Cannot display filtered apps - binding is null or fragment not added")
            return
        }

        try {
            val query = try {
                currentBinding.searchEditText.text.toString()
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error getting search query")
                ""
            }

            val filteredList = try {
                if (query.isEmpty()) {
                    masterAppList
                } else {
                    masterAppList.filter { app ->
                        try {
                            app.displayName.contains(query, ignoreCase = true)
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "Error filtering app: ${app.packageName}")
                            false
                        }
                    }
                }
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error filtering apps")
                masterAppList
            }

            try {
                appDrawerAdapter.submitList(filteredList.toList()) {
                    try {
                        if (shouldScrollToTop && _binding != null && isAdded) {
                            currentBinding.appsRecyclerView.scrollToPosition(0)
                            shouldScrollToTop = false
                        }
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error scrolling to top")
                    }
                }
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error submitting list to adapter")
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in displayFilteredApps")
        }
    }

    private fun showAppContextMenu(app: AppInfo) {
        try {
            currentDialog?.dismissAllowingStateLoss()
            currentDialog = null

            longClickedApp = app

            viewLifecycleOwner.lifecycleScope.launch {   // ← KEIN Dispatchers.Main nötig
                try {
                    val hasUsage = try {
                        appUsageManager.hasUsageDataForPackage(app.packageName)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error checking usage data")
                        false
                    }

                    if (!isAdded || isDetached) return@launch

                    val dialog = AppContextMenuDialogFragment.newInstance(
                        app,
                        MenuContext.APP_DRAWER,
                        hasUsage
                    )
                    currentDialog = dialog
                    dialog.show(childFragmentManager, AppContextMenuDialogFragment.TAG)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error showing context menu")
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in showAppContextMenu")
        }
    }

    private fun setupWindowInsets(view: View) {
        try {
            val fabMargin = try {
                resources.getDimensionPixelSize(R.dimen.spacing_large)
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error getting fab margin")
                16 // Fallback value
            }

            val initialContentPadding = try {
                Rect(
                    binding.contentContainer.paddingLeft,
                    binding.contentContainer.paddingTop,
                    binding.contentContainer.paddingRight,
                    binding.contentContainer.paddingBottom
                )
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error getting initial padding")
                Rect(0, 0, 0, 0)
            }

            ViewCompat.setOnApplyWindowInsetsListener(binding.appDrawerRoot) { _, insets ->
                try {
                    val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

                    try {
                        binding.contentContainer.setPadding(
                            initialContentPadding.left,
                            initialContentPadding.top + systemBars.top,
                            initialContentPadding.right,
                            initialContentPadding.bottom + systemBars.bottom
                        )
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error setting content padding")
                    }

                    try {
                        val fabLayoutParams = binding.fabSort.layoutParams as? CoordinatorLayout.LayoutParams
                        if (fabLayoutParams != null) {
                            fabLayoutParams.bottomMargin = systemBars.bottom + fabMargin
                            binding.fabSort.layoutParams = fabLayoutParams
                        }
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error setting fab margin")
                    }

                    insets
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error applying window insets")
                    insets
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting up window insets")
        }
    }

    override fun onDestroyView() {
        try {
            searchJob?.cancel()
            searchJob = null

            currentDialog?.dismissAllowingStateLoss()
            currentDialog = null

            try {
                binding.appsRecyclerView.adapter = null
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error clearing adapter")
            }

            _binding = null
            longClickedApp = null
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onDestroyView")
        } finally {
            super.onDestroyView()
        }
    }
}