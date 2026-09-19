package com.github.reygnn.launcher.testing

import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import org.hamcrest.Matcher

/**
 * Reads a scalar off the view with [id] on the main thread and returns it.
 *
 * A read-only escape hatch for assertions Espresso's matcher vocabulary can't
 * express — e.g. a rendered `TextView.textSize` in pixels, to prove a live
 * preview actually re-measured. The [reader] runs inside the ViewAction on the
 * main thread, so it observes the committed view state. The matched view must be
 * unique (seed a single item when probing a RecyclerView row).
 */
public fun probeFloat(id: Int, reader: (View) -> Float): Float =
    probeFloat(withId(id), reader)

/**
 * Matcher-based sibling of [probeFloat] for views with no stable id — e.g. a
 * favorite rendered as a programmatically-created Button. The [matcher] must
 * resolve to exactly one view.
 */
public fun probeFloat(matcher: Matcher<View>, reader: (View) -> Float): Float {
    val holder = FloatArray(1)
    onView(matcher).perform(object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)
        override fun getDescription(): String = "probe float on $matcher"
        override fun perform(uiController: UiController, view: View) {
            holder[0] = reader(view)
        }
    })
    return holder[0]
}
