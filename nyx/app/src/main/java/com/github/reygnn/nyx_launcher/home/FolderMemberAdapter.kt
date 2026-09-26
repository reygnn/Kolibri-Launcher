package com.github.reygnn.nyx_launcher.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.LazySlotMembership
import com.github.reygnn.nyx_launcher.home.model.IconRef
import kotlinx.coroutines.CoroutineScope

/** Icons of a folder's members. Tap launches; long-press starts a home drag to
 *  extract the member (finger-drag, placed where the user drops it).
 *
 *  A member whose app is no longer installed ([installed] is the current installed-key
 *  set; empty means "not loaded" → flag nothing, per [LazySlotMembership]) renders greyed
 *  with a placeholder and, on tap, calls [onMissingApp] to offer removal from the folder —
 *  the folder-internal analog of a greyed top-level tile. Drawer folders leave [installed]
 *  empty (their members are already reconciled against the live app set), so nothing greys
 *  there. */
class FolderMemberAdapter(
    private val iconLoader: IconLoader,
    private val scope: CoroutineScope,
    private val iconSizePx: Int,
    private val onLaunch: (ComponentKey) -> Unit,
    private val onStartDrag: (view: View, key: ComponentKey) -> Unit,
    private val installed: Set<ComponentKey> = emptySet(),
    private val onMissingApp: (ComponentKey) -> Unit = {},
) : RecyclerView.Adapter<FolderMemberAdapter.MemberHolder>() {

    private var members: List<ComponentKey> = emptyList()
    private var dotPackages: Set<String> = emptySet()

    fun submit(newMembers: List<ComponentKey>) {
        members = newMembers
        notifyDataSetChanged()
    }

    fun submitNotificationDots(newDots: Set<String>) {
        if (dotPackages == newDots) return
        dotPackages = newDots
        notifyItemRangeChanged(0, members.size, NOTIFICATION_DOT_PAYLOAD)
    }

    override fun onBindViewHolder(holder: MemberHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isDotOnlyPayload()) {
            holder.dot.visibility =
                if (members[position].packageName in dotPackages) View.VISIBLE else View.GONE
            return
        }
        super.onBindViewHolder(holder, position, payloads)
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
        holder.dot.visibility = if (key.packageName in dotPackages) View.VISIBLE else View.GONE
        holder.itemView.setOnLongClickListener { onStartDrag(holder.itemView, key); true }
        if (LazySlotMembership.isMissing(key, installed)) {
            // Dead reference (Windows-shortcut model): a launch is impossible, so a tap offers
            // to remove it from the folder; long-press still extracts (it becomes a greyed
            // top-level tile, removable there too). Greyed placeholder instead of the icon.
            holder.icon.alpha = MISSING_ICON_ALPHA
            holder.icon.setImageResource(android.R.drawable.sym_def_app_icon)
            holder.itemView.setOnClickListener { onMissingApp(key) }
        } else {
            holder.icon.alpha = 1f
            holder.itemView.setOnClickListener { onLaunch(key) }
            holder.icon.loadIconGated(scope, token, { holder.bindToken }) {
                iconLoader.bitmap(IconRef.System(key), iconSizePx)
            }
        }
    }

    override fun onViewRecycled(holder: MemberHolder) {
        holder.bindToken++
        holder.icon.setImageDrawable(null)
        holder.icon.alpha = 1f // don't leak a greyed missing-member alpha onto a reused holder
    }

    class MemberHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.member_icon)
        val dot: View = view.findViewById(R.id.member_dot)
        var bindToken: Int = 0
    }
}
