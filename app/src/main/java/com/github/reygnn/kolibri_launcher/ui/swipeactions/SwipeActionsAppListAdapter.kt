package com.github.reygnn.kolibri_launcher.ui.swipeactions

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.databinding.ItemAppSelectableSwipeBinding
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo

class SwipeActionsAppListAdapter(
    private val onAppClicked: (AppInfo) -> Unit
) : ListAdapter<SwipeActionSelectableApp, SwipeActionsAppListAdapter.ViewHolder>(SwipeActionAppDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppSelectableSwipeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding, onAppClicked)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemAppSelectableSwipeBinding,
        onAppClicked: (AppInfo) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentItem: SwipeActionSelectableApp? = null

        init {
            itemView.setOnClickListener {
                currentItem?.let {
                    onAppClicked(it.appInfo)
                }
            }
        }

        fun bind(item: SwipeActionSelectableApp) {
            this.currentItem = item

            binding.appLabel.text = item.appInfo.displayName

            // Setze das korrekte Icon basierend auf dem zugewiesenen Slot
            when (item.assignedSlot) {
                SwipeSlot.SWIPE_FROM_LEFT -> {
                    binding.slotIndicatorIcon.visibility = View.VISIBLE
                    binding.slotIndicatorIcon.setImageResource(R.drawable.ic_arrow_left)
                }
                SwipeSlot.SWIPE_FROM_RIGHT -> {
                    binding.slotIndicatorIcon.visibility = View.VISIBLE
                    binding.slotIndicatorIcon.setImageResource(R.drawable.ic_arrow_right)
                }
                SwipeSlot.NONE -> {
                    // Wichtig: 'invisible' verwenden, damit das Layout nicht springt
                    binding.slotIndicatorIcon.visibility = View.INVISIBLE
                }
            }
        }
    }

    class SwipeActionAppDiffCallback : DiffUtil.ItemCallback<SwipeActionSelectableApp>() {
        override fun areItemsTheSame(oldItem: SwipeActionSelectableApp, newItem: SwipeActionSelectableApp): Boolean {
            return oldItem.appInfo.componentName == newItem.appInfo.componentName
        }

        override fun areContentsTheSame(oldItem: SwipeActionSelectableApp, newItem: SwipeActionSelectableApp): Boolean {
            return oldItem == newItem
        }
    }
}