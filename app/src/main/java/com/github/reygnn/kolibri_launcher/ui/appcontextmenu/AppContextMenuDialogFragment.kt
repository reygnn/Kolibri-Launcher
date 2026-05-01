package com.github.reygnn.kolibri_launcher.ui.appcontextmenu

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
import com.github.reygnn.kolibri_launcher.BuildConfig
import com.github.reygnn.kolibri_launcher.EspressoIdlingResource
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.databinding.BottomSheetAppContextMenuBinding
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.MenuContext
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.BuildAppContextMenuUseCase
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * CRASH-SAFE VERSION + SAMSUNG FIX
 *
 * Crash safety through:
 * - Try-catch around all suspend operations
 * - Safe dialog handling
 * - Lifecycle-aware coroutines with error handling
 * - Defensive null checks
 * - Safe fragment result handling
 * - Proper cleanup
 * - Dispatchers.IO for loading actions (Fixes Samsung Knox StrictMode violation)
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

    // appNamesManager is still used by handleActionClick for the rename
    // and restore-original-name flows. The other three repositories that
    // used to live here (favorites, hidden, shortcut) moved into
    // BuildAppContextMenuUseCase.
    @Inject
    lateinit var customNamesRepository: CustomNamesRepository

    @Inject
    lateinit var buildAppContextMenuUseCase: BuildAppContextMenuUseCase

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

        binding.appNameText.text = appInfo.displayName

        val adapter = AppContextMenuAdapter { action -> handleActionClick(action) }

        binding.contextMenuItemsRecyclerView.layoutManager =
            LinearLayoutManager(requireContext())
        binding.contextMenuItemsRecyclerView.adapter = adapter

        if (BuildConfig.DEBUG) {
            EspressoIdlingResource.increment()
        }

        // Starts on Main, but we switch inside.
        // Inner catch kept: loadActions() runs the use case which talks to
        // multiple repositories (Samsung Knox via PackageManager included);
        // dismiss() on failure gives the user an exit instead of an empty
        // dialog.
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // FIX: Move heavy lifting (and Samsung DB reads) to IO thread
                val actions = withContext(Dispatchers.IO) {
                    loadActions()
                }

                // Back on Main Thread here
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
                dismiss()
            }
        }
    }

    /**
     * Loads actions. Safe to be called on IO thread — only repository
     * calls, no Views. Logic moved to [BuildAppContextMenuUseCase] so
     * the per-state branching is testable on the JVM.
     */
    private suspend fun loadActions(): List<AppContextMenuAction> {
        return buildAppContextMenuUseCase(
            appInfo = appInfo,
            menuContext = menuContext,
            hasUsageData = hasUsageData,
        )
    }

    private fun handleActionClick(action: AppContextMenuAction) {
        // CRASH-SAFE: Check fragment state
        if (!isAdded || isStateSaved) {
            Timber.w("handleActionClick called in invalid state")
            return
        }

        when (action) {
            is AppContextMenuAction.Shortcut -> {
                setFragmentResult(REQUEST_KEY, bundleOf(
                    RESULT_KEY_ACTION to "launch_shortcut",
                    RESULT_KEY_SHORTCUT to action.shortcutInfo
                ))
                dismiss()
            }
            is AppContextMenuAction.LauncherAction -> {
                when (action.id) {
                    AppContextMenuAction.Companion.ACTION_ID_RENAME_APP -> {
                        showRenameDialog()
                        return
                    }
                    AppContextMenuAction.Companion.ACTION_ID_RESTORE_NAME -> {
                        // EXPECTED: customNamesRepository call is suspend
                        // (DataStore I/O); on failure we still dismiss to
                        // give the user an exit.
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                customNamesRepository.removeCustomNameForPackage(appInfo.packageName)
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
                        setFragmentResult(REQUEST_KEY, bundleOf(RESULT_KEY_ACTION to action.id))
                        dismiss()
                    }
                }
            }
            is AppContextMenuAction.Separator -> return
        }
    }

    private fun showRenameDialog() {
        // dismiss() can throw IllegalArgumentException if the dialog's view
        // is no longer attached to a window — specific, ignorable.
        try {
            currentDialog?.dismiss()
        } catch (e: IllegalArgumentException) {
            // Dialog already gone, ignore
        }
        currentDialog = null

        val ctx = context
        if (ctx == null) {
            Timber.w("Context is null, cannot show rename dialog")
            return
        }

        // Outer catch kept: AlertDialog.Builder + show() can throw
        // BadTokenException / IllegalStateException when the activity is
        // finishing.
        try {
            val editText = EditText(ctx).apply {
                setText(appInfo.displayName)
                setHint(R.string.new_app_name_hint)
            }

            currentDialog = AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.rename_app_title, appInfo.displayName))
                .setView(editText)
                .setPositiveButton(R.string.rename) { _, _ ->
                    val newName = editText.text.toString().trim()
                    // Inner launch catch kept: customNamesRepository
                    // calls are suspend (DataStore I/O); dismiss on
                    // failure gives the user an exit.
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            if (newName.isNotBlank() && newName != appInfo.originalName) {
                                customNamesRepository.setCustomNameForPackage(
                                    appInfo.packageName,
                                    newName
                                )
                            } else {
                                customNamesRepository.removeCustomNameForPackage(appInfo.packageName)
                            }
                            dismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "Error setting custom name")
                            dismiss()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
                .setOnDismissListener {
                    if (currentDialog?.isShowing == false) {
                        currentDialog = null
                    }
                }
                .create()

            currentDialog?.show()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error creating rename dialog")
        }
    }

    override fun onDestroyView() {
        // Outer catch kept: lifecycle teardown — finally{} super.onDestroyView()
        try {
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