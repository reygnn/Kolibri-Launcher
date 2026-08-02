package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith

/**
 * Impl-level tests for the reconcile path (RECONCILE_FIX_SPEC §6.6/§6.7). Swipe
 * had no impl-test file before — the impl was covered only by the contract. The
 * two load-bearing cases here are the fail-closed edit propagation and the
 * slot-keyed VALUE guard (the round-1 high-severity finding).
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
    fun `reconcileSwipeActions - when DataStore edit fails - propagates (fail-closed)`() = runTest {
        val store = FakeDataStore()
        store.setInitialData(preferencesOf(leftKey to "com.app1/Component"))
        val repo = newRepo(store)
        store.makeEditFail()
        // com.app1 is an orphan (installed is com.other) and the predicate reports
        // it absent, so the edit is attempted and must propagate (no swallow, §6.6).
        assertFailsWith<IOException> {
            repo.reconcileSwipeActions(listOf("com.other/Component")) { false }
        }
    }

    @Test
    fun `reconcileSwipeActions - when the candidate read fails - propagates (fail-closed)`() = runTest {
        // The candidate read is fail-CLOSED (dataStore.data.first(), not the
        // fail-open shared swipe flow): a read error propagates so the caller's
        // runCleanup skips the store and deletes nothing. A fail-open read would
        // yield empty -> no candidate -> "nothing deleted" too, so only asserting
        // the throw distinguishes fail-closed from the M1 regression (§6.1).
        val store = FakeDataStore()
        store.setInitialData(preferencesOf(leftKey to "com.app1/Component"))
        val repo = newRepo(store)
        store.makeReadFail()
        assertFailsWith<IOException> {
            repo.reconcileSwipeActions(listOf("com.other/Component")) { false }
        }
    }

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
        // The launch read is fail-OPEN (unlike the fail-closed reconcile path): a
        // transient IOException yields null -> NoAction, never a wrong app. This
        // is the only fail-open read left in the repo since the swipe flows were
        // removed, so it is pinned here directly.
        val store = FakeDataStore()
        store.setInitialData(preferencesOf(leftKey to "com.app/Component"))
        val repo = newRepo(store)
        store.makeReadFail()
        Assert.assertNull(repo.getSwipeActionComponent(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT))
    }

    @Test
    fun `reconcileSwipeActions - value guard - a slot reassigned during the presence check survives`() = runTest {
        val absent = "com.gone/Component"      // in LEFT at read time, verified absent
        val installed = "com.here/Component"   // reassigned into LEFT during the check
        val store = FakeDataStore()
        store.setInitialData(preferencesOf(leftKey to absent))
        val repo = newRepo(store)

        repo.reconcileSwipeActions(listOf(installed)) { component ->
            // The presence check runs between the candidate read and the edit.
            // Simulate the window write that reassigns LEFT to a still-installed
            // app, then report the original orphan gone. FakeDataStore serializes
            // writes, so this reassignment persists before the reconcile edit
            // re-reads the slot.
            if (component == absent) {
                repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, installed)
            }
            false
        }

        // Value-guard (§2/§5): the edit re-reads LEFT, finds `installed` (never
        // verified-absent), and keeps it — a blind remove(LEFT) would clobber it.
        Assert.assertEquals(installed, store.data.first()[leftKey])
    }
}
