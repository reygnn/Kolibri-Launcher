/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import com.github.reygnn.kolibri_launcher.databinding.FragmentHomeBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.abs

/**
 * CRASH-SAFE VERSION
 *
 * Crash safety through:
 * - Nullable binding with proper cleanup
 * - Safe GestureDetector handling
 * - Try-catch around all UI operations
 * - Safe View creation and manipulation
 * - Lifecycle-aware coroutines with error handling
 * - Safe dialog management
 * - Defensive null checks throughout
 * - Safe fragment result listener
 */
@AndroidEntryPoint
class HomeFragment : Fragment() {

    private val viewModel: HomeViewModel by activityViewModels()

    // CRASH-SAFE: Nullable binding
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // CRASH-SAFE: Nullable GestureDetector
    private var gestureDetector: GestureDetector? = null
    private var longClickedApp: AppInfo? = null
    private var currentDialog: DialogFragment? = null

    @Inject
    lateinit var favoritesManager: FavoritesRepository
    @Inject
    lateinit var visibilityManager: AppVisibilityRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            setupGestures()
            setupDoubleTapActions()
            observeViewModel()
            setupFragmentResultListener()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onViewCreated")
        }
    }

    private fun observeViewModel() {
        // Observer 1: Favoriten-Liste
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    viewModel.favoriteAppsState.collect { state ->
                        if (_binding == null) return@collect

                        Timber.d("HomeFragment received FAV state: ${state::class.simpleName}")

                        try {
                            when (state) {
                                is UiState.Loading -> {
                                    safelyRemoveAllViews()
                                }
                                is UiState.Success -> {
                                    val colors = viewModel.uiColorsState.value
                                    updateFavoriteAppsUI(
                                        state.data.apps,
                                        colors.textColor,
                                        colors.shadowColor
                                    )
                                }
                                is UiState.Error -> {
                                    viewModel.onFavoriteAppsError(state.message)
                                    safelyRemoveAllViews()
                                }
                            }
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "Error handling favorite apps state")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error collecting favoriteAppsState")
                }
            }
        }

        // Observer 2: Zeit, Datum, Batterie
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    viewModel.uiState.collect { state ->
                        if (_binding == null) return@collect

                        try {
                            binding.timeText.text = state.timeString
                            binding.dateText.text = state.dateString
                            binding.batteryText.text = state.batteryString
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "Error updating UI text")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error collecting uiState")
                }
            }
        }

        // Observer 3: UI-Farben
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    viewModel.uiColorsState.collect { colors ->
                        if (_binding == null) return@collect

                        try {
                            updateTextColors(colors.textColor, colors.shadowColor)
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "Error updating colors")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error collecting uiColorsState")
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
                            viewModel.onHideApp(app)
                        }
                        AppContextMenuAction.ACTION_ID_UNHIDE_APP -> {
                            viewModel.onShowApp(app)
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
                Timber.w("Shortcut is null")
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

    private fun updateTextColors(textColor: Int, shadowColor: Int) {
        if (_binding == null) return

        try {
            binding.timeText.setTextColor(textColor)
            binding.timeText.setShadowLayer(
                AppConstants.SHADOW_RADIUS_TIME,
                AppConstants.SHADOW_DX,
                AppConstants.SHADOW_DY,
                shadowColor
            )
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error updating time text color")
        }

        try {
            binding.dateText.setTextColor(textColor)
            binding.dateText.setShadowLayer(
                AppConstants.SHADOW_RADIUS_DATE,
                AppConstants.SHADOW_DX_SMALL,
                AppConstants.SHADOW_DY_SMALL,
                shadowColor
            )
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error updating date text color")
        }

        try {
            binding.batteryText.setTextColor(textColor)
            binding.batteryText.setShadowLayer(
                AppConstants.SHADOW_RADIUS_BATTERY,
                AppConstants.SHADOW_DX_SMALL,
                AppConstants.SHADOW_DY_SMALL,
                shadowColor
            )
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error updating battery text color")
        }

        updateFavoriteAppsColors(textColor, shadowColor)
    }

    private fun updateFavoriteAppsColors(textColor: Int, shadowColor: Int) {
        if (_binding == null) return

        try {
            for (i in 0 until binding.favoriteAppsContainer.childCount) {
                try {
                    val view = binding.favoriteAppsContainer.getChildAt(i)
                    if (view is Button) {
                        view.setTextColor(textColor)
                        view.setShadowLayer(
                            AppConstants.SHADOW_RADIUS_APPS,
                            AppConstants.SHADOW_DX,
                            AppConstants.SHADOW_DY,
                            shadowColor
                        )
                    }
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error updating color for child at index $i")
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error updating favorite apps colors")
        }
    }

    private fun safelyRemoveAllViews() {
        try {
            if (_binding != null && isAdded && !isDetached) {
                binding.favoriteAppsContainer.removeAllViews()
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error removing all views")
        }
    }

    private fun updateFavoriteAppsUI(
        appsToShow: List<AppInfo>,
        textColor: Int,
        shadowColor: Int
    ) {
        if (_binding == null) return

        val ctx = context
        if (ctx == null) {
            Timber.w("Context is null, cannot update favorite apps UI")
            return
        }

        try {
            safelyRemoveAllViews()

            for (app in appsToShow) {
                try {
                    val appButton = createAppButton(ctx, app, textColor, shadowColor)
                    if (appButton != null) {
                        binding.favoriteAppsContainer.addView(appButton)
                    }
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error creating/adding button for ${app.packageName}")
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error updating favorite apps UI")
        }
    }

    private fun createAppButton(
        context: Context,
        app: AppInfo,
        textColor: Int,
        shadowColor: Int
    ): Button? {
        return try {
            Button(context).apply {
                text = app.displayName
                background = null
                setPadding(0, 12, 0, 12)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(textColor)

                try {
                    val buttonTextSizeInPx = resources.getDimension(R.dimen.text_size_app_button)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, buttonTextSizeInPx)
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error setting text size")
                }

                setShadowLayer(
                    AppConstants.SHADOW_RADIUS_APPS,
                    AppConstants.SHADOW_DX,
                    AppConstants.SHADOW_DY,
                    shadowColor
                )

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 8)
                }

                setOnClickListener {
                    try {
                        viewModel.onAppClicked(app)
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error in app click for ${app.packageName}")
                    }
                }

                setOnLongClickListener {
                    try {
                        showAppContextMenu(app)
                        true
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error in long click for ${app.packageName}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error creating app button for ${app.packageName}")
            null
        }
    }

    private fun showAppContextMenu(app: AppInfo) {
        try {
            currentDialog?.dismissAllowingStateLoss()
            currentDialog = null

            longClickedApp = app
            val dialog = AppContextMenuDialogFragment.newInstance(
                app,
                MenuContext.HOME_SCREEN,
                false
            )
            currentDialog = dialog
            dialog.show(childFragmentManager, AppContextMenuDialogFragment.TAG)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error showing app context menu")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        try {
            gestureDetector = GestureDetector(requireContext(), createGestureListener())

            binding.rootLayout.setOnTouchListener { _, event ->
                try {
                    gestureDetector?.onTouchEvent(event) ?: false
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error in touch listener")
                    false
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting up gestures")
        }
    }

    private fun createGestureListener() = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onLongPress(e: MotionEvent) {
            try {
                viewModel.onLongPress()
            } catch (ex: Exception) {
                TimberWrapper.silentError(ex, "Error in long press")
            }
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            try {
                viewModel.onDoubleTapToLock()
                return true
            } catch (ex: Exception) {
                TimberWrapper.silentError(ex, "Error in double tap")
                return false
            }
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            vX: Float,
            vY: Float
        ): Boolean {
            if (e1 == null) return false

            return try {
                val diffY = e2.y - e1.y
                if (abs(diffY) > AppConstants.SWIPE_THRESHOLD &&
                    abs(vY) > AppConstants.SWIPE_VELOCITY_THRESHOLD &&
                    diffY < 0) {
                    viewModel.onFlingUp()
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error in fling")
                false
            }
        }
    }

    private fun setupDoubleTapActions() {
        try {
            binding.timeText.setOnClickListener(object : DoubleClickListener() {
                override fun onDoubleClick() {
                    try {
                        viewModel.onTimeDoubleClick()
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error in time double click")
                    }
                }
            })
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting time click listener")
        }

        try {
            binding.dateText.setOnClickListener(object : DoubleClickListener() {
                override fun onDoubleClick() {
                    try {
                        viewModel.onDateDoubleClick()
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error in date double click")
                    }
                }
            })
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting date click listener")
        }

        try {
            binding.batteryText.setOnClickListener(object : DoubleClickListener() {
                override fun onDoubleClick() {
                    try {
                        viewModel.onBatteryDoubleClick()
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error in battery double click")
                    }
                }
            })
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting battery click listener")
        }
    }

    abstract class DoubleClickListener : View.OnClickListener {
        private var lastClickTime: Long = 0

        override fun onClick(v: View?) {
            try {
                val clickTime = System.currentTimeMillis()
                if (clickTime - lastClickTime < AppConstants.DOUBLE_CLICK_THRESHOLD) {
                    onDoubleClick()
                }
                lastClickTime = clickTime
            } catch (e: Exception) {
                TimberWrapper.silentError("Error in DoubleClickListener")
            }
        }

        abstract fun onDoubleClick()
    }

    override fun onDestroyView() {
        try {
            currentDialog?.dismissAllowingStateLoss()
            currentDialog = null

            gestureDetector = null
            longClickedApp = null

            _binding = null
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onDestroyView")
        } finally {
            super.onDestroyView()
        }
    }
}