package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
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

    private fun TestScope.newRepo(store: FakeDataStore): SwipeActionsRepositoryImpl =
        SwipeActionsRepositoryImpl.createForTesting(
            store, this.backgroundScope, SharingStarted.Companion.Lazily,
        )

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
