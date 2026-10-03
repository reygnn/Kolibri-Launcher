package com.github.reygnn.launcher.feature.wallpaper

import com.github.reygnn.launcher.core.wallpaper.LayerTransform
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [WallpaperEditSession] (3a-3): pure and synchronous, so these tests need no coroutine and no
 * dispatcher — every transition is visible right after the call.
 */
class WallpaperEditSessionTest {

    private val a = "file:///w/a"
    private val b = "file:///w/b"
    private val c = "file:///w/c"

    private fun sessionShowing(vararg uris: String) = WallpaperEditSession().apply {
        onPersistedState(WallpaperState.multiLayer(uris.map { WallpaperLayerState(id = it, imageUri = it) }))
    }

    private val WallpaperEditSession.shown get() = state.value.layers.map { it.imageUri }

    // ---- outside writes (E4) ----

    @Test
    fun a_persisted_state_is_shown_outside_a_session_and_ignored_inside() {
        val session = sessionShowing(a)
        session.enter()
        session.removeLayer(0)

        val applied = session.onPersistedState(WallpaperState.single(uri = a)) // a stale save landing late

        assertThat(applied).isFalse()
        assertThat(session.shown).isEmpty() // the removed layer does not come back
    }

    @Test
    fun after_a_session_emissions_wait_for_the_resync_which_applies_the_persisted_state() {
        // A late emission delivered after commit (an older save) must not flash the old state; the
        // re-sync reads the persisted state after all of the session's writes and applies it.
        val session = sessionShowing(a, b)
        session.enter()
        session.removeLayer(0)
        session.commit()

        assertThat(session.onPersistedState(WallpaperState.single(uri = a))).isFalse()
        assertThat(session.shown).containsExactly(b)

        val persisted = WallpaperState.single(uri = c)
        assertThat(session.resync(persisted)).isTrue()
        assertThat(session.shown).containsExactly(c)
        assertThat(session.onPersistedState(WallpaperState.single(uri = a))).isTrue() // normal again
    }

    @Test
    fun a_resync_that_could_not_read_ends_the_wait_and_keeps_the_display() {
        val session = sessionShowing(a)
        session.enter()
        session.cancel()

        assertThat(session.resync(null)).isFalse()
        assertThat(session.shown).containsExactly(a)
        assertThat(session.onPersistedState(WallpaperState.single(uri = b))).isTrue()
    }

    @Test
    fun a_resync_landing_in_a_new_session_changes_nothing() {
        val session = sessionShowing(a)
        session.enter()
        session.commit()
        session.enter()

        assertThat(session.resync(WallpaperState.single(uri = b))).isFalse()
        assertThat(session.shown).containsExactly(a)
    }

    // ---- enter, commit, cancel ----

    @Test
    fun re_entering_a_running_session_keeps_its_snapshot() {
        // E3: overwriting the snapshot would make a cancel restore the already-edited state.
        val session = sessionShowing(a)
        session.enter()
        session.addLayer(b, session.rollbackGeneration)

        assertThat(session.enter()).isFalse()
        val end = session.cancel()

        assertThat(session.shown).containsExactly(a)
        assertThat(end.persist?.referencedUris).containsExactly(a)
        assertThat(end.deleteCandidates).containsExactly(b)
    }

    @Test
    fun commit_keeps_the_edit_and_hands_the_removed_files_to_the_store() {
        val session = sessionShowing(a, b)
        session.enter()
        session.removeLayer(0)

        val end = session.commit()

        assertThat(end.persist).isNull() // already persisted change by change
        assertThat(end.deleteCandidates).containsExactly(a)
        assertThat(end.finalState.referencedUris).containsExactly(b)
        assertThat(session.isEditMode.value).isFalse()
    }

    @Test
    fun cancel_restores_the_snapshot_synchronously_and_drops_the_focus_hint() {
        val session = sessionShowing(a)
        session.enter()
        session.addLayer(b, session.rollbackGeneration)
        assertThat(session.pendingFocusLayerId.value).isNotNull()

        session.cancel()

        assertThat(session.shown).containsExactly(a)
        assertThat(session.pendingFocusLayerId.value).isNull()
        assertThat(session.isEditMode.value).isFalse()
    }

    // ---- the rollback generation (E2) ----

    @Test
    fun an_add_still_copying_when_the_user_commits_is_applied() {
        // E2: nothing the user did disappears silently.
        val session = sessionShowing(a)
        session.enter()
        val generation = session.rollbackGeneration
        session.commit()

        val effect = session.addLayer(b, generation)

        assertThat(effect).isNotNull()
        assertThat(session.shown).containsExactly(a, b).inOrder()
    }

    @Test
    fun an_add_or_replace_still_copying_when_the_user_cancels_is_discarded() {
        val session = sessionShowing(a)
        session.enter()
        val generation = session.rollbackGeneration
        session.cancel()

        assertThat(session.addLayer(b, generation)).isNull()
        assertThat(session.replace(c, generation)).isNull()
        assertThat(session.shown).containsExactly(a)
    }

    // ---- replace ----

    @Test
    fun replacing_outside_a_session_deletes_the_old_files_after_the_save() {
        val session = sessionShowing(a, b)

        val effect = session.replace(c, session.rollbackGeneration)!!

        assertThat(effect.persist.referencedUris).containsExactly(c)
        assertThat(effect.deleteNow).containsExactly(a, b)
        assertThat(session.shown).containsExactly(c)
    }

    @Test
    fun replacing_inside_a_session_shows_the_new_image_and_defers_both_files() {
        // E4: the edit no longer waits for the emission to show the new image.
        val session = sessionShowing(a)
        session.enter()

        val effect = session.replace(b, session.rollbackGeneration)!!
        assertThat(effect.deleteNow).isEmpty()
        assertThat(session.shown).containsExactly(b)

        val cancelled = session.cancel()
        assertThat(session.shown).containsExactly(a)
        assertThat(cancelled.deleteCandidates).containsExactly(b) // the new copy goes again

        session.enter()
        session.replace(c, session.rollbackGeneration)
        assertThat(session.commit().deleteCandidates).containsExactly(a) // the old file goes at commit
    }

    // ---- image-change signal ----

    @Test
    fun an_image_change_is_reported_now_outside_and_at_commit_inside_a_session() {
        val session = sessionShowing(a)
        assertThat(session.noteImageChanged()).isTrue()

        session.enter()
        assertThat(session.noteImageChanged()).isFalse()
        assertThat(session.commit().imageChanged).isTrue()

        session.enter()
        session.noteImageChanged()
        assertThat(session.cancel().imageChanged).isFalse() // rolled back
    }

    // ---- the other changes ----

    @Test
    fun removing_outside_a_session_deletes_after_the_save_and_inside_waits_for_commit() {
        val outside = sessionShowing(a, b)
        assertThat(outside.removeLayer(0).deleteNow).containsExactly(a)

        val inside = sessionShowing(a, b)
        inside.enter()
        assertThat(inside.removeLayer(0).deleteNow).isEmpty()
        assertThat(inside.commit().deleteCandidates).containsExactly(a)
    }

    @Test
    fun swaps_and_transforms_change_only_the_state() {
        val session = sessionShowing(a, b)

        assertThat(session.swapLayers(0, 1).deleteNow).isEmpty()
        assertThat(session.shown).containsExactly(b, a).inOrder()

        session.saveLayerTransform(0, scale = 2f, translateX = 3f, translateY = 4f, captureSampleSize = 2)
        assertThat(session.state.value.layers[0].scale).isEqualTo(2f)
        assertThat(session.state.value.layers[0].captureSampleSize).isEqualTo(2)

        session.saveAllLayerTransforms(listOf(LayerTransform(1.5f, 0f, 0f, 1), LayerTransform(0.5f, 1f, 1f, 4)))
        assertThat(session.state.value.layers.map { it.scale }).containsExactly(1.5f, 0.5f).inOrder()
    }

    @Test
    fun the_single_transform_needs_a_wallpaper() {
        assertThat(WallpaperEditSession().saveSingleTransform(2f, 0f, 0f, null)).isNull()
        assertThat(sessionShowing(a).saveSingleTransform(2f, 0f, 0f, null)?.persist?.layers?.single()?.scale).isEqualTo(2f)
    }
}
