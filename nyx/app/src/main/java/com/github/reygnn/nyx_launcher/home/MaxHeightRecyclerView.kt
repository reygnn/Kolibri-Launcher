package com.github.reygnn.nyx_launcher.home

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * A [RecyclerView] that behaves as `wrap_content` up to [maxHeightPx], then caps its
 * height there and scrolls the overflow. Used by the folder overlay so a folder with
 * many members can't grow to fill the whole screen — leaving a tappable scrim margin
 * to dismiss it. [maxHeightPx] `<= 0` means no cap (plain `wrap_content`).
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    var maxHeightPx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val spec = if (maxHeightPx > 0) {
            MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        } else {
            heightSpec
        }
        super.onMeasure(widthSpec, spec)
    }
}
