package com.github.reygnn.launcher.feature.crashreporting.resilience

/**
 * Per-app ACRA endpoint configuration, injected into [CrashReportingBootstrap]
 * instead of read from a hard-wired `BuildConfig`.
 *
 * This is the seam that lets the crash-reporting slice become a shared feature
 * module consumed by more than one app (MONOREPO_MERGE_SPEC §3, BSP-INV-4 spirit):
 * a feature module has no app `BuildConfig`, so each application supplies its own
 * values (server URL + basic-auth, all fed from that app's `secrets.properties`)
 * and its own `BuildConfig` class (ACRA reads version metadata off it).
 */
data class AcraConfig(
    val buildConfigClass: Class<*>,
    val url: String,
    val login: String,
    val password: String,
)
