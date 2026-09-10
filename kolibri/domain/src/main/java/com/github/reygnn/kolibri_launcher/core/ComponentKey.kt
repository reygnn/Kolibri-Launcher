package com.github.reygnn.kolibri_launcher.core

/**
 * Structured identity of a launcher entry: the `package` + `class` pair that
 * every component-based store (favorites, hidden apps, swipe actions) persists
 * as a flattened `package/class` string.
 *
 * The structured form is the identity; [flat] is its persistence/backup
 * projection and [parse] its inverse — both live here, so the flattened wire
 * format is defined in exactly ONE place and can never drift against the
 * component decomposition scattered across the repositories (former TODO §15).
 *
 * Pure Kotlin on purpose: `:domain` is a plain-JVM module without the Android
 * SDK, so it cannot call `android.content.ComponentName.unflattenFromString`.
 * Keeping the rule here lets the repository fakes (`:domain` test fixtures) and
 * the production impls (`:data`) share ONE definition — no fake/impl drift, and
 * the contract test can pin the behaviour once for both.
 */
data class ComponentKey(
    val packageName: String,
    val className: String, // absolute; callers normalize (see AppInfo.normalizedClassName)
) {
    /** Persistence/backup projection: exactly the historical `"pkg/cls"` string. */
    val flat: String = "$packageName/$className"

    companion object {
        /**
         * Whether [value] is a well-formed flattened ComponentName: a single `/`
         * separator with a non-empty package before it and a non-empty class after
         * it. This accepts everything a real app component flattens to and rejects
         * the garbage that used to slip through silently (e.g. a bare package name
         * `"com.example.alpha"` with no class — former TODO §15).
         *
         * Slightly stricter than `ComponentName.unflattenFromString`, which tolerates
         * an empty package (`"/cls"`); an empty-package component is never a real app,
         * so it is rejected here too.
         */
        fun isValid(value: String): Boolean {
            val separator = value.indexOf('/')
            return separator > 0 && separator < value.length - 1
        }

        /**
         * Read a flattened projection back into a structured key; `null` when
         * [value] is malformed (the guard the scattered `substringBefore('/')`
         * decompositions used to lack — former TODO §15).
         */
        fun parse(value: String): ComponentKey? {
            if (!isValid(value)) return null
            val separator = value.indexOf('/')
            return ComponentKey(value.substring(0, separator), value.substring(separator + 1))
        }
    }
}
