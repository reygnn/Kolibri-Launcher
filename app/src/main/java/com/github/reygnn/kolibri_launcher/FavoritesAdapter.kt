package com.github.reygnn.kolibri_launcher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.kolibri_launcher.databinding.ItemFavoriteBinding
import timber.log.Timber

/**
 * CRASH-SAFE VERSION
 *
 * Crash safety through:
 * - Try-catch around all ViewHolder operations
 * - Safe layout inflation
 * - Safe list manipulation
 * - Defensive validation
 * - Safe callback invocation
 */
class FavoritesAdapter(
    private val onOrderChanged: (List<AppInfo>) -> Unit
) : ListAdapter<AppInfo, FavoritesAdapter.ViewHolder>(AppInfoDiffCallback()) {

    class ViewHolder(val binding: ItemFavoriteBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AppInfo) {
            try {
                binding.appName.text = app.displayName
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error setting app name: ${app.packageName}")
                try {
                    binding.appName.text = app.packageName
                } catch (e2: Exception) {
                    TimberWrapper.silentError(e2, "Critical error in bind fallback")
                }
            }

            try {
                binding.appIcon.visibility = View.GONE
                binding.dragHandle.visibility = View.VISIBLE
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error setting visibility")
            }
        }

        companion object {
            fun from(parent: ViewGroup): ViewHolder? {
                return try {
                    val layoutInflater = LayoutInflater.from(parent.context)
                    val binding = ItemFavoriteBinding.inflate(layoutInflater, parent, false)
                    ViewHolder(binding)
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error creating ViewHolder")
                    null
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return try {
            ViewHolder.from(parent) ?: run {
                // Fallback: Create minimal ViewHolder
                Timber.w("Failed to create normal ViewHolder, creating fallback")
                createFallbackViewHolder(parent)
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onCreateViewHolder, creating fallback")
            createFallbackViewHolder(parent)
        }
    }

    private fun createFallbackViewHolder(parent: ViewGroup): ViewHolder {
        return try {
            val layoutInflater = LayoutInflater.from(parent.context)
            val binding = ItemFavoriteBinding.inflate(layoutInflater, parent, false)
            ViewHolder(binding)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Critical error in fallback ViewHolder")
            throw RuntimeException("Unable to create ViewHolder", e)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        try {
            val item = try {
                getItem(position)
            } catch (e: IndexOutOfBoundsException) {
                TimberWrapper.silentError(e, "Index out of bounds at position $position")
                return
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error getting item at position $position")
                return
            }

            if (item != null) {
                holder.bind(item)
            } else {
                Timber.w("Null item at position $position")
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error binding ViewHolder at position $position")
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        try {
            super.onViewRecycled(holder)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error recycling ViewHolder")
        }
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        try {
            val currentList = try {
                currentList
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error getting current list")
                return
            }

            // Validation
            if (fromPosition < 0 ||
                fromPosition >= currentList.size ||
                toPosition < 0 ||
                toPosition >= currentList.size) {
                Timber.w("Invalid positions: from=$fromPosition, to=$toPosition, size=${currentList.size}")
                return
            }

            val mutableList = try {
                currentList.toMutableList()
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error creating mutable list")
                return
            }

            val movedItem = try {
                mutableList.removeAt(fromPosition)
            } catch (e: IndexOutOfBoundsException) {
                TimberWrapper.silentError(e, "Error removing item at $fromPosition")
                return
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Unexpected error removing item")
                return
            }

            try {
                mutableList.add(toPosition, movedItem)
            } catch (e: IndexOutOfBoundsException) {
                TimberWrapper.silentError(e, "Error adding item at $toPosition")
                // Revert: Add item back at original position
                try {
                    mutableList.add(fromPosition, movedItem)
                } catch (revertError: Exception) {
                    TimberWrapper.silentError(revertError, "Critical: Failed to revert move")
                }
                return
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Unexpected error adding item")
                return
            }

            try {
                submitList(mutableList)
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error submitting moved list")
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Critical error in moveItem")
        }
    }

    fun onMoveFinished() {
        try {
            val currentList = try {
                currentList
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error getting current list for callback")
                emptyList()
            }

            try {
                onOrderChanged(currentList)
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error in onOrderChanged callback")
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Critical error in onMoveFinished")
        }
    }
}