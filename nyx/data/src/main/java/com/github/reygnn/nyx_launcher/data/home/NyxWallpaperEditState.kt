package com.github.reygnn.nyx_launcher.data.home

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Nyx's wallpaper edit session is open right now (3b-1). The coordinator lives in
 * `MainActivity`, but "remove wallpaper" can also be triggered from `SettingsActivity`; this
 * process-wide flag lets [NyxWallpaperImageSetter] keep choose, remove and the orphan GC away from
 * the files of an open session. `MainActivity` mirrors the coordinator's edit mode into it and
 * resets it when the activity (and with it the session) goes away.
 *
 * Interim: with 3b-3 the shared edit session and its operations take over, and this flag goes.
 * Until then, a choose or remove from outside during an open session is overwritten when the
 * session commits or cancels (no data loss, the files stay; see [NyxWallpaperImageSetter]).
 *
 * Written on Main (the collector in `MainActivity`), read in suspend functions that need not resume
 * on Main — hence [Volatile] for cross-thread visibility.
 */
@Singleton
class NyxWallpaperEditState @Inject constructor() {
    @Volatile
    var sessionOpen: Boolean = false
}
