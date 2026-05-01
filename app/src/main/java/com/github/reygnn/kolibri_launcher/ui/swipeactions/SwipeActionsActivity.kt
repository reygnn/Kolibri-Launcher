package com.github.reygnn.kolibri_launcher.ui.swipeactions

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
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.databinding.ActivitySwipeActionsBinding
import com.github.reygnn.kolibri_launcher.ui.base.BaseActivity
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
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
        _binding?.let {
            outState.putString(STATE_SEARCH_QUERY, it.searchEditText.text.toString())
        }
    }

    override fun onDestroy() {
        // Outer catch kept: lifecycle teardown — finally{} super.onDestroy()
        try {
            if (_binding != null) {
                // Listener entfernen (Referenz auf ViewModel kappen)
                binding.leftSlotChip.setOnCheckedChangeListener(null)
                binding.rightSlotChip.setOnCheckedChangeListener(null)
                binding.leftSlotChip.setOnCloseIconClickListener(null)
                binding.rightSlotChip.setOnCloseIconClickListener(null)
                binding.allAppsRecyclerView.adapter = null
            }
            appSelectionAdapter = null
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
        appSelectionAdapter = SwipeActionsAppListAdapter { appInfo -> viewModel.onAppSelected(appInfo) }
        binding.allAppsRecyclerView.apply {
            adapter = appSelectionAdapter
            layoutManager = LinearLayoutManager(this@SwipeActionsActivity)
            setHasFixedSize(true)
            itemAnimator = null
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
                    .collect { query -> viewModel.onSearchQueryChanged(query) }
            }
        }
    }

    private fun setupClickListeners() {
        // doneButton inner catch kept: viewModel.onDoneClicked is the user-
        // facing commit; finish() fallback is the user's exit if it throws.
        binding.doneButton.setOnClickListener {
            try {
                viewModel.onDoneClicked()
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in done button click")
                finish()
            }
        }

        binding.leftSlotChip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                viewModel.onSlotSelected(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)
            }
        }

        binding.rightSlotChip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                viewModel.onSlotSelected(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT)
            }
        }

        binding.leftSlotChip.setOnCloseIconClickListener {
            viewModel.onSlotCleared(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)
        }

        binding.rightSlotChip.setOnCloseIconClickListener {
            viewModel.onSlotCleared(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> updateUi(state) }
            }
        }
    }

    /**
     * Aktualisiert die UI basierend auf dem neuen SwipeActionsUiState.
     */
    private fun updateUi(state: SwipeActionsUiState) {
        if (_binding == null) {
            Timber.w("Attempted to update UI after binding was destroyed")
            return
        }
        binding.titleText.setText(state.titleResId)
        binding.subtitleText.setText(state.subtitleResId)
        appSelectionAdapter?.submitList(state.selectableApps) ?: run {
            Timber.w("Adapter is null, cannot submit list")
        }
        updateSlotChips(state)
    }

    private fun updateSlotChips(state: SwipeActionsUiState) {
        if (_binding == null) return

        val leftText = state.appForLeft?.displayName ?: getString(R.string.swipe_slot_empty)
        binding.leftSlotChip.text = getString(R.string.swipe_slot_left_format, leftText)

        val rightText = state.appForRight?.displayName ?: getString(R.string.swipe_slot_empty)
        binding.rightSlotChip.text = getString(R.string.swipe_slot_right_format, rightText)

        // State setzen OHNE den Listener zu triggern
        // Trick: Listener kurz entfernen, setzen, Listener wieder dran.
        binding.leftSlotChip.setOnCheckedChangeListener(null)
        binding.rightSlotChip.setOnCheckedChangeListener(null)

        binding.leftSlotChip.isChecked = state.currentSlotBeingAssigned == SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT
        binding.rightSlotChip.isChecked = state.currentSlotBeingAssigned == SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT

        binding.leftSlotChip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) viewModel.onSlotSelected(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)
        }
        binding.rightSlotChip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) viewModel.onSlotSelected(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT)
        }
    }

    override fun handleSpecificEvent(event: UiEvent) {
        // Bleibt leer, da alle relevanten Events in BaseActivity behandelt werden.
    }
}