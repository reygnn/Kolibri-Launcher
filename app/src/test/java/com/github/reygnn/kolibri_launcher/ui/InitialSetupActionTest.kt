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
    fun `decide returns LaunchOnboarding on first run with no backup`() {
        val result = InitialSetupAction.decide(
            onboardingCompleted = false,
            backupPresent = false,
        )
        assertEquals(InitialSetupAction.LaunchOnboarding, result)
    }

    @Test
    fun `decide returns InitializeImmediately when onboarding already completed`() {
        val result = InitialSetupAction.decide(
            onboardingCompleted = true,
            backupPresent = false,
        )
        assertEquals(InitialSetupAction.InitializeImmediately, result)
    }

    @Test
    fun `decide skips onboarding when a backup is present`() {
        // Wipe+reinstall scenario: the user has already onboarded once
        // and the OS or a previous run wrote a backup file. Forcing them
        // through onboarding again would be a UX regression — the backup
        // itself signals an experienced user. The persisted onboarding
        // flag is false here because the wipe cleared DataStore.
        val result = InitialSetupAction.decide(
            onboardingCompleted = false,
            backupPresent = true,
        )
        assertEquals(InitialSetupAction.InitializeImmediately, result)
    }

    @Test
    fun `decide returns InitializeImmediately when both flags are true`() {
        val result = InitialSetupAction.decide(
            onboardingCompleted = true,
            backupPresent = true,
        )
        assertEquals(InitialSetupAction.InitializeImmediately, result)
    }
}
