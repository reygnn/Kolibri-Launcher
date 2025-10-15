package com.github.reygnn.kolibri_launcher

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class OnboardingActivityUiIsolationTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @BindValue
    @JvmField
    val fakeViewModel: ViewModel = FakeOnboardingViewModel()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun doneButton_whenClicked_callsOnDoneClickedOnViewModel() {
        // Arrange
        // KORREKTUR 1: Hole eine korrekt getypte Referenz auf den Fake.
        val viewModel = fakeViewModel as FakeOnboardingViewModel

        // KORREKTUR 2: Erstelle einen "test-sicheren" Zustand OHNE echte Ressourcen-IDs.
        // Wir verwenden 0, was für `setText(resId)` eine sichere, leere Operation ist.
        val testSafeState = OnboardingUiState(
            titleResId = 0,
            subtitleResId = 0,
            selectableApps = emptyList(), // Leere Listen sind auch sicher.
            selectedApps = emptyList()
        )
        // KORREKTUR 3: Setze diesen sicheren Zustand, BEVOR die Activity startet.
        viewModel.setState(testSafeState)

        // Jetzt starten wir die Activity. Sie wird beim Start sofort den sicheren Zustand vorfinden.
        val intent = Intent(ApplicationProvider.getApplicationContext(), OnboardingActivity::class.java)
        ActivityScenario.launch<OnboardingActivity>(intent)

        // Stelle sicher, dass der Zähler immer noch 0 ist.
        assertThat(viewModel.onDoneClickedCallCount).isEqualTo(0)

        // Act
        onView(withId(R.id.done_button)).perform(click())

        // Assert
        assertThat(viewModel.onDoneClickedCallCount).isEqualTo(1)
    }
}