package com.github.reygnn.kolibri_launcher

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class OnboardingActivityBarebonesTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Test
    fun activity_launches_without_crashing() {

        val scenario = ActivityScenario.launch(OnboardingActivity::class.java)

        assertThat(scenario.state).isAtLeast(Lifecycle.State.RESUMED)

        Thread.sleep(1000)
        assertThat(scenario.state).isNotEqualTo(Lifecycle.State.DESTROYED)
    }
}
