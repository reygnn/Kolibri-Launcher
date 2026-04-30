package com.github.reygnn.kolibri_launcher.ui.customnames

/**
 * Four-way decision for what to do with a rename input from the
 * `CustomNamesActivity` rename dialog. Pure logic, no Android types —
 * the Activity calls [decide] and dispatches the result to either the
 * ViewModel (for state changes) or `showError` (for the validation
 * failure). String formatting and Toast handling stay in the Activity.
 *
 * Pattern follows the existing pure-decision extracts in this codebase
 * (`WallpaperSaveAction`, `LayerButtonsState`, etc.): the class makes
 * the decision; callers carry out the side effect.
 */
sealed interface RenameDecision {

    /**
     * Either the input is empty, or it equals the app's original name.
     * Both should clear any custom name (the original is restored).
     */
    data object Remove : RenameDecision

    /**
     * Input exceeds [maxLength]. Caller renders an error to the user.
     */
    data class TooLong(val maxLength: Int) : RenameDecision

    /**
     * Input is non-empty, within length limit, and differs from the
     * original name. Caller persists the new name.
     */
    data class Set(val name: String) : RenameDecision

    companion object {
        /**
         * Maximum allowed length for a custom app name, in code-units.
         * The previous home of this constant was a `companion object`
         * `private const val` inside `CustomNamesActivity`; moved here so
         * the threshold and the decision that uses it sit together.
         */
        const val MAX_APP_NAME_LENGTH = 50

        /**
         * Branch precedence:
         *  1. empty input       → [Remove]
         *  2. too long          → [TooLong]
         *  3. equals original   → [Remove]
         *  4. otherwise         → [Set]
         *
         * Note: emptiness is checked with `isEmpty`, not `isBlank` — a
         * pure-whitespace input still routes to [Set] here, matching the
         * pre-extraction behavior of `CustomNamesActivity.handleRename`.
         * The downstream `CustomNamesViewModel.setCustomName` then maps
         * blank-or-equals-original back to a remove. Preserving that
         * historical behavior was a deliberate non-goal of this
         * extraction; revisit if a test surfaces a real symptom.
         */
        fun decide(newName: String, originalName: String): RenameDecision = when {
            newName.isEmpty() -> Remove
            newName.length > MAX_APP_NAME_LENGTH -> TooLong(MAX_APP_NAME_LENGTH)
            newName == originalName -> Remove
            else -> Set(newName)
        }
    }
}
