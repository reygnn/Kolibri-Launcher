package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith

@ExperimentalCoroutinesApi
class SwipeActionsManagerTest {

    private lateinit var fakeDataStore: FakeDataStore

    // Keys müssen hier neu definiert werden, da sie im Manager private sind
    private val SWIPE_LEFT_KEY = stringPreferencesKey("swipe_left_app_component")
    private val SWIPE_RIGHT_KEY = stringPreferencesKey("swipe_right_app_component")

    @Before
    fun setup() {
        fakeDataStore = FakeDataStore()
    }

    /**
     * Helper to create manager instance using the testing factory method.
     * Uses Eagerly strategy to avoid timing issues in tests.
     */
    private fun createManager(scope: CoroutineScope): SwipeActionsManager {
        return SwipeActionsManager.createForTesting(
            dataStore = fakeDataStore,
            externalScope = scope,
            sharingStrategy = SharingStarted.Eagerly
        )
    }

    // ========== INITIAL STATE TESTS ==========

    @Test
    fun `flows - emit null when DataStore is empty`() = runTest {
        val manager = createManager(backgroundScope)

        // Eagerly Flow needs time to emit initial value
        advanceUntilIdle()

        Assert.assertNull(manager.swipeLeftAppFlow.first())
        Assert.assertNull(manager.swipeRightAppFlow.first())
    }

    @Test
    fun `flows - emit correct values when initialized with data`() = runTest {
        fakeDataStore.setInitialData(preferencesOf(
            SWIPE_LEFT_KEY to "com.left/App",
            SWIPE_RIGHT_KEY to "com.right/App"
        ))

        val manager = createManager(backgroundScope)
        advanceUntilIdle()

        Assert.assertEquals("com.left/App", manager.swipeLeftAppFlow.first())
        Assert.assertEquals("com.right/App", manager.swipeRightAppFlow.first())
    }

    // ========== SET ACTION TESTS ==========

    @Test
    fun `setSwipeAction - LEFT - saves component correctly`() = runTest {
        val manager = createManager(backgroundScope)
        val componentName = "com.new.app/LeftActivity"

        // Act
        manager.setSwipeAction(SwipeSlot.LEFT, componentName)
        advanceUntilIdle()

        // Assert DataStore
        val saved = fakeDataStore.data.first()[SWIPE_LEFT_KEY]
        Assert.assertEquals(componentName, saved)

        // Assert Flow
        Assert.assertEquals(componentName, manager.swipeLeftAppFlow.first())
    }

    @Test
    fun `setSwipeAction - RIGHT - saves component correctly`() = runTest {
        val manager = createManager(backgroundScope)
        val componentName = "com.new.app/RightActivity"

        // Act
        manager.setSwipeAction(SwipeSlot.RIGHT, componentName)
        advanceUntilIdle()

        // Assert DataStore
        val saved = fakeDataStore.data.first()[SWIPE_RIGHT_KEY]
        Assert.assertEquals(componentName, saved)

        // Assert Flow
        Assert.assertEquals(componentName, manager.swipeRightAppFlow.first())
    }

    @Test
    fun `setSwipeAction - with NULL - removes the key`() = runTest {
        // Arrange
        fakeDataStore.setInitialData(preferencesOf(SWIPE_LEFT_KEY to "com.remove/Me"))
        val manager = createManager(backgroundScope)
        advanceUntilIdle()

        // Act
        manager.setSwipeAction(SwipeSlot.LEFT, null)
        advanceUntilIdle()

        // Assert
        val saved = fakeDataStore.data.first()[SWIPE_LEFT_KEY]
        Assert.assertNull("Key should be removed", saved)
        Assert.assertNull("Flow should emit null", manager.swipeLeftAppFlow.first())
    }

    @Test
    fun `setSwipeAction - NONE - does nothing`() = runTest {
        val manager = createManager(backgroundScope)

        // Act
        manager.setSwipeAction(SwipeSlot.NONE, "com.ignore/Me")

        // Assert
        Assert.assertEquals(0, fakeDataStore.updateDataCallCount)
    }

    // ========== PURGE TESTS ==========

    @Test
    fun `purgeRepository - removes both swipe keys`() = runTest {
        // Arrange
        fakeDataStore.setInitialData(preferencesOf(
            SWIPE_LEFT_KEY to "com.left/App",
            SWIPE_RIGHT_KEY to "com.right/App",
            stringPreferencesKey("other_setting") to "keep_me" // Other keys should stay
        ))
        val manager = createManager(backgroundScope)
        advanceUntilIdle()

        // Act
        manager.purgeRepository()
        advanceUntilIdle()

        // Assert
        val prefs = fakeDataStore.data.first()
        Assert.assertNull(prefs[SWIPE_LEFT_KEY])
        Assert.assertNull(prefs[SWIPE_RIGHT_KEY])
        Assert.assertEquals("keep_me", prefs[stringPreferencesKey("other_setting")])
    }

    // ========== ERROR HANDLING TESTS ==========

    @Test
    fun `setSwipeAction - when DataStore edit fails - rethrows exception`() = runTest {
        fakeDataStore.makeEditFail()
        val manager = createManager(backgroundScope)

        // Manager re-throws so ViewModel can handle UI feedback
        assertFailsWith<IOException> {
            manager.setSwipeAction(SwipeSlot.LEFT, "com.fail/App")
        }
    }

    @Test
    fun `setSwipeAction - when CancellationException - propagates it`() = runTest {
        fakeDataStore.makeCancellable()
        val manager = createManager(backgroundScope)

        assertFailsWith<CancellationException> {
            manager.setSwipeAction(SwipeSlot.LEFT, "com.cancel/App")
        }
    }

    @Test
    fun `purgeRepository - when DataStore edit fails - logs silent error and does not crash`() = runTest {
        fakeDataStore.makeEditFail()
        val manager = createManager(backgroundScope)

        // Act - Should not crash (caught internally)
        manager.purgeRepository()

        // Assert - Check that edit was at least attempted
        Assert.assertEquals(1, fakeDataStore.updateDataCallCount)
    }
}