package com.github.reygnn.kolibri_launcher.ui.home

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.ui.util.AppInfoDiffCallback
import com.github.reygnn.kolibri_launcher.ui.util.toHorizontalGravity

/**
 * RecyclerView adapter for the home favorites list.
 *
 * Why a RecyclerView for ~20 favorites at most: NOT for view recycling
 * (the favorite list is small enough that recycling is irrelevant), but
 * for **scroll-engine parity with the AppDrawer**. ScrollView and
 * RecyclerView use different fling-friction curves, edge-effect
 * implementations, and post-touchSlop velocity handling — these
 * differences are perceptible side-by-side. Standardizing both
 * surfaces on RecyclerView removes that asymmetry.
 *
 * Each item is a programmatically constructed `Button` with
 * `WRAP_CONTENT` width — important for the wrapper's
 * [HomeGestureLayout.hasOwnTouchPipelineDescendantAt] hit-test, which
 * gives a long-press on a favorite text priority over the wrapper's
 * own customize-options dialog. `setOnLongClickListener` (set in
 * [onBindViewHolder]) implicitly toggles `isLongClickable = true`,
 * which is the signal the hit-test reads.
 *
 * Theme + layout updates flow through [setStyling]: the host fragment
 * snapshots the active theme/layout/font into a [Styling] value and
 * pushes it. Internally a `notifyItemRangeChanged` triggers a rebind
 * so every visible button picks up the new styling. App list updates
 * flow through `submitList` (inherited from [ListAdapter]) with the
 * standard [DiffUtil] callback.
 */
class HomeFavoritesAdapter(
    private val onAppClick: (AppInfo) -> Unit,
    private val onAppLongClick: (AppInfo) -> Unit,
) : ListAdapter<AppInfo, HomeFavoritesAdapter.ViewHolder>(AppInfoDiffCallback()) {

    /**
     * Snapshot of the styling state that varies with theme + layout
     * settings. Owned by the host fragment which produces a fresh
     * value whenever any contributing flow emits.
     */
    data class Styling(
        val textSizePx: Float,
        val verticalPaddingPx: Int,
        val horizPaddingPx: Int,
        val isBold: Boolean,
        val textColor: Int,
        val shadowColor: Int,
        val alignment: FavoritesAlignment,
    )

    private var styling: Styling = INITIAL_STYLING

    fun setStyling(newStyling: Styling) {
        if (styling == newStyling) return
        styling = newStyling
        if (itemCount > 0) {
            // Payload makes DefaultItemAnimator.canReuseUpdatedViewHolder
            // return true, so existing ViewHolders are rebound in place
            // without the cross-fade change animation. Keeps the favorites
            // styling/alignment update in lockstep with the timeContainer
            // gravity flip in HomeFragment.applyLayoutToExistingViews —
            // otherwise the RV cross-fade lags ~250ms behind.
            notifyItemRangeChanged(0, itemCount, STYLING_PAYLOAD)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // The row is a MATCH_PARENT FrameLayout that hosts a WRAP_CONTENT
        // button. Alignment is encoded as `FrameLayout.LayoutParams.gravity`
        // on the button — that positions the button (start / center / end)
        // within the row WITHOUT widening the button itself.
        //
        // Why two views, not just a MATCH_PARENT button:
        // [HomeGestureLayout.hasOwnTouchPipelineDescendantAt] walks the view
        // tree at the touch point and decides whether the wrapper's own
        // long-press fires. A MATCH_PARENT button covers the whole row →
        // every long-press on a favorites row hits the favorite's
        // long-press (app-context-menu) and the wrapper's customize-options
        // dialog never gets a chance. Keeping the button WRAP_CONTENT
        // means the empty space next to a short favorite belongs to the
        // FrameLayout (which is NOT long-clickable), so the wrapper's
        // long-press path stays alive there.
        val container = FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val button = Button(container.context).apply {
            background = null
            includeFontPadding = false
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        container.addView(button)
        return ViewHolder(container, button)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        try {
            val app = getItem(position)
            // Position the button within the row. Wrap-content + layout
            // gravity keeps the click/long-press hit area on the text only,
            // leaving empty space for the wrapper's own long-press handler.
            val params = holder.button.layoutParams as FrameLayout.LayoutParams
            val newGravity = styling.alignment.toHorizontalGravity() or Gravity.CENTER_VERTICAL
            if (params.gravity != newGravity) {
                params.gravity = newGravity
                holder.button.layoutParams = params
            }
            with(holder.button) {
                text = app.displayName
                setTextSize(TypedValue.COMPLEX_UNIT_PX, styling.textSizePx)
                setPadding(
                    styling.horizPaddingPx,
                    styling.verticalPaddingPx,
                    styling.horizPaddingPx,
                    styling.verticalPaddingPx,
                )
                typeface = if (styling.isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(createSubtlePressColor(styling.textColor))
                setShadowLayer(
                    AppConstants.SHADOW_RADIUS_APPS,
                    AppConstants.SHADOW_DX,
                    AppConstants.SHADOW_DY,
                    styling.shadowColor,
                )
                setOnClickListener { onAppClick(app) }
                setOnLongClickListener {
                    onAppLongClick(app)
                    true
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error binding favorite at position $position")
        }
    }

    class ViewHolder(
        container: FrameLayout,
        val button: Button,
    ) : RecyclerView.ViewHolder(container)

    private companion object {
        // Marker payload for notifyItemRangeChanged — see setStyling KDoc.
        private val STYLING_PAYLOAD = Any()

        val INITIAL_STYLING = Styling(
            textSizePx = AppConstants.FALLBACK_TEXT_SIZE_PX,
            verticalPaddingPx = AppConstants.FALLBACK_VERTICAL_PADDING_PX,
            horizPaddingPx = AppConstants.FALLBACK_DIMEN_PX,
            isBold = AppConstants.FALLBACK_FONT_BOLD,
            textColor = AppConstants.DEFAULT_TEXT_COLOR,
            shadowColor = AppConstants.DEFAULT_TEXT_COLOR,
            alignment = AppConstants.DEFAULT_FAVORITES_ALIGNMENT,
        )

        fun createSubtlePressColor(normalColor: Int): ColorStateList {
            val pressedColor = ColorUtils.setAlphaComponent(
                normalColor,
                AppConstants.PRESSED_STATE_ALPHA,
            )
            return ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_pressed),
                    intArrayOf(),
                ),
                intArrayOf(pressedColor, normalColor),
            )
        }
    }
}
