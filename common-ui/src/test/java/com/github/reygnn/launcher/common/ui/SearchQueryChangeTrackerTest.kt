package com.github.reygnn.launcher.common.ui

import com.google.common.truth.Truth.assertThat
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
        assertThat(tracker.onQueryEmitted("cas")).isFalse()
    }

    @Test
    fun `changed value is a user change`() {
        tracker.onQueryEmitted("ca")
        assertThat(tracker.onQueryEmitted("cas")).isTrue()
    }

    @Test
    fun `repeated value is not a user change - the resume replay case`() {
        // User typed "cas" earlier (2 matches, no launch)...
        tracker.onQueryEmitted("")
        tracker.onQueryEmitted("cas")
        // ...goes to App Info, uninstalls one match, returns: the collector
        // re-subscribes and the StateFlow replays "cas". This must NOT count as
        // a change, so no auto-launch fires on the now-single match.
        assertThat(tracker.onQueryEmitted("cas")).isFalse()
    }

    @Test
    fun `genuine typing sequence reports every keystroke as a change`() {
        // Fresh drawer: first emission "" is the replay.
        assertThat(tracker.onQueryEmitted("")).isFalse()
        // Each subsequent keystroke is a real change → auto-launch may fire.
        assertThat(tracker.onQueryEmitted("c")).isTrue()
        assertThat(tracker.onQueryEmitted("ca")).isTrue()
        assertThat(tracker.onQueryEmitted("cas")).isTrue()
    }

    @Test
    fun `clearing the query is a user change`() {
        tracker.onQueryEmitted("cas")
        // Deleting the search text is a deliberate user action, not a replay.
        assertThat(tracker.onQueryEmitted("")).isTrue()
    }

    @Test
    fun `reset makes the next emission a replay again`() {
        tracker.onQueryEmitted("")
        tracker.onQueryEmitted("cas")
        // onDestroyView -> reset(): the new collector after recreation must
        // treat the restored query as a replay, not a change.
        tracker.reset()
        assertThat(tracker.onQueryEmitted("cas")).isFalse()
    }

    @Test
    fun `blank replay after reset does not auto-launch`() {
        // Defensive: empty-query replay must also be a non-change (an empty
        // query never auto-launches anyway, but the gate stays consistent).
        tracker.reset()
        assertThat(tracker.onQueryEmitted("")).isFalse()
    }
}
