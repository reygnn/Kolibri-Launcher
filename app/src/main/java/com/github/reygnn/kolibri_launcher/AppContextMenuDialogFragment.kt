package com.github.reygnn.kolibri_launcher

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.reygnn.kolibri_launcher.databinding.BottomSheetAppContextMenuBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * CRASH-SAFE VERSION
 *
 * Crash safety through:
 * - Try-catch around all suspend operations
 * - Safe dialog handling
 * - Lifecycle-aware coroutines with error handling
 * - Defensive null checks
 * - Safe fragment result handling
 * - Proper cleanup
 */
@AndroidEntryPoint
class AppContextMenuDialogFragment : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "AppContextMenu"
        const val REQUEST_KEY = "app_context_menu_request"
        const val RESULT_KEY_ACTION = "result_action"
        const val RESULT_KEY_SHORTCUT = "result_shortcut"
        private const val ARG_APP_INFO = "arg_app_info"
        private const val ARG_CONTEXT = "arg_context"
        private const val ARG_HAS_USAGE_DATA = "arg_has_usage_data"

        fun newInstance(
            appInfo: AppInfo,
            context: MenuContext,
            hasUsageData: Boolean
        ): AppContextMenuDialogFragment {
            return AppContextMenuDialogFragment().apply {
                arguments = bundleOf(
                    ARG_APP_INFO to appInfo,
                    ARG_CONTEXT to context,
                    ARG_HAS_USAGE_DATA to hasUsageData
                )
            }
        }
    }

    @Inject
    lateinit var favoritesManager: FavoritesRepository
    @Inject
    lateinit var visibilityManager: AppVisibilityRepository
    @Inject
    lateinit var shortcutManager: ShortcutRepository
    @Inject
    lateinit var appNamesManager: AppNamesRepository
    @Inject
    lateinit var installedAppsManager: InstalledAppsRepository

    // CRASH-SAFE: Nullable binding
    private var _binding: BottomSheetAppContextMenuBinding? = null
    private val binding get() = _binding!!

    private lateinit var appInfo: AppInfo
    private lateinit var menuContext: MenuContext
    private var hasUsageData: Boolean = false

    // CRASH-SAFE: Track current dialog
    private var currentDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            @Suppress("DEPRECATION")
            appInfo = requireArguments().getParcelable(ARG_APP_INFO)
                ?: run {
                    Timber.w("Dialog created without AppInfo, dismissing")
                    dismiss()
                    return
                }

            @Suppress("DEPRECATION")
            menuContext = requireArguments().getParcelable(ARG_CONTEXT)
                ?: MenuContext.HOME_SCREEN

            hasUsageData = requireArguments().getBoolean(ARG_HAS_USAGE_DATA, false)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onCreate")
            dismiss()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAppContextMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            binding.appNameText.text = appInfo.displayName

            val adapter = AppContextMenuAdapter { action ->
                try {
                    handleActionClick(action)
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error handling action click")
                }
            }

            binding.contextMenuItemsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
            binding.contextMenuItemsRecyclerView.adapter = adapter

            if (BuildConfig.DEBUG) {
                EspressoIdlingResource.increment()
            }

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val actions = loadActions()

                    if (!isAdded || isDetached) {
                        if (BuildConfig.DEBUG) EspressoIdlingResource.decrement()
                        return@launch
                    }

                    adapter.submitList(actions) {
                        if (BuildConfig.DEBUG) {
                            EspressoIdlingResource.decrement()
                        }
                    }
                } catch (e: CancellationException) {
                    if (BuildConfig.DEBUG) EspressoIdlingResource.decrement()
                    throw e
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error loading actions")
                    if (BuildConfig.DEBUG) EspressoIdlingResource.decrement()

                    // Dismiss bei kritischem Fehler
                    try {
                        dismiss()
                    } catch (dismissError: Exception) {
                        TimberWrapper.silentError(dismissError, "Error dismissing after load error")
                    }
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onViewCreated")
            dismiss()
        }
    }

    private suspend fun loadActions(): List<AppContextMenuAction> {
        val actions = mutableListOf<AppContextMenuAction>()

        // CRASH-SAFE: Load shortcuts with error handling
        try {
            val shortcuts = try {
                shortcutManager.getShortcutsForPackage(appInfo.packageName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error loading shortcuts for ${appInfo.packageName}")
                emptyList()
            }

            shortcuts.forEach { shortcutInfo ->
                try {
                    actions.add(AppContextMenuAction.Shortcut(shortcutInfo))
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error adding shortcut action")
                }
            }

            if (actions.isNotEmpty()) {
                actions.add(AppContextMenuAction.Separator)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error processing shortcuts")
        }

        // CRASH-SAFE: Check favorite status
        try {
            val isFavorite = try {
                favoritesManager.isFavoriteComponent(appInfo.componentName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error checking favorite status")
                false
            }

            actions.add(
                AppContextMenuAction.LauncherAction(
                    id = AppContextMenuAction.ACTION_ID_TOGGLE_FAVORITE,
                    label = if (isFavorite) {
                        getString(R.string.remove_from_favorites)
                    } else {
                        getString(R.string.add_to_favorites)
                    }
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error adding favorite action")
        }

        // CRASH-SAFE: Check custom name
        try {
            val hasCustomName = try {
                appNamesManager.hasCustomNameForPackage(appInfo.packageName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error checking custom name")
                false
            }

            if (hasCustomName) {
                actions.add(
                    AppContextMenuAction.LauncherAction(
                        id = AppContextMenuAction.ACTION_ID_RESTORE_NAME,
                        label = getString(R.string.restore_original_name)
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error adding restore name action")
        }

        // Rename action
        try {
            actions.add(
                AppContextMenuAction.LauncherAction(
                    id = AppContextMenuAction.ACTION_ID_RENAME_APP,
                    label = getString(R.string.rename_app)
                )
            )
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error adding rename action")
        }

        // CRASH-SAFE: Check hidden status
        try {
            val isHidden = try {
                visibilityManager.isComponentHidden(appInfo.componentName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error checking hidden status")
                false
            }

            actions.add(
                AppContextMenuAction.LauncherAction(
                    id = if (isHidden) {
                        AppContextMenuAction.ACTION_ID_UNHIDE_APP
                    } else {
                        AppContextMenuAction.ACTION_ID_HIDE_APP
                    },
                    label = if (isHidden) {
                        getString(R.string.unhide_app_in_drawer)
                    } else {
                        getString(R.string.hide_app_from_drawer)
                    }
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error adding hide/unhide action")
        }

        // Reset usage (only in drawer)
        try {
            if (menuContext == MenuContext.APP_DRAWER && hasUsageData) {
                actions.add(
                    AppContextMenuAction.LauncherAction(
                        id = AppContextMenuAction.ACTION_ID_RESET_USAGE,
                        label = getString(R.string.action_reset_sorting)
                    )
                )
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error adding reset usage action")
        }

        // App Info
        try {
            actions.add(
                AppContextMenuAction.LauncherAction(
                    id = AppContextMenuAction.ACTION_ID_APP_INFO,
                    label = getString(R.string.app_info)
                )
            )
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error adding app info action")
        }

        return actions
    }

    private fun handleActionClick(action: AppContextMenuAction) {
        // CRASH-SAFE: Check fragment state
        if (!isAdded || isStateSaved) {
            Timber.w("handleActionClick called in invalid state")
            return
        }

        try {
            when (action) {
                is AppContextMenuAction.Shortcut -> {
                    try {
                        setFragmentResult(REQUEST_KEY, bundleOf(
                            RESULT_KEY_ACTION to "launch_shortcut",
                            RESULT_KEY_SHORTCUT to action.shortcutInfo
                        ))
                        dismiss()
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error handling shortcut action")
                    }
                }
                is AppContextMenuAction.LauncherAction -> {
                    when (action.id) {
                        AppContextMenuAction.ACTION_ID_RENAME_APP -> {
                            showRenameDialog()
                            return
                        }
                        AppContextMenuAction.ACTION_ID_RESTORE_NAME -> {
                            viewLifecycleOwner.lifecycleScope.launch {
                                try {
                                    appNamesManager.removeCustomNameForPackage(appInfo.packageName)
                                    dismiss()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    TimberWrapper.silentError(e, "Error removing custom name")
                                    dismiss()
                                }
                            }
                        }
                        else -> {
                            try {
                                setFragmentResult(REQUEST_KEY, bundleOf(RESULT_KEY_ACTION to action.id))
                                dismiss()
                            } catch (e: Exception) {
                                TimberWrapper.silentError(e, "Error handling launcher action")
                            }
                        }
                    }
                }
                is AppContextMenuAction.Separator -> return
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in handleActionClick")
        }
    }

    private fun showRenameDialog() {
        // CRASH-SAFE: Dismiss previous dialog
        try {
            currentDialog?.dismiss()
            currentDialog = null
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error dismissing previous dialog")
        }

        val ctx = context
        if (ctx == null) {
            Timber.w("Context is null, cannot show rename dialog")
            return
        }

        try {
            val editText = EditText(ctx).apply {
                setText(appInfo.displayName)
                setHint(R.string.new_app_name_hint)
            }

            currentDialog = AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.rename_app_title, appInfo.displayName))
                .setView(editText)
                .setPositiveButton(R.string.rename) { _, _ ->
                    try {
                        val newName = editText.text.toString().trim()

                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                if (newName.isNotBlank() && newName != appInfo.originalName) {
                                    appNamesManager.setCustomNameForPackage(
                                        appInfo.packageName,
                                        newName
                                    )
                                } else {
                                    appNamesManager.removeCustomNameForPackage(appInfo.packageName)
                                }
                                dismiss()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                TimberWrapper.silentError(e, "Error setting custom name")
                                dismiss()
                            }
                        }
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error in rename positive button")
                    }
                }
                .setNegativeButton(R.string.cancel) { dialog, _ ->
                    try {
                        dialog.cancel()
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error canceling dialog")
                    }
                }
                .setOnDismissListener {
                    try {
                        if (currentDialog?.isShowing == false) {
                            currentDialog = null
                        }
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error in dismiss listener")
                    }
                }
                .create()

            currentDialog?.show()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error creating rename dialog")
        }
    }

    override fun onDestroyView() {
        try {
            // CRASH-SAFE: Cleanup dialog
            currentDialog?.dismiss()
            currentDialog = null

            _binding = null
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onDestroyView")
        } finally {
            super.onDestroyView()
        }
    }
}