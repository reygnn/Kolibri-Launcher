// =============================================================================
// :baselineprofile — Baseline Profile producer (com.android.test, not shipped)
// =============================================================================
//
// Generates the release Baseline Profile that the :app plugin bakes into the
// shipped APK. Kept SEPARATE from :macrobenchmark on purpose: the
// androidx.baselineprofile plugin creates its own `nonMinifiedRelease`
// generation variants on :app, and :macrobenchmark has a hand-tuned
// `beforeVariants { it.enable = it.buildType == "benchmark" }` gate that would
// disable exactly those variants — the two cannot share a module. Splitting
// "latency gate" (:macrobenchmark) from "profile generation" (:baselineprofile)
// keeps each module's variant handling clean.
//
// Local device only — matching this project's "androidTest = real device =
// local" posture (CLAUDE.md Rule 10). Like :macrobenchmark it is NOT run in the
// device-free GitHub CI; CI only compile-checks it so it cannot rot (android.yml).
//
// Generate:  ./gradlew :app:generateBaselineProfile
// (needs a connected, unlocked device that is PAST ONBOARDING with at least one
//  home favorite; the profile is captured against the app's minified release and
//  reconciled over the R8 mapping by the plugin.)

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.github.reygnn.kolibri_launcher.baselineprofile"
    compileSdk = 37 // matches :app — DO NOT CHANGE independently

    defaultConfig {
        minSdk = 36 // :app's minSdk; the target device is always >= this
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Mirror :macrobenchmark's toolchain verbatim — this is the exact Kotlin /
    // JDK-21 configuration that works for a com.android.test module in this repo.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    targetProjectPath = ":app"

    // NO manual beforeVariants gate here (that is a :macrobenchmark-only concern).
    // The androidx.baselineprofile plugin drives variant selection itself — the
    // whole reason this module is separate from :macrobenchmark.
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}
