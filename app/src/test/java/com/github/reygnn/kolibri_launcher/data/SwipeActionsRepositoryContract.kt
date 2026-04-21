package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
 *   - **Slot-Unabhängigkeit:** Ein `setSwipeAction(LEFT, …)` darf den Flow
 *     für RIGHT nicht beeinflussen — und umgekehrt. Das ist die wichtigste
 *     Eigenschaft, die der Contract festschreibt: die beiden Slots sind
 *     separate Werte mit separater Persistenz.
 *
 *   - **NONE-Slot:** Ist explizit ein No-Op. Weder LEFT noch RIGHT dürfen
 *     sich ändern. Manager loggt + returnt früh, Fake hat einen leeren
 *     Branch — ohne Contract könnte einer der beiden später aus Versehen
 *     etwas tun.
 *
 *   - **null-Semantik:** `setSwipeAction(slot, null)` löscht den Slot →
 *     der Flow emittiert wieder `null`. Konsistent mit "kein Wert
 *     persistiert".
 *
 * NICHT IM CONTRACT (Manager-spezifisch):
 *   - Der Manager wirft Exceptions in `setSwipeAction` weiter (für `launchSafe`
 *     im ViewModel). Der Fake nicht. Fehlerverhalten ist Implementierungs-
 *     Detail, nicht Interface-Garantie.
 *   - `shareIn`-Lifecycle gehört in einen `SwipeActionsManagerShareInTest`
 *     (analog zu `FavoritesManagerShareInTest`), falls jemals nötig.
 *
 * @see FakeSwipeActionsRepositoryContractTest
 * @see SwipeActionsManagerContractTest
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

    // ---------- Initial State ----------

    @Test
    fun `fresh repository emits null for swipeLeftAppFlow`() = runTest {
        val repo = createRepository()
        assertNull(repo.swipeLeftAppFlow.first())
    }

    @Test
    fun `fresh repository emits null for swipeRightAppFlow`() = runTest {
        val repo = createRepository()
        assertNull(repo.swipeRightAppFlow.first())
    }

    // ---------- setSwipeAction LEFT ----------

    @Test
    fun `setSwipeAction LEFT reflects in swipeLeftAppFlow`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        assertEquals(appA, repo.swipeLeftAppFlow.first())
    }

    @Test
    fun `setSwipeAction LEFT does not affect swipeRightAppFlow`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        assertNull(repo.swipeRightAppFlow.first())
    }

    @Test
    fun `setSwipeAction LEFT overwrites previous LEFT value`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appB)
        assertEquals(appB, repo.swipeLeftAppFlow.first())
    }

    @Test
    fun `setSwipeAction LEFT with null clears the slot`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, null)
        assertNull(repo.swipeLeftAppFlow.first())
    }

    // ---------- setSwipeAction RIGHT (Spiegel von LEFT) ----------

    @Test
    fun `setSwipeAction RIGHT reflects in swipeRightAppFlow`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appA)
        assertEquals(appA, repo.swipeRightAppFlow.first())
    }

    @Test
    fun `setSwipeAction RIGHT does not affect swipeLeftAppFlow`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appA)
        assertNull(repo.swipeLeftAppFlow.first())
    }

    @Test
    fun `setSwipeAction RIGHT with null clears the slot`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appA)
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, null)
        assertNull(repo.swipeRightAppFlow.first())
    }

    // ---------- Slot-Unabhängigkeit ----------

    /**
     * Beide Slots gleichzeitig setzen → beide Flows liefern den jeweils eigenen
     * Wert. Das ist das eigentliche Versprechen: zwei unabhängige Slots, kein
     * versehentliches Übersprechen.
     */
    @Test
    fun `LEFT and RIGHT slots can hold different values simultaneously`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appB)

        assertEquals(appA, repo.swipeLeftAppFlow.first())
        assertEquals(appB, repo.swipeRightAppFlow.first())
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

        assertEquals(appA, repo.swipeLeftAppFlow.first())
        assertEquals(appA, repo.swipeRightAppFlow.first())
    }

    @Test
    fun `clearing LEFT does not affect RIGHT`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appB)

        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, null)

        assertNull(repo.swipeLeftAppFlow.first())
        assertEquals(appB, repo.swipeRightAppFlow.first())
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

        assertEquals(appA, repo.swipeLeftAppFlow.first())
        assertEquals(appB, repo.swipeRightAppFlow.first())
    }

    @Test
    fun `setSwipeAction NONE on empty repository changes nothing`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.NONE, appA)

        assertNull(repo.swipeLeftAppFlow.first())
        assertNull(repo.swipeRightAppFlow.first())
    }

    // ---------- purgeRepository ----------

    @Test
    fun `purgeRepository clears both slots`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, appA)
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, appB)

        repo.purgeRepository()

        assertNull(repo.swipeLeftAppFlow.first())
        assertNull(repo.swipeRightAppFlow.first())
    }

    @Test
    fun `purgeRepository on fresh repository is safe`() = runTest {
        val repo = createRepository()
        repo.purgeRepository()
        assertNull(repo.swipeLeftAppFlow.first())
        assertNull(repo.swipeRightAppFlow.first())
    }
}
