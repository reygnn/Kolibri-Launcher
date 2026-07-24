package com.github.reygnn.kolibri_launcher.ui.favorites

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.ui.util.showToastSafe
import com.github.reygnn.kolibri_launcher.databinding.FragmentFavoritesSortBinding
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.flow.collectOnStarted
import com.github.reygnn.kolibri_launcher.ui.util.AppInfoParcelable
import com.github.reygnn.kolibri_launcher.ui.util.toParcelable
import dagger.hilt.android.AndroidEntryPoint
import kotlin.coroutines.EmptyCoroutineContext

/**
 * UI shell for the favorites-reorder screen. The Fragment owns the
 * RecyclerView + drag-and-drop wiring, the action-bar title juggling,
 * the `setFragmentResult` callback to the parent screen, and toast
 * rendering. The sort/reset/persist logic and the apps-list state live
 * in [FavoritesSortViewModel] so they can be unit-tested on the JVM.
 *
 * Catches are kept only at real boundaries (Bundle-parcelable parsing,
 * Flow collection wrappers, per-item recovery in collect{}, Toast IPC,
 * setFragmentResult lifecycle race) — see CLAUDE.md Rule 11.
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
                    putParcelableArrayList(
                        AppConstants.ARG_FAVORITES,
                        ArrayList(favoriteApps.map { it.toParcelable() })
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialApps: List<AppInfo> = try {
            arguments?.getParcelableArrayList(
                AppConstants.ARG_FAVORITES,
                AppInfoParcelable::class.java,
            )?.map { it.toAppInfo() } ?: emptyList()
        } catch (e: Throwable) {
            // Bundle parcelable deserialization is EXTERNAL — a malformed
            // Parcel can throw BadParcelableException et al.
            TimberWrapper.silentError(e, "Error getting favorites from arguments")
            emptyList()
        }

        // Idempotent — survives configuration change without
        // overwriting in-progress drag-and-drop state in the VM.
        viewModel.setInitialApps(initialApps)
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

        (activity as? AppCompatActivity)?.supportActionBar?.title =
            getString(R.string.favorites_sort_title)

        setupRecyclerView()
        setupButtons()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = FavoritesAdapter { newOrder ->
            viewModel.onMoved(newOrder)
        }

        binding.recyclerView.adapter = adapter
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val callback = createItemTouchHelperCallback()
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper?.attachToRecyclerView(binding.recyclerView)
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
                val fromPosition = viewHolder.bindingAdapterPosition
                val toPosition = target.bindingAdapterPosition

                return if (fromPosition == RecyclerView.NO_POSITION ||
                    toPosition == RecyclerView.NO_POSITION
                ) {
                    false
                } else {
                    adapter?.moveItem(fromPosition, toPosition)
                    true
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // No swipe action
            }

            override fun onSelectedChanged(
                viewHolder: RecyclerView.ViewHolder?,
                actionState: Int,
            ) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.alpha = 0.7f
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f
                adapter?.onMoveFinished()
            }
        }
    }

    private fun setupButtons() {
        binding.buttonAlphabetical.setOnClickListener {
            viewModel.onSortAlphabetically()
        }

        binding.buttonReset.setOnClickListener {
            viewModel.onResetToOriginal()
        }
    }

    private fun observeViewModel() {
        // Apps state → adapter. The drag-and-drop path emits the same
        // list that the adapter already shows, but DiffUtil makes that a
        // no-op. Sort and Reset emit a different list; that drives the
        // adapter's animation.
        collectOnStarted(
            flow = viewModel.apps,
            errorTag = "apps",
            coroutineContext = EmptyCoroutineContext,
        ) { apps ->
            try {
                // Per-item recovery: a single failing submitList
                // must not tear down the whole subscription.
                adapter?.submitList(apps)
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error submitting list to adapter")
            }
        }

        // Events: toasts and the change-broadcast that becomes a fragment
        // result. Other UiEvents (NavigateUp, etc.) are not emitted by
        // this ViewModel, so the `else` branch is unreachable in practice.
        collectOnStarted(
            flow = viewModel.event,
            errorTag = "events",
            coroutineContext = EmptyCoroutineContext,
        ) { event ->
            try {
                // Per-item recovery: see apps-collect comment.
                handleEvent(event)
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error handling event: $event")
            }
        }
    }

    private fun handleEvent(event: UiEvent) {
        when (event) {
            is UiEvent.ShowToast -> showToastSafe(getString(event.messageResId))
            is UiEvent.FavoritesOrderChanged -> {
                try {
                    setFragmentResult(
                        REQUEST_KEY,
                        Bundle().apply { putBoolean("changed", true) },
                    )
                } catch (e: Throwable) {
                    // Lifecycle race: setFragmentResult can throw
                    // IllegalStateException if the FragmentManager is
                    // already in a saved state when the event lands.
                    TimberWrapper.silentError(e, "Error setting fragment result")
                }
            }
            else -> {
                // Unreachable for events emitted by FavoritesSortViewModel.
            }
        }
    }

    override fun onDestroyView() {
        (activity as? AppCompatActivity)?.supportActionBar?.title =
            getString(R.string.settings_title)

        // Detach the RecyclerView plumbing so the destroyed view tree isn't
        // retained (matches AppDrawerFragment.onDestroyView): detach the
        // ItemTouchHelper, null the adapter, drop the field references.
        itemTouchHelper?.attachToRecyclerView(null)
        _binding?.recyclerView?.adapter = null
        adapter = null
        itemTouchHelper = null

        _binding = null
        super.onDestroyView()
    }
}
