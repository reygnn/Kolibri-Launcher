// :common-testing-android — product-neutral instrumentation test-support,
// shared by both apps' androidTest source sets (MONOREPO_MERGE_SPEC §4.x).
//
// The code lives in src/main ON PURPOSE: it is consumed via
// `androidTestImplementation(project(":common-testing-android"))`, so the
// Espresso/androidx.test surface it exposes lands ONLY on each consumer's
// androidTest classpath — never in a production `implementation`. Do NOT add
// this module as a plain `implementation` dependency anywhere.
//
// Contains: the launcher-neutral primitives (awaitUntil, onMainSync,
// currentResumed) and BasePage. The concrete page objects stay per-app
// (kolibri Home != nyx Home) — see the tapl package in each :app.
plugins {
    id("launcher.android.library") // SDK, Java/Kotlin 21, unit-test setup (build-logic)
}

android {
    namespace = "com.github.reygnn.launcher.testing"
}

dependencies {
    // `api`, not `implementation`: BasePage's protected surface returns
    // Espresso ViewInteraction, and page objects in the consuming androidTest
    // sets build directly on Espresso + RecyclerViewActions. Exposing them
    // transitively keeps the consumer build files from re-declaring the stack.
    api(libs.androidx.test.espresso.core)
    api(libs.androidx.test.espresso.contrib)
    api(libs.androidx.test.runner)
    api(libs.androidx.test.rules)
    api(libs.androidx.test.core.ktx)

    // awaitUntil's polling loop (runBlocking / delay / withTimeoutOrNull).
    implementation(libs.kotlinx.coroutines.android)
}
