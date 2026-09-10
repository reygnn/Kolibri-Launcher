package com.github.reygnn.nyx_launcher.home.model

/**
 * Stable identity of a launchable activity.
 *
 * The durable "which app + activity is this" handle across DataStore round-trips
 * and re-installs of the target package: a placed item keeps its position while
 * its icon is re-resolved. See ICON_HOME_MODEL_SPEC §2.1.
 *
 * == WHY userSerial ==
 * Present from v1 even though v1 only ever sets 0 (the primary user), so that
 * adding work-profile / cloned-app support later does NOT break the persisted
 * layout blob's schema. It carries the *serial* (a [Long]), never an Android
 * `UserHandle` — the domain stays framework-free (ICON_HOME_MODEL_SPEC §0.2,
 * IHM-INV-1).
 */
data class ComponentKey(
    val packageName: String,
    val className: String,
    val userSerial: Long = 0L,
)
