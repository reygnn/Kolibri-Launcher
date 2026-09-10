package com.github.reygnn.kolibri_launcher.ui.customnames

import com.github.reygnn.kolibri_launcher.core.isEffectivelyBlank

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
     * Either the input is effectively blank (empty, whitespace, or only
     * invisible combining / format marks), or it equals the app's original
     * name. Both should clear any custom name (the original is restored).
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
         *  1. effectively blank input → [Remove]
         *  2. too long                → [TooLong]
         *  3. equals original         → [Remove]
         *  4. otherwise               → [Set]
         *
         * "Effectively blank" ([isEffectivelyBlank]) is broader than `isEmpty`
         * and `isBlank`: it also catches a name made only of invisible combining
         * marks (e.g. a lone U+0301), which would otherwise be persisted as a
         * visually empty row. Whitespace-only and combining-only inputs alike
         * therefore route straight to [Remove] here — the same user-visible
         * outcome the downstream ViewModel already produced for whitespace, now
         * decided at one place and extended to the invisible-mark case.
         */
        fun decide(newName: String, originalName: String): RenameDecision = when {
            newName.isEffectivelyBlank() -> Remove
            newName.length > MAX_APP_NAME_LENGTH -> TooLong(MAX_APP_NAME_LENGTH)
            newName == originalName -> Remove
            else -> Set(newName)
        }
    }
}
