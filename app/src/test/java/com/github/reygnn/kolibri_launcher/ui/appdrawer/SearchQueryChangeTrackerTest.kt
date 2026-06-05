package com.github.reygnn.kolibri_launcher.ui.appdrawer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the auto-launch gate that fixes the "app launches by itself" bug:
 * a single-match query may only auto-launch on a genuine user keystroke, never
 * on a StateFlow replay of the current value when the search collector
 * re-subscribes (resume from App Info, rotation, process restore).
 *
 * [SearchQueryChangeTracker.onQueryEmitted] returns `true` only for a real
 * change; everything below pins that contract.
 */
class SearchQueryChangeTrackerTest {

    private lateinit var tracker: SearchQueryChangeTracker

    @Before
    fun setUp() {
        tracker = SearchQueryChangeTracker()
    }

    @Test
    fun `first emission is treated as replay - never a user change`() {
        // The very first value after (re)subscription is the StateFlow replay,
        // even when it is a non-blank one-match query. Must not auto-launch.
        assertFalse(tracker.onQueryEmitted("cas"))
    }

    @Test
    fun `changed value is a user change`() {
        tracker.onQueryEmitted("ca")
        assertTrue(tracker.onQueryEmitted("cas"))
    }

    @Test
    fun `repeated value is not a user change - the resume replay case`() {
        // User typed "cas" earlier (2 matches, no launch)...
        tracker.onQueryEmitted("")
        tracker.onQueryEmitted("cas")
        // ...goes to App Info, uninstalls one match, returns: the collector
        // re-subscribes and the StateFlow replays "cas". This must NOT count as
        // a change, so no auto-launch fires on the now-single match.
        assertFalse(tracker.onQueryEmitted("cas"))
    }

    @Test
    fun `genuine typing sequence reports every keystroke as a change`() {
        // Fresh drawer: first emission "" is the replay.
        assertFalse(tracker.onQueryEmitted(""))
        // Each subsequent keystroke is a real change → auto-launch may fire.
        assertTrue(tracker.onQueryEmitted("c"))
        assertTrue(tracker.onQueryEmitted("ca"))
        assertTrue(tracker.onQueryEmitted("cas"))
    }

    @Test
    fun `clearing the query is a user change`() {
        tracker.onQueryEmitted("cas")
        // Deleting the search text is a deliberate user action, not a replay.
        assertTrue(tracker.onQueryEmitted(""))
    }

    @Test
    fun `reset makes the next emission a replay again`() {
        tracker.onQueryEmitted("")
        tracker.onQueryEmitted("cas")
        // onDestroyView -> reset(): the new collector after recreation must
        // treat the restored query as a replay, not a change.
        tracker.reset()
        assertFalse(tracker.onQueryEmitted("cas"))
    }

    @Test
    fun `blank replay after reset does not auto-launch`() {
        // Defensive: empty-query replay must also be a non-change (an empty
        // query never auto-launches anyway, but the gate stays consistent).
        tracker.reset()
        assertFalse(tracker.onQueryEmitted(""))
    }
}
