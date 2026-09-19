package com.github.reygnn.launcher.testing

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewInteraction
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import org.hamcrest.CoreMatchers.not

/**
 * Common base for every page object. Holds ONLY synchronisation and
 * presence-detection helpers — no product behaviour, no R references (this
 * module is launcher-neutral; concrete pages pass their own view ids in).
 *
 * Each concrete page calls [assertOnPage] at the end of its init block so a
 * wrong state fails fast, at construction, with a page-named message — instead
 * of surfacing later as a confusing follow-on assertion.
 *
 * View ids are plain Ints (R.id.* values) rather than @IdRes-annotated: the
 * annotation would pull androidx.annotation into this module's compile surface
 * for no runtime benefit. Callers pass R.id.* as usual.
 */
public abstract class BasePage {

    /** Anchor view this page pins its presence on (an R.id.* value). */
    protected abstract val anchorId: Int

    /** Human name used in failure messages. */
    protected abstract val name: String

    /** Call at the end of each concrete page's init block. */
    protected fun assertOnPage(): Long =
        waitForDisplayed(anchorId) { "Not on page '$name' — anchor id=$anchorId never displayed" }

    /** Bounded wait until [id] is displayed. Returns elapsed ms. */
    protected fun waitForDisplayed(
        id: Int,
        describe: () -> String = { "id=$id not displayed" },
    ): Long = awaitUntil(timeoutMs = 5_000, describe = describe) {
        runCatching { onView(withId(id)).check(matches(isDisplayed())) }.isSuccess
    }

    /**
     * Bounded wait until [id] is gone / not displayed. Returns elapsed ms.
     *
     * Detects a visibility-GONE dismissal (the view stays in the hierarchy):
     * `matches(not(isDisplayed()))` succeeds for a present-but-hidden view. It
     * does NOT detect a view REMOVED from the tree — `onView(withId)` then
     * throws `NoMatchingViewException`, which the poll swallows as "not gone yet",
     * so the wait runs to timeout. Pages that dismiss by detaching their anchor
     * must wait on `doesNotExist()` instead.
     */
    protected fun waitForGone(
        id: Int,
        describe: () -> String = { "id=$id still displayed" },
    ): Long = awaitUntil(timeoutMs = 5_000, describe = describe) {
        runCatching { onView(withId(id)).check(matches(not(isDisplayed()))) }.isSuccess
    }

    /** Convenience: an Espresso interaction on [id]. */
    protected fun view(id: Int): ViewInteraction = onView(withId(id))
}
