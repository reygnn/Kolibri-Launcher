// :core — shared, product-neutral foundation (MONOREPO_MERGE_SPEC §4.1, Phase 1a).
// Pure-Kotlin JVM module consumed by both apps: the closed, home-model-agnostic
// subset of Kolibri's former domain/core/* (9 files) + DispatcherModule. The
// remaining 8 former core files stay in :domain-kolibri (they couple to Kolibri
// domain models, directly or via AppConstants) — moving them would cycle.
// Package stays com.github.reygnn.kolibri_launcher.core for now (Phase 1a is the
// physical extraction; the neutral com.github.reygnn.launcher.* rename is 1b).
// Build note: kotlin-serialization is the carrier that makes kotlin("jvm") apply
// under AGP 9 built-in Kotlin (same as :domain); no comments inside plugins {}.
plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // Serialization runtime for the shared @Serializable backup models
    // (WallpaperLayerBackup); the plugin was already applied as the kotlin("jvm")
    // carrier — this adds the actual Json/@JsonNames runtime.
    implementation(libs.kotlinx.serialization.json)

    // hilt-core (JVM JAR) for DispatcherModule's @Module/@Provides; aggregation
    // runs in each :app via the hilt-android plugin.
    implementation(libs.hilt.core)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
