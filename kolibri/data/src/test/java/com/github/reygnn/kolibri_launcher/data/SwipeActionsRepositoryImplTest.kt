package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Rule
import org.junit.Test

/**
 * Impl-level tests for the authoritative launch read. The swipe store no longer
 * reconciles (auto-prunes) — a dead slot is validated lazily on the swipe gesture
 * instead — so the only impl behaviour left to pin here is that
 * getSwipeActionComponent is a fresh, fail-open read straight from the store
 * (never a cache), which the launch path depends on.
 */
@ExperimentalCoroutinesApi
class SwipeActionsRepositoryImplTest {

    @get:Rule
    val timberRule = TimberRule()

    private val leftKey = stringPreferencesKey("swipe_left_app_component")
    private val rightKey = stringPreferencesKey("swipe_right_app_component")

    private fun newRepo(store: FakeDataStore): SwipeActionsRepositoryImpl =
        SwipeActionsRepositoryImpl(store)

    @Test
    fun `getSwipeActionComponent - reads the current stored value`() = runTest {
        // Swipe-stale-replay fix: the launch path reads the authoritative store
        // value via getSwipeActionComponent. Here the assignment is changed and
        // the launch read must return the NEW value — exactly the Home situation
        // while the Settings activity is open. This pins the fix's contract:
        // getSwipeActionComponent is an authoritative fresh read straight from
        // the store, never a cache.
        val store = FakeDataStore()
        store.setInitialData(preferencesOf(rightKey to "com.old/Component"))
        val repo = newRepo(store)

        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, "com.new/Component")

        Assert.assertEquals(
            "com.new/Component",
            repo.getSwipeActionComponent(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT),
        )
    }

    @Test
    fun `getSwipeActionComponent - returns null for an unassigned slot`() = runTest {
        val repo = newRepo(FakeDataStore())
        Assert.assertNull(repo.getSwipeActionComponent(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT))
    }

    @Test
    fun `getSwipeActionComponent - when the read fails - returns null (fail-open)`() = runTest {
        // The launch read is fail-OPEN: a transient IOException yields null ->
        // NoAction, never a wrong app.
        val store = FakeDataStore()
        store.setInitialData(preferencesOf(leftKey to "com.app/Component"))
        val repo = newRepo(store)
        store.makeReadFail()
        Assert.assertNull(repo.getSwipeActionComponent(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT))
    }
}
