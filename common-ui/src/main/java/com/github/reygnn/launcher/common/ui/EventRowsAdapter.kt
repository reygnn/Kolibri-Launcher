package com.github.reygnn.launcher.common.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.github.reygnn.launcher.core.timeinfo.TimeBasedEventType
import com.github.reygnn.launcher.core.timeinfo.TimeEventFormatter
import com.google.android.material.color.MaterialColors

/** Alpha applied to `colorOnSurface` for the today/tomorrow separator line. */
private const val EVENTS_SEPARATOR_ALPHA = 90

/**
 * Shared `ListView` adapter for the upcoming-events dialog, used by both
 * launchers so the two-view-type layout, the tinted leading icon, and the
 * today/tomorrow separator cannot drift between them. [rows] is the single
 * source of truth for both rendering and click routing (positions never drift);
 * the labels are pre-built once via [TimeEventFormatter.buildRowLabels].
 *
 * Rows are inflated against `parent.context` — the dialog's themed context —
 * so `?attr/colorOnSurface` resolves against the dialog overlay (which may be
 * wallpaper-aware) rather than the Activity theme, keeping the label + its
 * icon tint on the same adaptive-contrast colour.
 *
 * App-specific bits are parameters: the row layout ([itemRowLayout] +
 * [itemLabelId], where [View.NO_ID] means the inflated root *is* the `TextView`),
 * the calendar icon ([calendarIcon] — the two apps ship different vectors), the
 * separator layout, and the icon metrics.
 */
class EventRowsAdapter(
    private val rows: List<TimeEventFormatter.EventRow>,
    private val rowLabels: List<CharSequence?>,
    @param:LayoutRes private val itemRowLayout: Int,
    @param:IdRes private val itemLabelId: Int,
    @param:DrawableRes private val alarmIcon: Int,
    @param:DrawableRes private val calendarIcon: Int,
    private val iconSizePx: Int,
    private val iconPaddingPx: Int,
    @param:LayoutRes private val dividerLayout: Int,
    @param:IdRes private val dividerLineId: Int,
) : BaseAdapter() {

    override fun getCount(): Int = rows.size
    override fun getItem(position: Int): Any = rows[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getViewTypeCount(): Int = 2

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is TimeEventFormatter.EventRow.Item) 0 else 1

    // The separator is not selectable, so a tap can never land on it.
    override fun areAllItemsEnabled(): Boolean = false
    override fun isEnabled(position: Int): Boolean =
        rows[position] is TimeEventFormatter.EventRow.Item

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val inflater = LayoutInflater.from(parent.context)
        return when (val row = rows[position]) {
            is TimeEventFormatter.EventRow.Item -> {
                val view = convertView ?: inflater.inflate(itemRowLayout, parent, false)
                val label: TextView =
                    if (itemLabelId == View.NO_ID) view as TextView else view.findViewById(itemLabelId)
                label.text = rowLabels[position]
                val iconRes = when (row.event.type) {
                    TimeBasedEventType.ALARM -> alarmIcon
                    TimeBasedEventType.CALENDAR -> calendarIcon
                }
                val icon = ContextCompat.getDrawable(parent.context, iconRes)?.mutate()?.apply {
                    setBounds(0, 0, iconSizePx, iconSizePx)
                    setTint(label.currentTextColor)
                }
                label.setCompoundDrawablesRelative(icon, null, null, null)
                label.compoundDrawablePadding = iconPaddingPx
                view
            }
            TimeEventFormatter.EventRow.TomorrowSeparator -> {
                val view = convertView ?: inflater.inflate(dividerLayout, parent, false)
                // The dialog theme overrides colorOnSurface (per wallpaper luminance)
                // but not a divider attr, so derive the line colour from onSurface at
                // reduced alpha.
                val separatorColor = ColorUtils.setAlphaComponent(
                    MaterialColors.getColor(
                        parent.context,
                        com.google.android.material.R.attr.colorOnSurface,
                        Color.GRAY,
                    ),
                    EVENTS_SEPARATOR_ALPHA,
                )
                view.findViewById<View>(dividerLineId).setBackgroundColor(separatorColor)
                view
            }
        }
    }
}
