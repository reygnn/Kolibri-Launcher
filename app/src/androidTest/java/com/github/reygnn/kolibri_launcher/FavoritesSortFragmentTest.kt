package com.github.reygnn.kolibri_launcher

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class FavoritesSortFragmentTest : BaseAndroidTest() {

    private val testApps = arrayListOf(
        AppInfo("Zebra Browser", "Zebra Browser", "com.zebra", "com.zebra.MainActivity"),
        AppInfo("Apple Mail", "Apple Mail", "com.apple", "com.apple.MainActivity"),
        AppInfo("Banana Calc", "Banana Calc", "com.banana", "com.banana.MainActivity")
    )

    private val fragmentArgs = Bundle().apply {
        putParcelableArrayList(AppConstants.ARG_FAVORITES, testApps)
    }

    @Test
    fun displaysInitialOrderCorrectly() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        launchAndTrackFragment<FavoritesSortFragment>(fragmentArgs)

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.recyclerView))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 2))

        onView(withId(R.id.recyclerView)).check(matches(withItemTextAtPosition(0, "Zebra Browser")))
        onView(withId(R.id.recyclerView)).check(matches(withItemTextAtPosition(1, "Apple Mail")))
        onView(withId(R.id.recyclerView)).check(matches(withItemTextAtPosition(2, "Banana Calc")))
    }

    @Test
    fun clickAlphabeticalButton_sortsListAndSavesOrder() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val fakeRepo = favoritesOrderRepository as FakeFavoritesOrderRepository

        launchAndTrackFragment<FavoritesSortFragment>(fragmentArgs)

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.recyclerView))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 2))

        onView(withId(R.id.buttonAlphabetical)).perform(click())

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.recyclerView))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 5))

        onView(withId(R.id.recyclerView)).check(matches(withItemTextAtPosition(0, "Apple Mail")))
        onView(withId(R.id.recyclerView)).check(matches(withItemTextAtPosition(1, "Banana Calc")))
        onView(withId(R.id.recyclerView)).check(matches(withItemTextAtPosition(2, "Zebra Browser")))

        val expectedOrder = listOf(
            "com.apple/com.apple.MainActivity",
            "com.banana/com.banana.MainActivity",
            "com.zebra/com.zebra.MainActivity"
        )

        assertThat(fakeRepo.saveOrderCallCount).isGreaterThan(0)
        assertThat(fakeRepo.savedOrder).isEqualTo(expectedOrder)
    }

    @Test
    fun clickResetButton_fromInitialState_doesNothing() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val fakeRepo = favoritesOrderRepository as FakeFavoritesOrderRepository

        launchAndTrackFragment<FavoritesSortFragment>(fragmentArgs)

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.recyclerView))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 2))

        onView(withId(R.id.buttonReset)).perform(click())

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.recyclerView))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 3))

        onView(withId(R.id.recyclerView)).check(matches(withItemTextAtPosition(0, "Zebra Browser")))

        assertThat(fakeRepo.saveOrderCallCount).isGreaterThan(0)
    }

    @Test
    fun clickResetButton_resetsToOriginalOrder_afterSorting() = testCoroutineRule.runTestAndLaunchUI(
        mode = TestCoroutineRule.Mode.SAFE
    ) {
        val fakeRepo = favoritesOrderRepository as FakeFavoritesOrderRepository

        launchAndTrackFragment<FavoritesSortFragment>(fragmentArgs)

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.recyclerView))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 2))

        // Alphabetisch sortieren
        onView(withId(R.id.buttonAlphabetical)).perform(click())

        // Warte auf erste Coroutine
        runBlocking { delay(1000) }

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.recyclerView))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 5))

        // Reset klicken
        onView(withId(R.id.buttonReset)).perform(click())

        // Warte auf zweite Coroutine
        runBlocking { delay(1000) }

        testCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        onView(withId(R.id.recyclerView))
            .perform(EspressoTestUtils.waitForUiThreadMultiple(iterations = 5))

        val originalOrderComponents = listOf(
            "com.zebra/com.zebra.MainActivity",
            "com.apple/com.apple.MainActivity",
            "com.banana/com.banana.MainActivity"
        )

        // Beide Calls sollten jetzt passiert sein
        assertThat(fakeRepo.saveOrderCallCount).isAtLeast(2)
        assertThat(fakeRepo.savedOrder).isEqualTo(originalOrderComponents)
    }
}

fun withItemTextAtPosition(position: Int, expectedText: String): Matcher<View> {
    return object : androidx.test.espresso.matcher.BoundedMatcher<View, RecyclerView>(RecyclerView::class.java) {
        override fun describeTo(description: Description) {
            description.appendText("has item with text '$expectedText' at position $position")
        }

        override fun matchesSafely(recyclerView: RecyclerView): Boolean {
            val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
            val textView = viewHolder?.itemView?.findViewById<android.widget.TextView>(R.id.app_name)
            return textView?.text.toString() == expectedText
        }
    }
}