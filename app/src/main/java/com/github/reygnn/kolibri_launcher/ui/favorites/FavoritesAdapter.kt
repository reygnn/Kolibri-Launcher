package com.github.reygnn.kolibri_launcher.ui.favorites

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.databinding.ItemFavoriteBinding
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.ui.util.AppInfoDiffCallback
import timber.log.Timber

/**
 * Adapter for the drag-and-drop favorites-reorder list.
 *
 * Crash-safety: legitimate catches stay around layout inflation
 * (`InflateException`, `OutOfMemoryError`), getItem race conditions,
 * `MutableList.add/removeAt` IOBE under concurrent edits, and the
 * user-supplied `onOrderChanged` callback. Throwable-catches around
 * pure View-property setters and ListAdapter-property reads were
 * removed in the throwable-audit sweep — they could not throw.
 */
class FavoritesAdapter(
    private val onOrderChanged: (List<AppInfo>) -> Unit,
) : ListAdapter<AppInfo, FavoritesAdapter.ViewHolder>(AppInfoDiffCallback()) {

    class ViewHolder(val binding: ItemFavoriteBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AppInfo) {
            // Reine TextView/View-Setter auf Non-Null-Binding — werfen nicht.
            binding.appName.text = app.displayName
            binding.appIcon.visibility = View.GONE
            binding.dragHandle.visibility = View.VISIBLE
        }

        companion object {
            fun from(parent: ViewGroup): ViewHolder? {
                return try {
                    // Outer Catchall kept: layout inflation can throw
                    // InflateException or Resources.NotFoundException, AND
                    // OutOfMemoryError on bitmap resources. OOM extends
                    // Error/Throwable, NOT Exception — same pattern as §9.8
                    // ZoomableImageView and §9.13 BackupRepositoryImpl.
                    val layoutInflater = LayoutInflater.from(parent.context)
                    val binding = ItemFavoriteBinding.inflate(layoutInflater, parent, false)
                    ViewHolder(binding)
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error creating ViewHolder")
                    null
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // ViewHolder.from fängt Inflate-Exceptions selbst und gibt null
        // zurück; ein zusätzlicher äußerer Catch wäre tot.
        return ViewHolder.from(parent) ?: run {
            Timber.w("Failed to create normal ViewHolder, creating fallback")
            createFallbackViewHolder(parent)
        }
    }

    private fun createFallbackViewHolder(parent: ViewGroup): ViewHolder {
        return try {
            val layoutInflater = LayoutInflater.from(parent.context)
            val binding = ItemFavoriteBinding.inflate(layoutInflater, parent, false)
            ViewHolder(binding)
        } catch (e: Throwable) {
            // Outer Catchall kept: gleicher Inflate-Pfad wie ViewHolder.from
            // (siehe dort) — OOM extends Throwable, NOT Exception. Wenn auch
            // der Fallback-Inflate failt, ist die Liste nicht mehr
            // renderbar. Re-throw, damit der RecyclerView-Stack explizit
            // failen kann statt zombiehaft weiterzulaufen.
            TimberWrapper.silentError(e, "Critical error in fallback ViewHolder")
            throw RuntimeException("Unable to create ViewHolder", e)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = try {
            // Race-Schutz: Liste kann zwischen Layout und Bind neu sein.
            getItem(position)
        } catch (e: IndexOutOfBoundsException) {
            TimberWrapper.silentError(e, "Index out of bounds at position $position")
            return
        }

        if (item != null) {
            holder.bind(item)
        } else {
            Timber.w("Null item at position $position")
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        // RecyclerView.Adapter.onViewRecycled der Base-Klasse ist leer.
        super.onViewRecycled(holder)
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        val list = currentList // ListAdapter-Property, wirft nicht.

        if (fromPosition < 0 ||
            fromPosition >= list.size ||
            toPosition < 0 ||
            toPosition >= list.size
        ) {
            Timber.w("Invalid positions: from=$fromPosition, to=$toPosition, size=${list.size}")
            return
        }

        val mutableList = list.toMutableList()

        val movedItem = try {
            // Race-geschützt: trotz size-Check oben kann die Liste
            // zwischen Check und removeAt neu gesetzt werden.
            mutableList.removeAt(fromPosition)
        } catch (e: IndexOutOfBoundsException) {
            TimberWrapper.silentError(e, "Error removing item at $fromPosition")
            return
        }

        try {
            mutableList.add(toPosition, movedItem)
        } catch (e: IndexOutOfBoundsException) {
            TimberWrapper.silentError(e, "Error adding item at $toPosition")
            // Revert: Item zurück an die Original-Position. Falls auch
            // das wirft, ist die Liste in einem unbestimmten Zwischen-
            // Zustand — submitList unten wird dann ohnehin nicht mehr
            // gerufen, der nächste echte Update überschreibt.
            try {
                mutableList.add(fromPosition, movedItem)
            } catch (revertError: IndexOutOfBoundsException) {
                TimberWrapper.silentError(revertError, "Critical: Failed to revert move")
            }
            return
        }

        try {
            // submitList kann während laufender DiffUtil-Berechnung
            // werfen — selten, aber dokumentierter Edge-Case.
            submitList(mutableList)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error submitting moved list")
        }
    }

    fun onMoveFinished() {
        // currentList ist eine ListAdapter-Property — wirft nicht.
        try {
            // Callback ist User-Code (Fragment-Lambda) — kann beliebig werfen.
            onOrderChanged(currentList)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onOrderChanged callback")
        }
    }
}
