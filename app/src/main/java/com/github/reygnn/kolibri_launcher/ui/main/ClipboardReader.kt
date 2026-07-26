/*
 * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.github.reygnn.kolibri_launcher.ui.main

import android.content.ClipData

/** Clipboard text plus the source app's sensitivity flag. */
data class ClipboardRead(val text: String?, val isSensitive: Boolean)

/**
 * The order-sensitive part of reading the primary clip, lifted out of
 * [MainActivity] so a JVM test can pin it (CLAUDE.md Rule 10). The Android
 * touch points — reading `ClipDescription.EXTRA_IS_SENSITIVE` and coercing the
 * first item to text — stay in the Activity as the two injected lambdas; this
 * object owns only the branching between them, which is where the one
 * security-relevant decision lives.
 *
 * **The invariant to preserve: sensitivity is checked BEFORE any coercion.** A
 * URI-backed clip marked sensitive (a password manager copying a secret) must
 * never have its stream read at all — [coerceFirstItem] is not allowed to run
 * for such a clip, because coercing a `content://` item opens the provider and
 * reads it to EOF. Returning `isSensitive = true` with a null text also lets
 * the caller show the "sensitive clip" toast instead of forwarding anything to
 * a search provider. That ordering is the property the test protects; treat it
 * as load-bearing, not incidental.
 */
object ClipboardReader {

    /**
     * @param clip the system primary clip, or null when the clipboard is empty
     *   or unavailable.
     * @param isSensitive reads the source app's `EXTRA_IS_SENSITIVE` flag. Kept
     *   as a lambda so this stays free of `ClipDescription` / `PersistableBundle`.
     * @param coerceFirstItem coerces the first clip item to text. Invoked ONLY
     *   for a non-sensitive clip (see the class KDoc) — a sensitive clip returns
     *   before it can run, so its backing stream is never touched.
     */
    fun read(
        clip: ClipData?,
        isSensitive: (ClipData) -> Boolean,
        coerceFirstItem: (ClipData) -> CharSequence?,
    ): ClipboardRead {
        if (clip == null) return ClipboardRead(text = null, isSensitive = false)
        if (isSensitive(clip)) return ClipboardRead(text = null, isSensitive = true)
        return ClipboardRead(text = coerceFirstItem(clip)?.toString(), isSensitive = false)
    }
}
