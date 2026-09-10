package com.github.reygnn.kolibri_launcher.ui.main

/**
 * Two-way decision for how `MainActivity` should bootstrap on first
 * creation: launch the onboarding flow or initialize the main app
 * immediately.
 *
 * The side effects (launching `OnboardingActivity`, calling
 * `initializeMainApp()`) stay in `MainActivity` — they require Activity
 * scope and lifecycle-aware coroutine launching. What is testable on the
 * JVM is the *decision* of which path to take, given the persisted
 * onboarding flag.
 *
 * Pattern follows the existing pure-decision extracts in this codebase
 * (`AppLaunchAction`, `RenameDecision`, `WallpaperSaveAction`,
 * `LayerButtonsState`): the class makes the decision; the caller carries
 * out the side effect.
 */
sealed interface InitialSetupAction {

    /** First-time install. Caller should launch the onboarding flow. */
    data object LaunchOnboarding : InitialSetupAction

    /**
     * The user has already completed onboarding. Caller should skip
     * onboarding and initialize the main app directly.
     */
    data object InitializeImmediately : InitialSetupAction

    companion object {
        /**
         * @param onboardingCompleted whether the persisted onboarding-
         *   completed flag is set.
         */
        fun decide(onboardingCompleted: Boolean): InitialSetupAction =
            if (onboardingCompleted) InitializeImmediately else LaunchOnboarding
    }
}
