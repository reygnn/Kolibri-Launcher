// :core — shared, product-neutral foundation (MONOREPO_MERGE_SPEC §4.1, Phase 1a).
// Pure-Kotlin JVM module consumed by both apps: the closed, home-model-agnostic
// subset of Kolibri's former domain/core/* (9 files) + DispatcherModule. The
// remaining 8 former core files stay in :domain-kolibri (they couple to Kolibri
// domain models, directly or via AppConstants) — moving them would cycle.
// Package stays com.github.reygnn.kolibri_launcher.core for now (Phase 1a is the
// physical extraction; the neutral com.github.reygnn.launcher.* rename is 1b).
// Build note: kotlin-serialization is the carrier that puts the Kotlin Gradle plugin on
// this module's classpath under AGP 9 built-in Kotlin; launcher.jvm.library then applies
// org.jetbrains.kotlin.jvm from it (same in both :domain modules). Keep the alias.
plugins {
    id("launcher.jvm.library") // Kotlin JVM + toolchain/target 21 (build-logic)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // Publishes the shared test fixtures (core.testing: the ONE MainDispatcherRule,
    // recordEmissions, TESTING_CONVENTIONS.kt) so every module consumes one copy —
    // no per-module subclass, no per-module dispatcher choice (SPEC_NYX_REWRITE 1a).
    `java-test-fixtures`
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // Serialization runtime for the shared @Serializable backup models
    // (WallpaperLayerBackup); the plugin is already applied as the Kotlin-plugin
    // carrier — this adds the actual Json/@JsonNames runtime.
    implementation(libs.kotlinx.serialization.json)

    // hilt-core (JVM JAR) for DispatcherModule's @Module/@Provides; aggregation
    // runs in each :app via the hilt-android plugin.
    implementation(libs.hilt.core)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.truth) // A12: Truth is the one assertion library
    testImplementation(libs.kotlinx.coroutines.test)

    // Shared test fixtures: MainDispatcherRule (JUnit TestWatcher, swaps
    // Dispatchers.Main for one StandardTestDispatcher) and recordEmissions; every
    // module consumes them via `testFixtures(project(":core"))`.
    testFixturesImplementation(libs.junit)
    testFixturesImplementation(libs.truth) // contracts assert with Truth (A12)
    testFixturesImplementation(libs.kotlinx.coroutines.test)
}
