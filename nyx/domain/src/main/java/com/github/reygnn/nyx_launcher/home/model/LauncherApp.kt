package com.github.reygnn.nyx_launcher.home.model

/**
 * What the domain knows about an installed, launchable app.
 *
 * Holds NO icon by design — icons are *referenced* ([IconRef]) and rendered in
 * `:data`, never here (IHM-INV-1). Display name is `customName ?: label`; the
 * domain keeps both and lets the UI choose.
 */
data class LauncherApp(
    val key: ComponentKey,
    val label: String,
    val customName: String? = null,
)
