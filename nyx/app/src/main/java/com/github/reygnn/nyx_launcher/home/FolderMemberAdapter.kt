package com.github.reygnn.nyx_launcher.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.nyx_launcher.home.model.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.IconRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Icons of a folder's members. Tap launches; long-press extracts to the home. */
class FolderMemberAdapter(
    private val iconLoader: IconLoader,
    private val scope: CoroutineScope,
    private val iconSizePx: Int,
    private val onLaunch: (ComponentKey) -> Unit,
    private val onExtract: (ComponentKey) -> Unit,
) : RecyclerView.Adapter<FolderMemberAdapter.MemberHolder>() {

    private var members: List<ComponentKey> = emptyList()

    fun submit(newMembers: List<ComponentKey>) {
        members = newMembers
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_folder_member, parent, false)
        return MemberHolder(view)
    }

    override fun getItemCount(): Int = members.size

    override fun onBindViewHolder(holder: MemberHolder, position: Int) {
        val key = members[position]
        val token = ++holder.bindToken
        holder.icon.setImageDrawable(null)
        holder.itemView.setOnClickListener { onLaunch(key) }
        holder.itemView.setOnLongClickListener { onExtract(key); true }
        scope.launch {
            val bitmap = runCatching { iconLoader.bitmap(IconRef.System(key), iconSizePx) }.getOrNull()
                ?: return@launch
            if (holder.bindToken == token) holder.icon.setImageBitmap(bitmap)
        }
    }

    override fun onViewRecycled(holder: MemberHolder) {
        holder.bindToken++
        holder.icon.setImageDrawable(null)
    }

    class MemberHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.member_icon)
        var bindToken: Int = 0
    }
}
