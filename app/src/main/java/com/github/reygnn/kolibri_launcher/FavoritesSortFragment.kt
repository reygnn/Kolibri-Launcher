package com.github.reygnn.kolibri_launcher

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.kolibri_launcher.databinding.FragmentFavoritesSortBinding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * CRASH-SAFE VERSION
 *
 * Crash safety through:
 * - Nullable binding with proper cleanup
 * - Try-catch around all operations
 * - Safe ItemTouchHelper callbacks
 * - Lifecycle-aware coroutines with error handling
 * - Safe fragment result handling
 * - Defensive null checks
 */
@AndroidEntryPoint
class FavoritesSortFragment : Fragment() {

    // CRASH-SAFE: Nullable binding
    private var _binding: FragmentFavoritesSortBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var favoritesOrderManager: FavoritesOrderRepository

    @Inject
    @MainDispatcher
    lateinit var mainDispatcher: CoroutineDispatcher

    private lateinit var adapter: FavoritesAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var originalOrder: List<AppInfo> = emptyList()

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
                    AppInfo::class.java
                ) ?: emptyList()
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error getting favorites from arguments")
                emptyList()
            }

            originalOrder = initialApps.toList()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onCreate")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
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
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onViewCreated")
        }
    }

    private fun setupRecyclerView() {
        try {
            adapter = FavoritesAdapter { newOrder ->
                try {
                    saveFavoritesOrder(newOrder)
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error in adapter callback")
                }
            }

            binding.recyclerView.adapter = adapter
            binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())

            try {
                adapter.submitList(originalOrder)
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error submitting initial list")
            }

            val callback = createItemTouchHelperCallback()
            itemTouchHelper = ItemTouchHelper(callback)
            itemTouchHelper.attachToRecyclerView(binding.recyclerView)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting up RecyclerView")
        }
    }

    private fun createItemTouchHelperCallback(): ItemTouchHelper.SimpleCallback {
        return object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return try {
                    val fromPosition = viewHolder.bindingAdapterPosition
                    val toPosition = target.bindingAdapterPosition

                    if (fromPosition == RecyclerView.NO_POSITION ||
                        toPosition == RecyclerView.NO_POSITION) {
                        false
                    } else {
                        adapter.moveItem(fromPosition, toPosition)
                        true
                    }
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error in onMove")
                    false
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // No swipe action
            }

            override fun onSelectedChanged(
                viewHolder: RecyclerView.ViewHolder?,
                actionState: Int
            ) {
                try {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        viewHolder?.itemView?.alpha = 0.7f
                    }
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error in onSelectedChanged")
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                try {
                    super.clearView(recyclerView, viewHolder)
                    viewHolder.itemView.alpha = 1.0f
                    adapter.onMoveFinished()
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error in clearView")
                }
            }
        }
    }

    private fun setupButtons() {
        try {
            binding.buttonAlphabetical.setOnClickListener {
                try {
                    sortFavoritesAlphabetically()
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error in alphabetical button click")
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting alphabetical button listener")
        }

        try {
            binding.buttonReset.setOnClickListener {
                try {
                    resetToOriginalOrder()
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error in reset button click")
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting reset button listener")
        }
    }

    private fun sortFavoritesAlphabetically() {
        try {
            val sortedList = try {
                adapter.currentList.sortedBy { it.displayName.lowercase() }
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error sorting alphabetically")
                adapter.currentList
            }

            try {
                adapter.submitList(sortedList)
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error submitting sorted list")
            }

            saveFavoritesOrder(sortedList)
            showSnackbar(getString(R.string.favorites_sorted_alphabetically))
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in sortFavoritesAlphabetically")
        }
    }

    private fun resetToOriginalOrder() {
        try {
            try {
                adapter.submitList(originalOrder)
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error submitting original order")
            }

            saveFavoritesOrder(originalOrder)
            showSnackbar(getString(R.string.favorites_order_reset))
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in resetToOriginalOrder")
        }
    }

    private fun saveFavoritesOrder(favoriteApps: List<AppInfo>) {
        // CRASH-SAFE: Check fragment state
        if (!isAdded || isDetached) {
            Timber.w("Cannot save order - fragment not in valid state")
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(mainDispatcher) {
            try {
                val componentNames = try {
                    favoriteApps.map { it.componentName }
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error mapping component names")
                    emptyList()
                }

                val success = try {
                    favoritesOrderManager.saveOrder(componentNames)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error calling saveOrder")
                    false
                }

                if (success) {
                    Timber.d("Favorites order saved successfully")

                    try {
                        if (isAdded && !isDetached) {
                            setFragmentResult(REQUEST_KEY, bundleOf("changed" to true))
                        }
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error setting fragment result")
                    }
                } else {
                    if (isAdded && !isDetached) {
                        showSnackbar(getString(R.string.error_saving_order))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error in saveFavoritesOrder")
                if (isAdded && !isDetached) {
                    showSnackbar(getString(R.string.error_saving_order))
                }
            }
        }
    }

    private fun showSnackbar(message: String) {
        try {
            view?.let { v ->
                try {
                    Snackbar.make(v, message, Snackbar.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error showing snackbar")
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in showSnackbar")
        }
    }

    override fun onDestroyView() {
        try {
            (activity as? AppCompatActivity)?.supportActionBar?.title =
                getString(R.string.settings_title)

            _binding = null
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onDestroyView")
        } finally {
            super.onDestroyView()
        }
    }
}