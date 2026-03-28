package com.github.reygnn.kolibri_launcher.contract

import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSwipeActionsRepository
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract Tests für SwipeActionsRepository.
 *
 * Verwaltet App-Zuweisungen für Swipe-Gesten (links/rechts).
 */
abstract class SwipeActionsRepositoryContractTest {

    abstract fun createRepository(): SwipeActionsRepository

    // ===========================================
    // INITIAL STATE
    // ===========================================

    @Test
    fun `swipeLeftAppFlow - initially null`() = runTest {
        val repo = createRepository()

        assertNull(repo.swipeLeftAppFlow.first())
    }

    @Test
    fun `swipeRightAppFlow - initially null`() = runTest {
        val repo = createRepository()

        assertNull(repo.swipeRightAppFlow.first())
    }

    // ===========================================
    // SET SWIPE ACTION - LEFT
    // ===========================================

    @Test
    fun `setSwipeAction - SWIPE_FROM_LEFT_TO_RIGHT updates swipeLeftAppFlow`() = runTest {
        val repo = createRepository()

        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, "com.example/MainActivity")

        assertEquals("com.example/MainActivity", repo.swipeLeftAppFlow.first())
    }

    @Test
    fun `setSwipeAction - SWIPE_FROM_LEFT_TO_RIGHT with null clears assignment`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, "com.example/MainActivity")

        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, null)

        assertNull(repo.swipeLeftAppFlow.first())
    }

    @Test
    fun `setSwipeAction - SWIPE_FROM_LEFT_TO_RIGHT overwrites previous`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, "com.first/Activity")

        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, "com.second/Activity")

        assertEquals("com.second/Activity", repo.swipeLeftAppFlow.first())
    }

    // ===========================================
    // SET SWIPE ACTION - RIGHT
    // ===========================================

    @Test
    fun `setSwipeAction - SWIPE_FROM_RIGHT_TO_LEFT updates swipeRightAppFlow`() = runTest {
        val repo = createRepository()

        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, "com.example/MainActivity")

        assertEquals("com.example/MainActivity", repo.swipeRightAppFlow.first())
    }

    @Test
    fun `setSwipeAction - SWIPE_FROM_RIGHT_TO_LEFT with null clears assignment`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, "com.example/MainActivity")

        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, null)

        assertNull(repo.swipeRightAppFlow.first())
    }

    // ===========================================
    // SET SWIPE ACTION - NONE
    // ===========================================

    @Test
    fun `setSwipeAction - NONE does nothing`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, "com.left/Activity")
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, "com.right/Activity")

        repo.setSwipeAction(SwipeSlot.NONE, "com.ignored/Activity")

        // Beide Flows unverändert
        assertEquals("com.left/Activity", repo.swipeLeftAppFlow.first())
        assertEquals("com.right/Activity", repo.swipeRightAppFlow.first())
    }

    // ===========================================
    // INDEPENDENCE
    // ===========================================

    @Test
    fun `setSwipeAction - left and right are independent`() = runTest {
        val repo = createRepository()

        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, "com.left/Activity")
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, "com.right/Activity")

        assertEquals("com.left/Activity", repo.swipeLeftAppFlow.first())
        assertEquals("com.right/Activity", repo.swipeRightAppFlow.first())

        // Nur left ändern
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, null)

        assertNull(repo.swipeLeftAppFlow.first())
        assertEquals("com.right/Activity", repo.swipeRightAppFlow.first())
    }

    // ===========================================
    // PURGE REPOSITORY
    // ===========================================

    @Test
    fun `purgeRepository - clears both assignments`() = runTest {
        val repo = createRepository()
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT, "com.left/Activity")
        repo.setSwipeAction(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT, "com.right/Activity")

        repo.purgeRepository()

        assertNull(repo.swipeLeftAppFlow.first())
        assertNull(repo.swipeRightAppFlow.first())
    }
}

/**
 * Verifiziert den Fake
 */
class FakeSwipeActionsRepositoryContractTest : SwipeActionsRepositoryContractTest() {
    override fun createRepository(): SwipeActionsRepository = FakeSwipeActionsRepository()
}