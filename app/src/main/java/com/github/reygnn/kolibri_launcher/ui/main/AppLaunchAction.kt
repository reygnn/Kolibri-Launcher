package com.github.reygnn.kolibri_launcher.ui.main

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo

/**
 * Two-way decision for how `MainActivity` should handle a `UiEvent.LaunchApp`:
 * pop the back stack first (the user is on the app drawer) or just launch.
 *
 * The pop side effect itself stays in `MainActivity` — `NavController` is an
 * Activity-scope concern and isn't testable on the JVM. What is testable is
 * the *decision* of whether to pop, given the current destination id and the
 * drawer's destination id. That's what this class captures.
 *
 * Pattern follows the existing pure-decision extracts in this codebase
 * (`RenameDecision`, `WallpaperSaveAction`, `LayerButtonsState`): the class
 * makes the decision; the caller carries out the side effect.
 */
sealed interface AppLaunchAction {

    val app: AppInfo

    /**
     * The user is currently on the app drawer. Caller should pop the
     * NavController back stack (returning to home) and then launch [app].
     */
    data class PopThenLaunch(override val app: AppInfo) : AppLaunchAction

    /**
     * The user is on a non-drawer destination (typically home itself, but
     * also `null` when the NavController hasn't been wired up yet — we
     * treat that defensively as "no pop needed").
     */
    data class JustLaunch(override val app: AppInfo) : AppLaunchAction

    companion object {
        /**
         * @param currentDestinationId the navigation destination the user
         *   is on right now, or `null` if the NavController is not yet
         *   set up. `null` routes to [JustLaunch] — the alternative would
         *   be popping a back stack that may not exist, which is worse
         *   than skipping the close-drawer animation.
         * @param drawerDestinationId the navigation graph id for the app
         *   drawer fragment.
         */
        fun decide(
            currentDestinationId: Int?,
            drawerDestinationId: Int,
            app: AppInfo,
        ): AppLaunchAction =
            if (currentDestinationId == drawerDestinationId) PopThenLaunch(app)
            else JustLaunch(app)
    }
}
