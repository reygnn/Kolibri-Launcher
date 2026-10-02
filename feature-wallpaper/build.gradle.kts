// :feature-wallpaper — the shared wallpaper feature (SPEC_NYX_REWRITE Phase 3). Android library,
// because it needs Uri, Bitmap and activity results (F1). Kolibri moves its pieces here in 3a
// (file lifecycle with the edit guard, edit session, display-settings store, FAB position, image
// picker, backup blob binding, composite path); Nyx follows in 3b. Source files live under the
// neutral com.github.reygnn.launcher.feature.wallpaper.* package; the namespace matches, so its
// R/BuildConfig do not collide with an app's.
plugins {
    id("launcher.android.library") // SDK, Java/Kotlin 21, unit-test setup (build-logic)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.github.reygnn.launcher.feature.wallpaper"
}

dependencies {
    api(project(":core"))
    implementation(project(":common-data"))
    implementation(project(":common-ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(testFixtures(project(":core"))) // shared MainDispatcherRule + recordEmissions
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
}
