package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import com.github.reygnn.kolibri_launcher.ui.home.ZoomableImageView

/**
 * Pure state-machine description of the wallpaper edit-mode enter / exit
 * transition. The companion's [WallpaperEditTransition.Companion.targetState]
 * function maps a transition to the full [WallpaperEditState] that the
 * Fragment should apply to its views and its own properties.
 *
 * Pattern matches the existing pure-logic extracts (LayerButtonsState,
 * SnapIconResolver, ContextMenuResult, ...): this class makes the
 * *decision* — which target state applies — and the Fragment carries
 * out the *side effect* of writing it onto views.
 *
 * Scope is intentionally narrow: only the *fields that are set* during the
 * transition. Listener setup and cleanup, touch interception, animations,
 * layer operations, the save-button decision logic, and the snapshot
 * mechanism (held in WallpaperDelegate) all stay out — they are
 * side-effects or live elsewhere in the architecture.
 *
 * Compiler-driven exhaustiveness is the point. If a new field is added to
 * [WallpaperEditState], every `WallpaperEditState(...)` constructor call —
 * the two inside [targetState] and the two inside the per-transition tests
 * — fails to compile until the new field is given a value for both Enter
 * and Exit. That is the bug-class this extraction catches: "added a new
 * edit-mode field, forgot to reset it on exit". Compiler-time, not
 * runtime-in-front-of-the-user.
 */
sealed interface WallpaperEditTransition {

    data object Enter : WallpaperEditTransition

    data object Exit : WallpaperEditTransition

    companion object {
        /**
         * Maps a boolean isEditMode flag to the corresponding transition.
         * The Fragment receives the flag from `viewModel.isWallpaperEditMode`
         * and turns it into a transition before asking for the target state.
         */
        fun forMode(isEditMode: Boolean): WallpaperEditTransition =
            if (isEditMode) Enter else Exit

        /**
         * Returns the full target state for [transition].
         *
         * Both branches set every field of [WallpaperEditState]. Fields
         * which the legacy code only set in the Exit branch (snapMode,
         * toolbarAlpha, toolbarDockedTop) are now also set explicitly in
         * Enter, with the values the legacy Exit branch guaranteed. This
         * eliminates the implicit "Enter inherits from the previous Exit"
         * dependency that could leave stale state on a path where Exit did
         * not run (process death, exit error). See PR description for the
         * full disclosure.
         */
        fun targetState(transition: WallpaperEditTransition): WallpaperEditState =
            when (transition) {
                Enter -> WallpaperEditState(
                    isEditMode = true,
                    snapEnabled = false,
                    horizontalSnapEnabled = false,
                    verticalSnapEnabled = false,
                    snapMode = ZoomableImageView.SnapMode.EDGE,
                    overlayVisible = true,
                    rootLayoutAlpha = 0.7f,
                )
                Exit -> WallpaperEditState(
                    isEditMode = false,
                    snapEnabled = true,
                    horizontalSnapEnabled = true,
                    verticalSnapEnabled = true,
                    snapMode = ZoomableImageView.SnapMode.EDGE,
                    overlayVisible = false,
                    rootLayoutAlpha = 1.0f,
                )
            }
    }
}

/**
 * Full target state for a wallpaper edit-mode transition.
 *
 * Every field is required (not nullable, no defaults). Adding a new field
 * forces both Enter and Exit branches in
 * [WallpaperEditTransition.Companion.targetState] to be updated, plus the
 * two per-transition tests. That compiler pressure is the point.
 *
 * No Android types: [snapMode] is the project's own
 * [ZoomableImageView.SnapMode] enum, [overlayVisible] is a Boolean (the
 * Fragment maps it to View.VISIBLE / View.GONE on application).
 */
data class WallpaperEditState(
    val isEditMode: Boolean,
    val snapEnabled: Boolean,
    val horizontalSnapEnabled: Boolean,
    val verticalSnapEnabled: Boolean,
    val snapMode: ZoomableImageView.SnapMode,
    val overlayVisible: Boolean,
    val rootLayoutAlpha: Float,
)
