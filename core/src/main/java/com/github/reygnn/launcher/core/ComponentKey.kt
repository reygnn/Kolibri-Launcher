package com.github.reygnn.launcher.core

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
 *
 * The constructor is private (SPEC_NYX_REWRITE B14): every key is built by [of] (or
 * [parse], which goes through [of]), so the manifest short form `pkg/.Cls` is ALWAYS
 * normalized to `pkg/pkg.Cls`. Before, callers had to remember to normalize — one that
 * forgot produced a second, never-matching key for the same app (an imported favorite
 * spelled `.Main` never met the installed app). Backups (E1) and every store read rely
 * on this: a stored short form is normalized on read — identity, not data migration
 * (Rule 5); the stored text stays until it is written anew.
 */
@ConsistentCopyVisibility
data class ComponentKey private constructor(
    val packageName: String,
    val className: String, // always absolute — guaranteed by [of]
) {
    /** Persistence/backup projection: exactly the historical `"pkg/cls"` string. */
    val flat: String = "$packageName/$className"

    companion object {
        /**
         * The ONE way to build a key. A leading-dot relative class (`.Main`, the manifest
         * short form) is expanded to `package.Main`; a fully-qualified name is kept as is.
         */
        fun of(packageName: String, className: String): ComponentKey =
            ComponentKey(packageName, normalizeClassName(packageName, className))

        /**
         * The single definition of the long-form rule (also behind
         * `AppInfo.normalizedClassName`): Android accepts both spellings, but an explicit
         * `ComponentName` used to launch must carry the fully-qualified class, and two
         * spellings of one class must be one identity.
         */
        fun normalizeClassName(packageName: String, className: String): String =
            if (className.startsWith(".")) "$packageName$className" else className

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
         * decompositions used to lack — former TODO §15). Goes through [of], so a
         * stored short form `pkg/.Cls` comes back as `pkg/pkg.Cls` (B14).
         */
        fun parse(value: String): ComponentKey? {
            if (!isValid(value)) return null
            val separator = value.indexOf('/')
            return of(value.substring(0, separator), value.substring(separator + 1))
        }
    }
}
