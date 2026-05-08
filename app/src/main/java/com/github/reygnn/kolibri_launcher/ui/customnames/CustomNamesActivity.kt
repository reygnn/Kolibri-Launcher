package com.github.reygnn.kolibri_launcher.ui.customnames

import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.databinding.ActivityCustomNamesBinding
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.ui.base.BaseActivity
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.flow.collectOnStarted
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.coroutines.EmptyCoroutineContext

/**
 * CRASH-SAFE VERSION
 *
 * Crashsicherheit durch:
 * - Nullable binding mit proper cleanup
 * - Lifecycle-aware coroutines
 * - Safe RecyclerView state restoration
 * - Defensive null checks
 * - Try-catch für alle kritischen Operationen
 * - Memory leak prevention
 * - Safe dialog handling
 */
@AndroidEntryPoint
class CustomNamesActivity : BaseActivity<UiEvent, CustomNamesViewModel>() {

    // CRASH-SAFE: Nullable binding
    private var _binding: ActivityCustomNamesBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("Binding accessed after onDestroy")

    override val viewModel: CustomNamesViewModel by viewModels()
    private var adapter: CustomNamesAdapter? = null
    private var currentDialog: AlertDialog? = null

    // Search Debouncing
    private val searchQueryFlow = MutableStateFlow("")

    private companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val STATE_SEARCH_QUERY = "search_query"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            _binding = ActivityCustomNamesBinding.inflate(layoutInflater)
            setContentView(binding.root)

            handleWindowInsets()
            setupRecyclerView()
            setupSearchListener()
            setupClickListeners()
            observeViewModelState()

            savedInstanceState?.getString(STATE_SEARCH_QUERY)?.let { query ->
                _binding?.let { binding ->
                    binding.searchEditText.setText(query)
                    binding.searchEditText.setSelection(query.length)
                }
            }

            // throw RuntimeException("ACRA Test Crash")

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Fatal error in onCreate")
            finish()
        }
    }

    internal fun initialize() {
        // Aktuell leer, aber vorhanden für Konsistenz und zukünftige Lade-Logik.
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.let {
            outState.putString(STATE_SEARCH_QUERY, it.searchEditText.text.toString())
        }
    }

    override fun onDestroy() {
        // Outer catch kept: lifecycle teardown — finally{} must always
        // reach super.onDestroy() even on a cleanup throw.
        try {
            currentDialog?.dismiss()
            currentDialog = null

            // GC-OPTIMIERUNG: Views leeren, um Listener-Referenzen zu kappen
            if (_binding != null) {
                binding.appNameChipGroup.removeAllViews()
            }

            adapter = null
            _binding = null
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onDestroy")
        } finally {
            super.onDestroy()
        }
    }

    private fun handleWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = insets.left,
                top = insets.top,
                right = insets.right,
                bottom = insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupRecyclerView() {
        adapter = CustomNamesAdapter { appInfo -> showRenameDialog(appInfo) }
        binding.allAppsRecyclerView.apply {
            adapter = this@CustomNamesActivity.adapter
            layoutManager = LinearLayoutManager(this@CustomNamesActivity)
            setHasFixedSize(true)
            // CRASH-SAFE: Verhindere IllegalStateException bei state restoration
            itemAnimator = null
        }
    }

    @OptIn(FlowPreview::class)
    private fun setupSearchListener() {
        binding.searchEditText.doOnTextChanged { text, _, _, _ ->
            searchQueryFlow.value = text?.toString() ?: ""
        }

        collectOnStarted(
            flow = searchQueryFlow.debounce(SEARCH_DEBOUNCE_MS),
            errorTag = "searchQuery",
            coroutineContext = EmptyCoroutineContext,
        ) { query ->
            viewModel.onSearchQueryChanged(query)
        }
    }

    private fun setupClickListeners() {
        binding.doneButton.setOnClickListener { finish() }
    }

    private fun observeViewModelState() {
        collectOnStarted(
            flow = viewModel.uiState,
            errorTag = "uiState",
            coroutineContext = EmptyCoroutineContext,
        ) { state ->
            updateUi(state)
        }
    }

    private fun updateUi(state: CustomNamesUiState) {
        // CRASH-SAFE: Check ob binding noch gültig ist
        if (_binding == null) {
            Timber.w("Attempted to update UI after binding was destroyed")
            return
        }
        adapter?.submitList(state.displayedApps) ?: run {
            Timber.w("Adapter is null, cannot submit list")
        }
        updateCustomNameChips(state.appsWithCustomNames)
    }

    private fun updateCustomNameChips(appsWithCustomNames: List<AppInfo>) {
        if (_binding == null) return
        binding.chipsScrollView.visibility = if (appsWithCustomNames.isEmpty()) View.INVISIBLE else View.VISIBLE
        binding.appNameChipGroup.removeAllViews()

        for (app in appsWithCustomNames) {
            val chip = Chip(this).apply {
                text = app.displayName
                isCloseIconVisible = true
                setOnCloseIconClickListener { viewModel.removeCustomName(app.packageName) }
            }
            binding.appNameChipGroup.addView(chip)
        }
    }

    private fun showRenameDialog(app: AppInfo) {
        // dismiss() can throw IllegalArgumentException if the dialog's view
        // is no longer attached to a window. Specific, ignorable.
        try {
            currentDialog?.dismiss()
        } catch (e: IllegalArgumentException) {
            // Dialog schon weg, ignorieren
        }
        currentDialog = null

        // Outer catch kept: AlertDialog.Builder + show() can fail when the
        // activity is finishing (BadTokenException, IllegalStateException)
        // and the showError fallback gives the user an explanation.
        try {
            val editText = EditText(this).apply {
                setText(app.displayName)
                setSelection(app.displayName.length)
                hint = getString(R.string.rename_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }

            currentDialog = MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.rename_dialog_title, app.originalName))
                .setView(editText)
                .setPositiveButton(R.string.save) { _, _ ->
                    val newName = editText.text.toString().trim()
                    handleRename(app, newName)
                }
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener {
                    // WICHTIG: Referenz sofort löschen
                    if (currentDialog?.isShowing == false) {
                        currentDialog = null
                    }
                }
                .create()

            currentDialog?.show()

            // Coroutine — the lifecycleScope binding is enough on its own;
            // a CancellationException on Activity death is forwarded by the
            // scope itself. IMM showSoftInput is EXTERNAL (system-service IPC),
            // hence the inner catch there.
            lifecycleScope.launch {
                kotlinx.coroutines.delay(100)
                if (currentDialog?.isShowing == true && editText.isAttachedToWindow) {
                    editText.requestFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                    try {
                        imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error showing keyboard in dialog")
                    }
                }
            }

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error creating rename dialog")
            showError(getString(R.string.error_generic))
        }
    }

    private fun handleRename(app: AppInfo, newName: String) {
        when (val decision = RenameDecision.decide(newName, app.originalName)) {
            RenameDecision.Remove -> viewModel.removeCustomName(app.packageName)
            is RenameDecision.TooLong ->
                showError(getString(R.string.error_name_too_long, decision.maxLength))
            is RenameDecision.Set -> viewModel.setCustomName(app.packageName, decision.name)
        }
    }

    private fun showError(message: String) {
        // Defensive Toast — Samsung IPC has been observed to throw
        // (see BaseActivity.showToastSafe).
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error showing toast")
        }
    }

    /**
     * Implements the abstract method from BaseActivity.
     * This screen's ViewModel only uses generic UiEvents (like ShowToast), which are already
     * handled in the BaseActivity. Therefore, this method can remain empty.
     */
    override fun handleSpecificEvent(event: UiEvent) {
        // No app-specific events are sent from AppNamesViewModel, so this is intentionally empty.
    }
}