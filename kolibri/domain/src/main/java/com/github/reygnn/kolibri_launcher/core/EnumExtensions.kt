package com.github.reygnn.kolibri_launcher.core

/**
 * Parses [this] into enum constant [T], returning `null` when the string is
 * `null` or names no constant of [T]. Callers supply their own fallback via
 * the elvis operator, e.g. `name.toEnumOrNull<SortOrder>() ?: DEFAULT`.
 *
 * Centralises the `try { Enum.valueOf(name) } catch { default }` idiom that
 * the DataStore read paths previously re-implemented per enum setting. The
 * catch is narrowed to [IllegalArgumentException] — the only exception
 * `enumValueOf` throws for an unknown name — per CLAUDE.md Rule 11.
 */
inline fun <reified T : Enum<T>> String?.toEnumOrNull(): T? {
    if (this == null) return null
    return try {
        enumValueOf<T>(this)
    } catch (e: IllegalArgumentException) {
        null
    }
}
