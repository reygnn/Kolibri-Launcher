// :feature-crashreporting — shared ACRA + consent pipeline (MONOREPO_MERGE_SPEC
// §3.5 / §4.2, Phase 3). Android-library consumed by both apps. Experimental:
// source files keep their kolibri_launcher.crashreporting.* package for now
// (zero-churn Phase-1a-style move); the neutral rename is a later step. The
// module NAMESPACE is neutral so its R/BuildConfig do not collide with :app.
//
// AnrReporter stays per-app (settings-store watermark + @IntoSet keep-list) and
// implements this module's AnrDrainer; each app supplies AcraConfig + drainer.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.github.reygnn.launcher.feature.crashreporting"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api(project(":core"))
    implementation(project(":common-ui"))

    implementation(libs.timber)
    implementation(libs.acra.core)
    implementation(libs.acra.http)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Unit tests for the crash-reporting slice (moved here with the code). The
    // Rule-2 consent contract + Fake + MainDispatcherRule/TimberRule live in
    // :kolibri:domain testFixtures; consuming them keeps this a test-only
    // coupling (Nyx uses only :feature's main artifact). A later cleanup lifts
    // MainDispatcherRule to a shared :core testFixtures.
    testImplementation(testFixtures(project(":kolibri:domain")))
    testImplementation(testFixtures(project(":kolibri:data")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit.ktx)
}
