package com.github.reygnn.kolibri_launcher.ui.home

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo

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
 * [HomeGestureLayout.hasLongClickableDescendantAt] hit-test, which
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
class FavoritesAdapter(
    private val onAppClick: (AppInfo) -> Unit,
    private val onAppLongClick: (AppInfo) -> Unit,
) : ListAdapter<AppInfo, FavoritesAdapter.ViewHolder>(DIFF_CALLBACK) {

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
    )

    private var styling: Styling = INITIAL_STYLING

    fun setStyling(newStyling: Styling) {
        if (styling == newStyling) return
        styling = newStyling
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val button = Button(parent.context).apply {
            background = null
            includeFontPadding = false
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        return ViewHolder(button)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        try {
            val app = getItem(position)
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

    class ViewHolder(val button: Button) : RecyclerView.ViewHolder(button)

    private companion object {
        val INITIAL_STYLING = Styling(
            textSizePx = AppConstants.FALLBACK_TEXT_SIZE_PX,
            verticalPaddingPx = AppConstants.FALLBACK_VERTICAL_PADDING_PX,
            horizPaddingPx = AppConstants.FALLBACK_DIMEN_PX,
            isBold = AppConstants.FALLBACK_FONT_BOLD,
            textColor = AppConstants.DEFAULT_TEXT_COLOR,
            shadowColor = AppConstants.DEFAULT_TEXT_COLOR,
        )

        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
                oldItem.componentName == newItem.componentName

            override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
                oldItem == newItem
        }

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
