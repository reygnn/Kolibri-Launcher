package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * ============================================================================
 * SWIPE ACTIONS REPOSITORY — CONTRACT TEST
 * ============================================================================
 *
 * Siehe [FavoritesRepositoryContract] für Hintergrund und Konventionen
 * (warum abstract class, warum keine Subklassen-Rules, warum kein eigener
 * TestScope).
 *
 * Das [SwipeActionsRepository]-Interface ist klein, aber hat mehrere
 * lohnende Stellen für einen Contract:
 *
 * State is read back through the single authoritative read surface
 * [SwipeActionsRepository.getSwipeActionComponent] — there is no longer a
 * hot/shared read flow.
 *
 *   - **Slot independence:** a `setSwipeAction(LEFT, …)` must not affect the
 *     RIGHT slot — and vice versa. This is the most important property the
 *     contract pins: the two slots are separate values with separate
 *     persistence.
 *
 *   - **NONE slot:** explicitly a no-op. Neither LEFT nor RIGHT may change.
 *     The impl logs + returns early, the fake has an empty branch — without a
 *     contract one of the two could later do something by accident.
 *
 *   - **null semantics:** `setSwipeAction(slot, null)` clears the slot →
 *     [getSwipeActionComponent] returns `null` again. Consistent with "no
 *     value persisted".
 *
 * NICHT IM CONTRACT (impl-spezifisch):
 *   - Der Manager wirft Exceptions in `setSwipeAction` weiter (für `launchSafe`
 *     im ViewModel). Der Fake nicht. Fehlerverhalten ist Implementierungs-
 *     Detail, nicht Interface-Garantie.
 *
 * @see FakeSwipeActionsRepositoryContractTest
 * @see SwipeActionsRepositoryImplContractTest
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class SwipeActionsRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    protected abstract fun createRepository(): SwipeActionsRepository

    private val appA = "com.example.a/.MainActivity"
    private val appB = "com.example.b/.MainActivity"

    // Read helpers — the authoritative read surface for both slots.
    private suspend fun SwipeActionsRepository.left() =
        getSwipeActionComponent(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)

    private suspend fun SwipeActionsRepository.right() =
        getSwipeActionComponent(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT)

    // ---------- Initial State ----------

    @Test
    fun `fresh repository returns null for LEFT slot`() = runTest {
        val repo = createRepository()
        assertNull(repo.left())
    }

    @Test
    fun `fresh repository returns null for RIGHT slot`() = runTest {
        val repo = createRepository()
        assertNull(repo.right())
    }

    // ---------- setSwipeAction LEFT ----------

    @Test
    fun `setSwipeAction LEFT reflects in the LEFT slot`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        assertEquals(appA, repo.left())
    }

    @Test
    fun `setSwipeAction LEFT does not affect the RIGHT slot`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        assertNull(repo.right())
    }

    @Test
    fun `setSwipeAction LEFT overwrites previous LEFT value`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appB)
        assertEquals(appB, repo.left())
    }

    @Test
    fun `setSwipeAction LEFT with null clears the slot`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, null)
        assertNull(repo.left())
    }

    // ---------- setSwipeAction RIGHT (Spiegel von LEFT) ----------

    @Test
    fun `setSwipeAction RIGHT reflects in the RIGHT slot`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appA)
        assertEquals(appA, repo.right())
    }

    @Test
    fun `setSwipeAction RIGHT does not affect the LEFT slot`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appA)
        assertNull(repo.left())
    }

    @Test
    fun `setSwipeAction RIGHT with null clears the slot`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appA)
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, null)
        assertNull(repo.right())
    }

    // ---------- Slot-Unabhängigkeit ----------

    /**
     * Beide Slots gleichzeitig setzen → beide liefern den jeweils eigenen
     * Wert. Das ist das eigentliche Versprechen: zwei unabhängige Slots, kein
     * versehentliches Übersprechen.
     */
    @Test
    fun `LEFT and RIGHT slots can hold different values simultaneously`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appB)

        assertEquals(appA, repo.left())
        assertEquals(appB, repo.right())
    }

    /**
     * Beide Slots können auf denselben Wert zeigen. Ist ein durchaus
     * realistisches User-Setup ("dieselbe App auf beide Wischrichtungen").
     */
    @Test
    fun `LEFT and RIGHT slots can hold the same value`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appA)

        assertEquals(appA, repo.left())
        assertEquals(appA, repo.right())
    }

    @Test
    fun `clearing LEFT does not affect RIGHT`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appB)

        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, null)

        assertNull(repo.left())
        assertEquals(appB, repo.right())
    }

    // ---------- NONE-Slot ----------

    /**
     * `SwipeSlot.NONE` ist semantisch "kein Slot" und muss als No-Op behandelt
     * werden — egal ob ein componentName mitkommt oder nicht. Das ist eine
     * harte Garantie an den Aufrufer: NONE ist niemals destruktiv.
     */
    @Test
    fun `setSwipeAction NONE does not modify either slot`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appB)

        repo.setSwipeAction(SwipeSlot.NONE, "irrelevant")
        repo.setSwipeAction(SwipeSlot.NONE, null)

        assertEquals(appA, repo.left())
        assertEquals(appB, repo.right())
    }

    @Test
    fun `setSwipeAction NONE on empty repository changes nothing`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.NONE, appA)

        assertNull(repo.left())
        assertNull(repo.right())
    }

    // ---------- reconcileSwipeActions ----------

    @Test
    fun `reconcileSwipeActions clears a slot whose app is not installed`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appB)

        repo.reconcileSwipeActions(listOf(appB)) { false } // appA uninstalled

        assertNull(repo.left())
        assertEquals(appB, repo.right())
    }

    @Test
    fun `reconcileSwipeActions keeps both slots when both installed`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appB)

        repo.reconcileSwipeActions(listOf(appA, appB)) { false }

        assertEquals(appA, repo.left())
        assertEquals(appB, repo.right())
    }

    @Test
    fun `reconcileSwipeActions with empty installed list clears both slots`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appB)

        repo.reconcileSwipeActions(emptyList()) { false }

        assertNull(repo.left())
        assertNull(repo.right())
    }

    @Test
    fun `reconcileSwipeActions keeps a slot the presence check reports present`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        // appA absent from the load but the presence predicate vetoes clearing it.
        repo.reconcileSwipeActions(emptyList()) { it == appA }
        assertEquals(appA, repo.left())
    }

    @Test
    fun `reconcileSwipeActions on fresh repository is a no-op`() = runTest {
        val repo = createRepository()
        repo.reconcileSwipeActions(listOf(appA)) { false }
        assertNull(repo.left())
        assertNull(repo.right())
    }

    // ---------- getSwipeActionComponent (authoritative read for the launch path) ----------

    @Test
    fun `getSwipeActionComponent returns the assigned LEFT component`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        assertEquals(appA, repo.getSwipeActionComponent(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT))
    }

    @Test
    fun `getSwipeActionComponent returns the assigned RIGHT component`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appB)
        assertEquals(appB, repo.getSwipeActionComponent(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT))
    }

    @Test
    fun `getSwipeActionComponent reflects the LATEST assignment, never the previous one`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appA)
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appB)
        // The core guarantee behind the swipe-stale-replay fix: the launch read
        // returns the newest value, not the one it replaced.
        assertEquals(appB, repo.getSwipeActionComponent(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT))
    }

    @Test
    fun `getSwipeActionComponent returns null for an unassigned slot`() = runTest {
        val repo = createRepository()
        assertNull(repo.getSwipeActionComponent(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT))
    }

    @Test
    fun `getSwipeActionComponent returns null for NONE`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        assertNull(repo.getSwipeActionComponent(SwipeSlot.NONE))
    }

    // ---------- purgeRepository ----------

    @Test
    fun `purgeRepository clears both slots`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appB)

        repo.purgeRepository()

        assertNull(repo.left())
        assertNull(repo.right())
    }

    @Test
    fun `purgeRepository on fresh repository is safe`() = runTest {
        val repo = createRepository()
        repo.purgeRepository()
        assertNull(repo.left())
        assertNull(repo.right())
    }
}
