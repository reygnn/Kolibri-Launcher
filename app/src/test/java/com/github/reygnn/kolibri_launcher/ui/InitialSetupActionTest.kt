package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.main.InitialSetupAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Pure JVM tests for [InitialSetupAction.Companion.decide]. No MockK,
 * no Robolectric — same shape as [AppLaunchActionTest], etc.
 *
 * The class itself only encodes the first-run decision; the side effects
 * (launching `OnboardingActivity`, calling `initializeMainApp()`) stay
 * in `MainActivity` and are exercised manually / via integration runs of
 * the launcher.
 */
class InitialSetupActionTest {

    @get:Rule
    val timberRule = TimberRule()

    @Test
    fun `decide returns LaunchOnboarding when onboarding has not been completed`() {
        val result = InitialSetupAction.decide(onboardingCompleted = false)
        assertEquals(InitialSetupAction.LaunchOnboarding, result)
    }

    @Test
    fun `decide returns InitializeImmediately when onboarding already completed`() {
        val result = InitialSetupAction.decide(onboardingCompleted = true)
        assertEquals(InitialSetupAction.InitializeImmediately, result)
    }
}
