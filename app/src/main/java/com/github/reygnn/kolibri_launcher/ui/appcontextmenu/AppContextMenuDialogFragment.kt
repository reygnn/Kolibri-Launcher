package com.github.reygnn.kolibri_launcher.ui.appcontextmenu
import com.github.reygnn.kolibri_launcher.domain.model.AppContextMenuAction

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import android.view.Window
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.github.reygnn.kolibri_launcher.domain.model.LuminanceClassification
import com.github.reygnn.kolibri_launcher.domain.model.ResolvedBackground
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.BuildAppContextMenuUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ResolveWallpaperSurfaceUseCase
import com.github.reygnn.kolibri_launcher.ui.customnames.RenameDecision
import com.github.reygnn.kolibri_launcher.ui.flow.collectOnStarted
import com.github.reygnn.kolibri_launcher.ui.util.AppInfoParcelable
import com.github.reygnn.kolibri_launcher.ui.util.toParcelable
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
                arguments = Bundle().apply {
                    putParcelable(ARG_APP_INFO, appInfo.toParcelable())
                    putString(ARG_CONTEXT, context.name)
                    putBoolean(ARG_HAS_USAGE_DATA, hasUsageData)
                }
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

    @Inject
    lateinit var resolveWallpaperSurfaceUseCase: ResolveWallpaperSurfaceUseCase

    // CRASH-SAFE: Nullable binding
    private var _binding: BottomSheetAppContextMenuBinding? = null
    private val binding get() = _binding!!

    private lateinit var appInfo: AppInfo
    private lateinit var menuContext: MenuContext
    private var hasUsageData: Boolean = false

    // CRASH-SAFE: Track current dialog
    private var currentDialog: AlertDialog? = null

    /**
     * Last surface classification emitted by
     * `resolveWallpaperSurfaceUseCase`. Cached so the rename dialog —
     * built synchronously from a click listener, not in a coroutine —
     * can read it without re-subscribing to the flow. Null until the
     * first emission.
     */
    private var currentClassification: LuminanceClassification? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            appInfo = requireArguments()
                .getParcelable(ARG_APP_INFO, AppInfoParcelable::class.java)
                ?.toAppInfo()
                ?: run {
                    Timber.w("Dialog created without AppInfo, dismissing")
                    dismiss()
                    return
                }

            menuContext = requireArguments().getString(ARG_CONTEXT)
                ?.let { MenuContext.valueOf(it) }
                ?: MenuContext.HOME_SCREEN

            hasUsageData = requireArguments().getBoolean(ARG_HAS_USAGE_DATA, false)
        } catch (e: Throwable) {
            // Outer Catchall kept: Bundle parsing surface (requireArguments,
            // getParcelable, MenuContext.valueOf) at the system-callback
            // boundary of Fragment.onCreate. §9.15-Sweep widened to Throwable
            // for consistency with the rest of the file.
            TimberWrapper.silentError(e, "Error in onCreate")
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        // Each Dialog is a separate Window from the host Activity, so the
        // host's status-bar hide does not reach it. We mirror whatever the
        // host currently shows: HomeFragment runs immersive, AppDrawer
        // shows the status bar — the menu must match either way, otherwise
        // the bar pops in (HomeFragment) or out (AppDrawer) on open.
        dialog?.window?.let(::matchHostStatusBarOn)
    }

    private fun matchHostStatusBarOn(window: Window) {
        if (!isHostStatusBarHidden()) return
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun isHostStatusBarHidden(): Boolean {
        val hostWindow = activity?.window ?: return false
        val insets = ViewCompat.getRootWindowInsets(hostWindow.decorView) ?: return false
        return !insets.isVisible(WindowInsetsCompat.Type.statusBars())
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

        // Wallpaper-following surface. Long-press menu hovers directly
        // over the homescreen wallpaper, so the same LuminanceClassification
        // that drives AppDrawer + Home text colour drives this dialog's
        // surface too. The Material3 bottom-sheet drawable (rounded
        // top corners) sits above `binding.root`, so the body-level
        // colour is set on the root and the Material3 layer's tint is
        // refreshed from the same colour to keep the rounded corners
        // matching the body. Text colour follows the WCAG-based
        // foregroundColor() so labels stay legible on either surface.
        collectOnStarted(
            flow = resolveWallpaperSurfaceUseCase(),
            errorTag = "wallpaperSurface",
            coroutineContext = Dispatchers.Main,
        ) { classification ->
            if (_binding == null) return@collectOnStarted
            currentClassification = classification
            val surface = ResolvedBackground.SolidColor(
                color = ContextCompat.getColor(
                    requireContext(),
                    when (classification) {
                        LuminanceClassification.LIGHT -> R.color.app_drawer_surface_light
                        LuminanceClassification.DARK -> R.color.app_drawer_surface_dark
                    },
                ),
            )
            val fg = surface.foregroundColor()
            binding.root.setBackgroundColor(surface.color)
            binding.appNameText.setTextColor(fg)
            adapter.setActionTextColor(fg)
            // Tint the Material3 bottom-sheet container so the rounded
            // top corners match the body colour. Without this the body
            // is wallpaper-aware but the rounded edge stays in the
            // Material3 day/night palette and looks disconnected.
            dialog?.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )?.backgroundTintList = ColorStateList.valueOf(surface.color)
        }

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
            } catch (e: Throwable) {
                // Outer Catchall kept: BuildAppContextMenuUseCase reaches
                // through 4 repositories (Samsung Knox PackageManager
                // included). §9.15-Sweep widened from Exception to Throwable.
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
                setFragmentResult(REQUEST_KEY, Bundle().apply {
                    putString(RESULT_KEY_ACTION, "launch_shortcut")
                    putParcelable(RESULT_KEY_SHORTCUT, action.shortcut.toParcelable())
                })
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
                            } catch (e: Throwable) {
                                // §9.15-Sweep: DataStore I/O can throw I/O
                                // failures, OOMs propagate the same way.
                                TimberWrapper.silentError(e, "Error removing custom name")
                                dismiss()
                            }
                        }
                    }
                    else -> {
                        setFragmentResult(
                            REQUEST_KEY,
                            Bundle().apply { putString(RESULT_KEY_ACTION, action.id) },
                        )
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

        // Outer catch kept: AlertDialog.Builder + EditText alloc + show()
        // can throw BadTokenException / IllegalStateException when the
        // activity is finishing, AND the View/Dialog inflation chain may
        // OutOfMemoryError on low-memory devices. OOM extends Error/
        // Throwable, NOT Exception — same pattern as §9.8 ZoomableImageView
        // and §9.13 BackupRepositoryImpl.
        try {
            val editText = EditText(ctx).apply {
                setText(appInfo.displayName)
                setHint(R.string.new_app_name_hint)
            }

            currentDialog = MaterialAlertDialogBuilder(ctx)
                .setTitle(getString(R.string.rename_app_title, appInfo.displayName))
                .setView(editText)
                .setPositiveButton(R.string.rename) { _, _ ->
                    val newName = editText.text.toString().trim()
                    // Inner launch catch kept: customNamesRepository
                    // calls are suspend (DataStore I/O); dismiss on
                    // failure gives the user an exit.
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            // Route through the same tested decision the CustomNames
                            // screen uses, so both entry points share one path
                            // (incl. the MAX_APP_NAME_LENGTH cap).
                            when (val decision =
                                RenameDecision.decide(newName, appInfo.originalName)) {
                                RenameDecision.Remove -> {
                                    customNamesRepository.removeCustomNameForPackage(appInfo.packageName)
                                    dismiss()
                                }
                                is RenameDecision.Set -> {
                                    customNamesRepository.setCustomNameForPackage(
                                        appInfo.packageName,
                                        decision.name
                                    )
                                    dismiss()
                                }
                                is RenameDecision.TooLong ->
                                    Toast.makeText(
                                        ctx,
                                        getString(R.string.error_name_too_long, decision.maxLength),
                                        Toast.LENGTH_SHORT
                                    ).show()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
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
            // Same window-mirror treatment as the BottomSheet itself —
            // the AlertDialog is yet another Window, must match the host.
            currentDialog?.window?.let(::matchHostStatusBarOn)
            // Wallpaper-following: the rename dialog hovers inside the
            // already-wallpaper-aware sheet. Tint after show() so the
            // AlertDialog's framework views (title, buttons, decor
            // background) exist and can be looked up.
            currentDialog?.let { tintRenameDialog(it, editText) }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error creating rename dialog")
        }
    }

    /**
     * Programmatically tint an [AlertDialog] to follow the same
     * surface classification as the host sheet. Uses the cached
     * [currentClassification] — no-op until the first emission has
     * arrived. The corner radius of the decor's framework drawable is
     * preserved because we call `setTint` rather than swapping the
     * drawable.
     *
     * The lookup uses framework id `android.R.id.title`, which is a
     * documented AlertDialog convention. If a future Android release
     * renames it, the title stays on its Material3 day/night colour —
     * the dialog still works, just visually mismatched.
     */
    private fun tintRenameDialog(dialog: AlertDialog, editText: EditText) {
        val classification = currentClassification ?: return
        val surface = ResolvedBackground.SolidColor(
            color = ContextCompat.getColor(
                requireContext(),
                when (classification) {
                    LuminanceClassification.LIGHT -> R.color.app_drawer_surface_light
                    LuminanceClassification.DARK -> R.color.app_drawer_surface_dark
                },
            ),
        )
        val fg = surface.foregroundColor()
        dialog.window?.decorView?.background?.setTint(surface.color)
        @Suppress("DEPRECATION")
        dialog.findViewById<android.widget.TextView>(android.R.id.title)?.setTextColor(fg)
        editText.setTextColor(fg)
        // Hint at 60% alpha keeps it readable but visibly secondary —
        // mirrors Material3's `colorOnSurfaceVariant` convention.
        editText.setHintTextColor((fg and 0x00FFFFFF) or 0x99000000.toInt())
        // EditText's underline indicator pulls from the same colour so
        // the focus line stays visible across surfaces.
        editText.backgroundTintList = ColorStateList.valueOf(fg)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(fg)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(fg)
    }

    override fun onDestroyView() {
        // Outer Catchall kept: lifecycle teardown race-guard. §9.15-Sweep
        // widened to Throwable per Rule 11 four-category-frame (teardown
        // race needs broad catch). `finally{}` always reaches super.
        try {
            currentDialog?.dismiss()
            currentDialog = null
            _binding = null
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onDestroyView")
        } finally {
            super.onDestroyView()
        }
    }
}
