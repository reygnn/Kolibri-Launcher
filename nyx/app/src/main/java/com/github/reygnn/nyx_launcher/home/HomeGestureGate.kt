package com.github.reygnn.nyx_launcher.home

/**
 * Whether empty-space home gestures (swipe-up / long-press / double-tap) may
 * fire. They are suppressed while any modal home surface is up — wallpaper edit
 * mode, the folder overlay, or the context menu — because those overlays keep
 * `gesturesEnabled = true` so drags still capture, which would otherwise let the
 * shared gesture core run the empty-space callbacks over them.
 *
 * Pure predicate, so it is JVM-testable; MainActivity reads the live overlay
 * state and delegates the decision here.
 */
internal fun homeGesturesAllowed(
    editMode: Boolean,
    folderVisible: Boolean,
    contextMenuVisible: Boolean,
): Boolean = !editMode && !folderVisible && !contextMenuVisible
