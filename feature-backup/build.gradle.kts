// :feature-backup — the shared backup engine (SPEC_NYX_REWRITE Phase 2a). Pure JVM on
// purpose (U1): the engine works on streams with an injected dispatcher, so it is tested
// without Robolectric; opening SAF documents stays with the apps.
//
// U1-U4 in comments are the spec's engine takeover items (written without the umlaut,
// which Rule 13 reads as German).
//
// Step 2a-2: the container format (E5a) — manifest first, blobs by SHA-256, caps,
// staging. App schemas, the engine proper and the legacy port follow.
plugins {
    id("launcher.jvm.library") // Kotlin JVM + toolchain/target 21 (build-logic)
    alias(libs.plugins.kotlin.serialization) // also the Kotlin-plugin carrier under AGP 9
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlin.test.junit) // assertFailsWith (A12 exception)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core"))) // shared MainDispatcherRule
}
