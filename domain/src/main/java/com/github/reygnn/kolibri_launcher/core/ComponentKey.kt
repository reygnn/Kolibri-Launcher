package com.github.reygnn.kolibri_launcher.core

/**
 * Format rule for the flattened `package/class` component strings that every
 * component-based store (favorites, hidden apps, swipe actions) persists.
 *
 * Pure Kotlin on purpose: `:domain` is a plain-JVM module without the Android
 * SDK, so it cannot call `android.content.ComponentName.unflattenFromString`.
 * Keeping the rule here lets the repository fakes (`:domain` test fixtures) and
 * the production impls (`:data`) share ONE definition — no fake/impl drift, and
 * the contract test can pin the behaviour once for both.
 */
object ComponentKey {
    /**
     * Whether [value] is a well-formed flattened ComponentName: a single `/`
     * separator with a non-empty package before it and a non-empty class after
     * it. This accepts everything a real app component flattens to and rejects
     * the garbage that used to slip through silently (e.g. a bare package name
     * `"com.example.alpha"` with no class — TODO §15).
     *
     * Slightly stricter than `ComponentName.unflattenFromString`, which tolerates
     * an empty package (`"/cls"`); an empty-package component is never a real app,
     * so it is rejected here too.
     */
    fun isValid(value: String): Boolean {
        val separator = value.indexOf('/')
        return separator > 0 && separator < value.length - 1
    }
}
