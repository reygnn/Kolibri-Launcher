package com.github.reygnn.kolibri_launcher.ui.appdrawer

/**
 * Represents a one-shot scroll command for the AppDrawer RecyclerView.
 *
 * Using a sealed interface allows for future extensibility (e.g., ScrollToPosition,
 * ScrollToApp) while maintaining exhaustive when-expression checking.
 */
sealed interface AppDrawerScrollIntent {

    /**
     * Scroll to the top of the list.
     * Triggered after sort order changes or usage data resets.
     */
    data object ScrollToTop : AppDrawerScrollIntent

    // Future possibilities:
    // data class ScrollToPosition(val position: Int) : AppDrawerScrollIntent
    // data class ScrollToApp(val packageName: String) : AppDrawerScrollIntent
}