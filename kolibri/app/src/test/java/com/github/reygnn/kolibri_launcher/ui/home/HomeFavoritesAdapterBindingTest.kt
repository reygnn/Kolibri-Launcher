package com.github.reygnn.kolibri_launcher.ui.home

import com.github.reygnn.kolibri_launcher.domain.model.SettingsDefaults

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.kolibri_launcher.ui.appdrawer.AppDrawerAdapter
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric coverage for the AUDIT-14 F3 parts 1 (payload rebind) and 3
 * (hoisted listeners) refactor of [HomeFavoritesAdapter].
 *
 * These pin what a JVM/Robolectric test CAN see: the hit-test signal
 * (`isLongClickable` on the button, not the container), that the payload path
 * re-applies styling without clobbering the text, and that a real click routes
 * the correct item via `bindingAdapterPosition`. The true touch-priority
 * contract (favorite long-press vs. the wrapper's customize dialog through
 * [HomeGestureLayout]) is device behavior and stays an on-device check.
 */
@RunWith(RobolectricTestRunner::class)
class HomeFavoritesAdapterBindingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val parent = FrameLayout(context)

    private val camera = AppInfo(
        originalName = "Camera",
        displayName = "Camera",
        packageName = "com.cam",
        className = "MainActivity",
    )

    private fun styling(textSizePx: Float, bold: Boolean, color: Int) = HomeFavoritesAdapter.Styling(
        textSizePx = textSizePx,
        verticalPaddingPx = 5,
        horizPaddingPx = 7,
        isBold = bold,
        textColor = color,
        shadowColor = 0xFF000000.toInt(),
        outlineWidthPx = 3f,
        alignment = SettingsDefaults.DEFAULT_FAVORITES_ALIGNMENT,
    )

    @Test
    fun `listeners are wired at creation on the button, not the container (hit-test signal)`() {
        val adapter = HomeFavoritesAdapter(onAppClick = {}, onAppLongClick = {})

        val holder = adapter.onCreateViewHolder(parent, 0)

        // Part 3: listeners hoisted into init -> the button carries the click /
        // long-press signal WITHOUT any bind having run.
        assertThat(holder.button.isClickable).isTrue()
        assertThat(holder.button.isLongClickable).isTrue()
        // The container must stay non-long-clickable so the empty row space is
        // the wrapper's long-press area (the contract onCreateViewHolder protects).
        assertThat((holder.itemView as FrameLayout).isLongClickable).isFalse()
    }

    @Test
    fun `full bind sets the text and applies styling`() {
        val adapter = HomeFavoritesAdapter(onAppClick = {}, onAppLongClick = {})
        adapter.submitList(listOf(camera)) // empty -> list commits synchronously
        val stylingA = styling(textSizePx = 40f, bold = true, color = 0xFF112233.toInt())
        adapter.setStyling(stylingA)

        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)

        assertThat(holder.button.text.toString()).isEqualTo("Camera")
        assertThat(holder.button.textSize).isWithin(0.5f).of(40f)
        assertThat(holder.button.typeface).isEqualTo(Typeface.DEFAULT_BOLD)
        assertThat(holder.button.textColors.defaultColor).isEqualTo(stylingA.textColor)
    }

    @Test
    fun `payload rebind re-applies styling and keeps the text (partial rebind)`() {
        val adapter = HomeFavoritesAdapter(onAppClick = {}, onAppLongClick = {})
        adapter.submitList(listOf(camera))
        adapter.setStyling(styling(textSizePx = 40f, bold = true, color = 0xFF112233.toInt()))

        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        assertThat(holder.button.textSize).isWithin(0.5f).of(40f)

        // A styling change flows through the payload override (part 1). Use the
        // REAL STYLING_PAYLOAD: after AUDIT-17 F1 only that instance takes the
        // styling-only path; any other payload falls through to the full bind.
        val stylingB = styling(textSizePx = 60f, bold = false, color = 0xFF445566.toInt())
        adapter.setStyling(stylingB)
        adapter.onBindViewHolder(holder, 0, mutableListOf(HomeFavoritesAdapter.STYLING_PAYLOAD))

        assertThat(holder.button.textSize).isWithin(0.5f).of(60f)
        assertThat(holder.button.typeface).isEqualTo(Typeface.DEFAULT)
        assertThat(holder.button.textColors.defaultColor).isEqualTo(stylingB.textColor)
        // Text is untouched by the payload path.
        assertThat(holder.button.text.toString()).isEqualTo("Camera")
    }

    @Test
    fun `name-change payload falls through to the full bind and refreshes the text (F1)`() {
        // AUDIT-17 F1: the shared AppInfoDiffCallback emits PAYLOAD_NAME_CHANGE on a
        // rename (same componentName, new displayName) through the SAME payload
        // entry point as STYLING_PAYLOAD. The old override treated any non-empty
        // payload as styling-only, so the renamed favorite kept its old name.
        val adapter = HomeFavoritesAdapter(onAppClick = {}, onAppLongClick = {})
        val renamed = camera.copy(displayName = "Cam") // same componentName, new name
        adapter.submitList(listOf(renamed)) // empty -> commits synchronously
        adapter.setStyling(styling(textSizePx = 40f, bold = true, color = 0xFF112233.toInt()))

        val holder = adapter.onCreateViewHolder(parent, 0)
        // Simulate the holder currently showing the pre-rename text.
        holder.button.text = "Camera"

        adapter.onBindViewHolder(holder, 0, mutableListOf<Any>(AppDrawerAdapter.PAYLOAD_NAME_CHANGE))

        // Full bind ran -> text refreshed to the current item's displayName.
        assertThat(holder.button.text.toString()).isEqualTo("Cam")
    }

    @Test
    fun `empty payload falls through to the full bind`() {
        val adapter = HomeFavoritesAdapter(onAppClick = {}, onAppLongClick = {})
        adapter.submitList(listOf(camera))
        adapter.setStyling(styling(textSizePx = 40f, bold = true, color = 0xFF112233.toInt()))

        val holder = adapter.onCreateViewHolder(parent, 0)
        // Empty payload -> super -> full onBindViewHolder sets the text too.
        adapter.onBindViewHolder(holder, 0, mutableListOf())

        assertThat(holder.button.text.toString()).isEqualTo("Camera")
        assertThat(holder.button.textSize).isWithin(0.5f).of(40f)
    }

    @Test
    fun `a click on a bound row routes the correct item via bindingAdapterPosition`() {
        var clicked: AppInfo? = null
        var longClicked: AppInfo? = null
        val adapter = HomeFavoritesAdapter(
            onAppClick = { clicked = it },
            onAppLongClick = { longClicked = it },
        )
        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
        }
        adapter.submitList(listOf(camera))

        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
        )
        recyclerView.layout(0, 0, 1000, 2000)

        val container = recyclerView.getChildAt(0) as FrameLayout
        val button = container.getChildAt(0) as Button

        button.performClick()
        assertThat(clicked).isSameInstanceAs(camera)

        assertThat(button.performLongClick()).isTrue()
        assertThat(longClicked).isSameInstanceAs(camera)
    }

    @Test
    fun `a long click on an unbound holder fires nothing`() {
        var longClicked: AppInfo? = null
        val adapter = HomeFavoritesAdapter(
            onAppClick = {},
            onAppLongClick = { longClicked = it },
        )
        val holder = adapter.onCreateViewHolder(parent, 0)

        // Not attached to a RecyclerView -> bindingAdapterPosition == NO_POSITION.
        val consumed = holder.button.performLongClick()

        assertThat(consumed).isFalse()
        assertThat(longClicked).isNull()
    }
}
