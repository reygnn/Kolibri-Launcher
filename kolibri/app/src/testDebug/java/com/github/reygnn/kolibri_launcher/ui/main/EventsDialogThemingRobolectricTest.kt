package com.github.reygnn.kolibri_launcher.ui.main

import android.content.Intent
import android.view.ContextThemeWrapper
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.kolibri_launcher.HiltTestActivity
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.launcher.common.ui.EventRowsAdapter
import com.github.reygnn.launcher.core.timeinfo.TimeBasedEvent
import com.github.reygnn.launcher.core.timeinfo.TimeBasedEventType
import com.github.reygnn.launcher.core.timeinfo.TimeEventFormatter
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the theming invariant the Tier-2 events-dialog dedup relies on
 * ([EventRowsAdapter] inflates its rows against `parent.context`, the dialog's
 * own ListView context, instead of an explicit `ContextThemeWrapper(activity,
 * wallpaperAwareDialogStyle())`). That is only correct because the dialog is
 * built with the wallpaper-aware overlay, so `parent.context` IS that overlay —
 * and `?attr/colorOnSurface` (the row label + icon-tint colour) resolves against
 * it, not the Activity / system theme. If a future change builds the events
 * dialog without the overlay, or Material stops propagating the overlay to the
 * ListView, this goes red instead of silently shipping invisible dialog text on
 * a device whose wallpaper luminance diverges from system day/night.
 *
 * Needs a real Activity + a real AlertDialog + real theme resolution, so it runs
 * under Robolectric (Rule 10: framework behaviour a JVM test cannot reproduce),
 * hosted in the project's [HiltTestActivity]. The two overlays mirror
 * `MainActivity.wallpaperAwareDialogStyle()`.
 */
@RunWith(RobolectricTestRunner::class)
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
class EventsDialogThemingRobolectricTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private fun launchHost(): ActivityScenario<HiltTestActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), HiltTestActivity::class.java),
        )

    private fun eventsAdapter() = EventRowsAdapter(
        rows = listOf(TimeEventFormatter.EventRow.Item(TimeBasedEvent(0L, "Event", TimeBasedEventType.CALENDAR))),
        rowLabels = listOf("Event"),
        itemRowLayout = R.layout.item_recent_app,
        itemLabelId = R.id.recent_app_name,
        alarmIcon = R.drawable.ic_alarm,
        calendarIcon = R.drawable.ic_calendar,
        iconSizePx = 24,
        iconPaddingPx = 12,
        dividerLayout = R.layout.item_events_divider,
        dividerLineId = R.id.events_divider_line,
    )

    private fun onSurfaceOf(context: android.content.Context): Int =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0)

    private fun assertRowsTrackOverlay(styleRes: Int) {
        launchHost().use { scenario ->
            lateinit var activity: HiltTestActivity
            scenario.onActivity { activity = it }

            val adapter = eventsAdapter()
            val dialog = MaterialAlertDialogBuilder(activity, styleRes)
                .setAdapter(adapter, null)
                .create()
            dialog.show()

            val listView = dialog.listView
            assertNotNull("AlertDialog built with setAdapter must expose a ListView", listView)

            // The colour the code USED to compute explicitly, and the colour the dialog's
            // ListView context now resolves — must match (parent.context ≡ the overlay).
            val expected = onSurfaceOf(ContextThemeWrapper(activity, styleRes))
            assertEquals(
                "dialog ListView context must resolve colorOnSurface against the wallpaper-aware overlay",
                expected,
                onSurfaceOf(listView.context),
            )

            // Stronger: a real inflated row's label colour tracks that overlay too
            // (item_recent_app's textColor is ?attr/colorOnSurface).
            val row = adapter.getView(0, null, listView)
            val label = row.findViewById<TextView>(R.id.recent_app_name)
            assertEquals(
                "event row label colour must resolve against the overlay, not the Activity theme",
                expected,
                label.currentTextColor,
            )

            dialog.dismiss()
        }
    }

    @Test
    fun `light overlay - dialog rows resolve colorOnSurface against the light overlay`() {
        assertRowsTrackOverlay(R.style.CustomAlertDialog_Light)
    }

    @Test
    fun `dark overlay - dialog rows resolve colorOnSurface against the dark overlay`() {
        assertRowsTrackOverlay(R.style.CustomAlertDialog_Dark)
    }

    @Test
    fun `the two wallpaper-aware overlays resolve to different colorOnSurface`() {
        // Guards against a vacuous suite: if Light and Dark resolved to the same
        // onSurface, the per-overlay assertions above could pass without the
        // overlay actually driving the colour.
        launchHost().use { scenario ->
            lateinit var activity: HiltTestActivity
            scenario.onActivity { activity = it }
            assertNotEquals(
                onSurfaceOf(ContextThemeWrapper(activity, R.style.CustomAlertDialog_Light)),
                onSurfaceOf(ContextThemeWrapper(activity, R.style.CustomAlertDialog_Dark)),
            )
        }
    }
}
