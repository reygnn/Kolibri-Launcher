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
 * onboarding flag and the presence of a backup file.
 *
 * Pattern follows the existing pure-decision extracts in this codebase
 * (`AppLaunchAction`, `RenameDecision`, `WallpaperSaveAction`,
 * `LayerButtonsState`): the class makes the decision; the caller carries
 * out the side effect.
 */
sealed interface InitialSetupAction {

    /**
     * First-time install with no backup. Caller should launch the
     * onboarding flow.
     */
    data object LaunchOnboarding : InitialSetupAction

    /**
     * Either the user has already completed onboarding, or a backup is
     * present (typical wipe+reinstall scenario). Caller should skip
     * onboarding and initialize the main app directly — the user has
     * already gone through onboarding once and can restore from backup.
     */
    data object InitializeImmediately : InitialSetupAction

    companion object {
        /**
         * @param onboardingCompleted whether the persisted onboarding-
         *   completed flag is set.
         * @param backupPresent whether a DataStore backup file exists in
         *   the app's external/cache directory. If true, this is treated
         *   as a wipe+reinstall: skip onboarding even if the
         *   onboarding-completed flag is missing, since the backup itself
         *   signals an experienced user.
         */
        fun decide(
            onboardingCompleted: Boolean,
            backupPresent: Boolean,
        ): InitialSetupAction =
            if (!onboardingCompleted && !backupPresent) LaunchOnboarding
            else InitializeImmediately
    }
}
