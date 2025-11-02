package com.github.reygnn.kolibri_launcher.ui

import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.SwipeActionsAppListAdapter
import com.github.reygnn.kolibri_launcher.SwipeActionsUiState
import com.github.reygnn.kolibri_launcher.ui.SwipeActionsViewModel
import com.github.reygnn.kolibri_launcher.SwipeSlot
import com.github.reygnn.kolibri_launcher.TimberWrapper
import com.github.reygnn.kolibri_launcher.UiEvent
import com.github.reygnn.kolibri_launcher.databinding.ActivitySwipeActionsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class SwipeActionsActivity : BaseActivity<UiEvent, SwipeActionsViewModel>() {

    // Verwende das neue Binding
    private var _binding: ActivitySwipeActionsBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("Binding accessed after onDestroy")

    override val viewModel: SwipeActionsViewModel by viewModels()

    // Verwende den neuen Adapter
    private var appSelectionAdapter: SwipeActionsAppListAdapter? = null

    // Search Debouncing
    private val searchQueryFlow = MutableStateFlow("")
    private companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val STATE_SEARCH_QUERY = "search_query"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.initialize()

        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            // Inflate das neue Layout
            _binding = ActivitySwipeActionsBinding.inflate(layoutInflater)
            setContentView(binding.root)

            handleWindowInsets()
            setupRecyclerView()
            setupSearchListener()
            setupClickListeners()
            observeViewModel()

            savedInstanceState?.getString(STATE_SEARCH_QUERY)?.let { query ->
                binding.searchEditText.setText(query)
                binding.searchEditText.setSelection(query.length)
            }

            // throw RuntimeException("ACRA Test Crash")

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Fatal error in onCreate")
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            _binding?.let {
                outState.putString(STATE_SEARCH_QUERY, it.searchEditText.text.toString())
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error saving instance state")
        }
    }

    override fun onDestroy() {
        try {
            appSelectionAdapter = null
            _binding = null
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onDestroy")
        } finally {
            super.onDestroy()
        }
    }

    private fun handleWindowInsets() {
        try {
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
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error handling window insets")
        }
    }

    private fun setupRecyclerView() {
        try {
            // Initialisiere den neuen Adapter
            appSelectionAdapter = SwipeActionsAppListAdapter { appInfo ->
                try {
                    // Informiere das VM, welche App ausgewählt wurde
                    viewModel.onAppSelected(appInfo)
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error selecting app: ${appInfo.packageName}")
                }
            }

            binding.allAppsRecyclerView.apply {
                adapter = appSelectionAdapter
                layoutManager = LinearLayoutManager(this@SwipeActionsActivity)
                setHasFixedSize(true)
                itemAnimator = null
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting up RecyclerView")
        }
    }

    @OptIn(FlowPreview::class)
    private fun setupSearchListener() {
        binding.searchEditText.doOnTextChanged { text, _, _, _ ->
            searchQueryFlow.value = text?.toString() ?: ""
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchQueryFlow
                    .debounce(SEARCH_DEBOUNCE_MS)
                    .collect { query ->
                        try {
                            viewModel.onSearchQueryChanged(query)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            TimberWrapper.silentError(e, "Error in search query changed")
                        }
                    }
            }
        }
    }

    private fun setupClickListeners() {
        try {
            // Listener für den "Fertig"-Button
            binding.doneButton.setOnClickListener {
                try {
                    viewModel.onDoneClicked()
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error in done button click")
                    finish()
                }
            }

            // Listener für die Slot-Auswahl-Chips (welcher ist aktiv?)
            binding.leftSlotChip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    viewModel.onSlotSelected(SwipeSlot.LEFT)
                }
            }

            binding.rightSlotChip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    viewModel.onSlotSelected(SwipeSlot.RIGHT)
                }
            }

            // Listener für das "Löschen"-Icon (X) auf den Chips
            binding.leftSlotChip.setOnCloseIconClickListener {
                try {
                    viewModel.onSlotCleared(SwipeSlot.LEFT)
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error clearing left slot")
                }
            }

            binding.rightSlotChip.setOnCloseIconClickListener {
                try {
                    viewModel.onSlotCleared(SwipeSlot.RIGHT)
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error clearing right slot")
                }
            }

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting up click listeners")
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Beobachte den neuen UI-State
                viewModel.uiState.collect { state ->
                    try {
                        updateUi(state)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error updating UI")
                    }
                }
            }
        }
    }

    /**
     * Aktualisiert die UI basierend auf dem neuen SwipeActionsUiState.
     */
    private fun updateUi(state: SwipeActionsUiState) {
        if (_binding == null) {
            Timber.Forest.w("Attempted to update UI after binding was destroyed")
            return
        }

        try {
            binding.titleText.setText(state.titleResId)
            binding.subtitleText.setText(state.subtitleResId)

            appSelectionAdapter?.submitList(state.selectableApps) ?: run {
                Timber.Forest.w("Adapter is null, cannot submit list")
            }

            // Aktualisiere die Slot-Chips
            updateSlotChips(state)

        } catch (e: IllegalStateException) {
            TimberWrapper.silentError(e, "View not attached, skipping UI update")
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating UI")
        }
    }

    private fun updateSlotChips(state: SwipeActionsUiState) {
        if (_binding == null) return

        try {
            // Setze den Text für den "Left" Chip
            val leftText = state.appForLeft?.displayName ?: getString(R.string.swipe_slot_empty)
            binding.leftSlotChip.text = getString(R.string.swipe_slot_left_format, leftText)

            // Setze den Text für den "Right" Chip
            val rightText = state.appForRight?.displayName ?: getString(R.string.swipe_slot_empty)
            binding.rightSlotChip.text = getString(R.string.swipe_slot_right_format, rightText)

            // Setze, welcher Chip aktiv (checked) ist
            // Wichtig: Deaktiviere kurz die Listener, um Endlosschleifen zu vermeiden
            binding.leftSlotChip.setOnCheckedChangeListener(null)
            binding.rightSlotChip.setOnCheckedChangeListener(null)

            binding.leftSlotChip.isChecked = state.currentSlotBeingAssigned == SwipeSlot.LEFT
            binding.rightSlotChip.isChecked = state.currentSlotBeingAssigned == SwipeSlot.RIGHT

            // Aktiviere die Listener wieder
            setupClickListeners()

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating selection chips")
        }
    }

    override fun handleSpecificEvent(event: UiEvent) {
        // Bleibt leer, da alle relevanten Events in BaseActivity behandelt werden.
    }
}