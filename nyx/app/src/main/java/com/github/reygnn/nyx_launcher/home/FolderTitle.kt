package com.github.reygnn.nyx_launcher.home

/**
 * Canonicalizes a raw folder-title edit before it is persisted (A2): surrounding
 * whitespace is trimmed, so a blank / whitespace-only entry collapses to "".
 *
 * An empty title is a LEGITIMATE state — the folder sheet renders the localized
 * default via the `folder_default_title` hint when the title is empty, and folders
 * are created empty — so this never rejects; it only normalizes. Without the trim a
 * whitespace-only title ("   ") would persist verbatim, suppressing the default hint
 * while showing no real name. Pure/Android-free so it is JVM-testable; MainActivity
 * applies it in applyFolderTitleEdit.
 */
internal fun normalizeFolderTitle(raw: String): String = raw.trim()
