// :feature-backup — the shared backup engine (SPEC_NYX_REWRITE Phase 2a). Pure JVM on
// purpose (U1): the engine works on streams with an injected dispatcher, so it is tested
// without Robolectric; opening SAF documents stays with the apps.
//
// U1-U4 in comments are the spec's engine takeover items (written without the umlaut,
// which Rule 13 reads as German).
//
// 2a-2: the container format (E5a) — manifest first, blobs by SHA-256, caps, staging.
// 2a-3: the engine over it (sections, producer check, staged-blob ownership) and the
// LegacyFormatReader port (Hilt set, empty by default).
// 2b-0: the app-neutral frame around the engine, so Nyx uses it instead of a copy —
// writeOrDiscard (U3) and readStaged (staging dir, size check before reading).
// 2b-2c: testFixtures with the shared backup contracts (A2); each app runs a subclass.
plugins {
    id("launcher.jvm.library") // Kotlin JVM + toolchain/target 21 (build-logic)
    alias(libs.plugins.kotlin.serialization) // also the Kotlin-plugin carrier under AGP 9
    alias(libs.plugins.ksp)
    `java-test-fixtures` // shared contracts (A2), like :core
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.core)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlin.test.junit) // assertFailsWith (A12 exception)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core"))) // shared MainDispatcherRule

    // The contracts expose ComponentKey and MainDispatcherRule to the app subclasses.
    testFixturesApi(project(":core"))
    testFixturesApi(testFixtures(project(":core")))
    testFixturesImplementation(libs.junit)
    testFixturesImplementation(libs.truth) // contracts assert with Truth (A12)
    testFixturesImplementation(libs.kotlinx.coroutines.test)
}
