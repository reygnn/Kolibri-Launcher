package com.github.reygnn.kolibri_launcher.ui.util

import android.os.StrictMode

/**
 * Runs [block] with a relaxed (LAX) StrictMode thread policy and restores the
 * previous policy afterwards.
 *
 * Purpose: suppress the benign StrictMode DiskRead violations Samsung raises via
 * on-UI-thread IPC/DB reads inside otherwise innocuous framework calls
 * (`Toast.makeText`, `PreferenceFragmentCompat.setPreferencesFromResource`).
 * StrictMode is armed in DEBUG only (see `KolibriLauncherApp.setupStrictMode`),
 * so in release this is an effectively free save/restore.
 *
 * This is the single owner of the save → relax → restore dance that
 * `BaseActivity`, the toast helpers, and `SettingsFragment` previously
 * open-coded.
 */
inline fun <T> withRelaxedStrictMode(block: () -> T): T {
    val oldPolicy = StrictMode.getThreadPolicy()
    StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
    return try {
        block()
    } finally {
        StrictMode.setThreadPolicy(oldPolicy)
    }
}
