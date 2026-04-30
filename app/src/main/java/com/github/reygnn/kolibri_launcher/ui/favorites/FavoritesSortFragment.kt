package com.github.reygnn.kolibri_launcher.ui.favorites

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.databinding.FragmentFavoritesSortBinding
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * UI shell for the favorites-reorder screen. The Fragment owns the
 * RecyclerView + drag-and-drop wiring, the action-bar title juggling,
 * the `setFragmentResult` callback to the parent screen, and toast
 * rendering. The sort/reset/persist logic and the apps-list state live
 * in [FavoritesSortViewModel] so they can be unit-tested on the JVM.
 *
 * Crash-safety pattern from the original implementation is preserved
 * (try-catch around every callback, defensive null checks on the
 * binding, lifecycle-aware coroutines for Flow collection).
 */
@AndroidEntryPoint
class FavoritesSortFragment : Fragment() {

    private var _binding: FragmentFavoritesSortBinding? = null
    private val binding get() = _binding!!

    private val viewModel: FavoritesSortViewModel by viewModels()

    private var adapter: FavoritesAdapter? = null
    private var itemTouchHelper: ItemTouchHelper? = null

    companion object {
        const val REQUEST_KEY = "favorites_order_changed_key"

        fun newInstance(favoriteApps: List<AppInfo>): FavoritesSortFragment {
            return FavoritesSortFragment().apply {
                arguments = Bundle().apply {
                    putParcelableArrayList(AppConstants.ARG_FAVORITES, ArrayList(favoriteApps))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val initialApps = try {
                arguments?.getParcelableArrayList(
                    AppConstants.ARG_FAVORITES,
                    AppInfo::class.java,
                ) ?: emptyList()
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error getting favorites from arguments")
                emptyList()
            }

            // Idempotent — survives configuration change without
            // overwriting in-progress drag-and-drop state in the VM.
            viewModel.setInitialApps(initialApps)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onCreate")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentFavoritesSortBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            (activity as? AppCompatActivity)?.supportActionBar?.title =
                getString(R.string.favorites_sort_title)

            setupRecyclerView()
            setupButtons()
            observeViewModel()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onViewCreated")
        }
    }

    private fun setupRecyclerView() {
        try {
            adapter = FavoritesAdapter { newOrder ->
                try {
                    viewModel.onMoved(newOrder)
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error in adapter callback")
                }
            }

            binding.recyclerView.adapter = adapter
            binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())

            val callback = createItemTouchHelperCallback()
            itemTouchHelper = ItemTouchHelper(callback)
            itemTouchHelper?.attachToRecyclerView(binding.recyclerView)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting up RecyclerView")
        }
    }

    private fun createItemTouchHelperCallback(): ItemTouchHelper.SimpleCallback {
        return object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0,
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                return try {
                    val fromPosition = viewHolder.bindingAdapterPosition
                    val toPosition = target.bindingAdapterPosition

                    if (fromPosition == RecyclerView.NO_POSITION ||
                        toPosition == RecyclerView.NO_POSITION
                    ) {
                        false
                    } else {
                        adapter?.moveItem(fromPosition, toPosition)
                        true
                    }
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error in onMove")
                    false
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // No swipe action
            }

            override fun onSelectedChanged(
                viewHolder: RecyclerView.ViewHolder?,
                actionState: Int,
            ) {
                try {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        viewHolder?.itemView?.alpha = 0.7f
                    }
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error in onSelectedChanged")
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                try {
                    super.clearView(recyclerView, viewHolder)
                    viewHolder.itemView.alpha = 1.0f
                    adapter?.onMoveFinished()
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error in clearView")
                }
            }
        }
    }

    private fun setupButtons() {
        try {
            binding.buttonAlphabetical.setOnClickListener {
                try {
                    viewModel.onSortAlphabetically()
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error in alphabetical button click")
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting alphabetical button listener")
        }

        try {
            binding.buttonReset.setOnClickListener {
                try {
                    viewModel.onResetToOriginal()
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error in reset button click")
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting reset button listener")
        }
    }

    private fun observeViewModel() {
        // Apps state → adapter. The drag-and-drop path emits the same
        // list that the adapter already shows, but DiffUtil makes that a
        // no-op. Sort and Reset emit a different list; that drives the
        // adapter's animation.
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.apps.collect { apps ->
                        try {
                            adapter?.submitList(apps)
                        } catch (e: Throwable) {
                            TimberWrapper.silentError(e, "Error submitting list to adapter")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in repeatOnLifecycle for apps")
            }
        }

        // Events: toasts and the change-broadcast that becomes a fragment
        // result. Other UiEvents (NavigateUp, etc.) are not emitted by
        // this ViewModel, so the `else` branch is unreachable in practice.
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.event.collect { event ->
                        try {
                            handleEvent(event)
                        } catch (e: Throwable) {
                            TimberWrapper.silentError(e, "Error handling event: $event")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in repeatOnLifecycle for events")
            }
        }
    }

    private fun handleEvent(event: UiEvent) {
        when (event) {
            is UiEvent.ShowToast -> showToast(getString(event.messageResId))
            is UiEvent.FavoritesOrderChanged -> {
                try {
                    setFragmentResult(REQUEST_KEY, bundleOf("changed" to true))
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error setting fragment result")
                }
            }
            else -> {
                // Unreachable for events emitted by FavoritesSortViewModel.
            }
        }
    }

    private fun showToast(message: String) {
        try {
            if (isAdded && !isDetached) {
                context?.let { ctx ->
                    Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error showing toast")
        }
    }

    override fun onDestroyView() {
        try {
            (activity as? AppCompatActivity)?.supportActionBar?.title =
                getString(R.string.settings_title)

            _binding = null
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onDestroyView")
        } finally {
            super.onDestroyView()
        }
    }
}
