package com.github.reygnn.nyx_launcher.tapl

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.viewpager2.widget.ViewPager2
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.launcher.testing.BasePage
import com.github.reygnn.launcher.testing.longPressDrag
import org.hamcrest.Matcher

/**
 * The open home-folder overlay (R.id.folder_members is its member RecyclerView).
 * Opened via [NyxHome.openFolderAtCell]. A member long-press starts a
 * DragPayload.FolderMember drag immediately (startFolderMemberDrag -> startDrag,
 * no arm) and dismisses the overlay, so the drag continues straight into the
 * DragLayer; dropping on the grid runs extractFromFolder(target).
 */
internal class NyxFolder : BasePage() {
    override val anchorId: Int = R.id.folder_members
    override val name: String = "NyxFolder"

    init { assertOnPage() }

    /**
     * Long-press-drags member [memberIndex] out of the folder onto grid cell
     * [targetCellIndex] — extracting it from the folder onto the home grid.
     */
    fun extractMemberToCell(memberIndex: Int, targetCellIndex: Int) {
        onView(withId(R.id.home_root)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)
            override fun getDescription() = "extract folder member $memberIndex -> cell $targetCellIndex"
            override fun perform(uiController: UiController, view: View) {
                // Grid target (behind the overlay, still laid out) — read up front.
                val pager = view.findViewById<ViewPager2>(R.id.home_pager)
                val target = NyxHome.cellCenterOnScreen(pager, pager.currentItem, targetCellIndex)
                    ?: error("No grid cell at index $targetCellIndex")

                val members = view.findViewById<RecyclerView>(R.id.folder_members)
                    ?: error("folder_members not found — is the folder open?")
                val member = members.findViewHolderForAdapterPosition(memberIndex)?.itemView
                    ?: error("No folder member at index $memberIndex")
                val loc = IntArray(2)
                member.getLocationOnScreen(loc)
                val startX = loc[0] + member.width / 2f
                val startY = loc[1] + member.height / 2f

                longPressDrag(uiController, startX, startY, waypoints = listOf(target))
            }
        })
    }
}
